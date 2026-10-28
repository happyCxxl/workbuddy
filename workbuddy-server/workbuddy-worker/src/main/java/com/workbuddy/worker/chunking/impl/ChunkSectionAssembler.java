package com.workbuddy.worker.chunking.impl;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.chunk.PipelineKey;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.slice.ChunkPostProcessor;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 切片章节装配：标题栈维护（标题不成片）、章节归属、父子片构建与后置处理链执行。
 * 单次运行的标题栈与章节列表由调用方持有，本类只做无状态装配。
 */
final class ChunkSectionAssembler {

    private final ChunkProperties properties;
    private final List<ChunkPostProcessor> postProcessors;

    /**
     * 构造章节装配器。
     *
     * @param properties     切片参数（标题路径上限缺省值）
     * @param postProcessors 后置处理器链（注册顺序即执行顺序）
     */
    ChunkSectionAssembler(ChunkProperties properties, List<ChunkPostProcessor> postProcessors) {
        this.properties = properties;
        this.postProcessors = postProcessors;
    }

    /**
     * 打开新章节：按结构层级刷新标题栈（空标题不改变栈，超过上限丢最前级），
     * 返回章节容器并同步当前标题路径。
     *
     * @param titleStack   当前标题栈
     * @param titleElement 标题视图元素
     * @param context      切片上下文
     * @param strategy     切片策略快照（标题路径上限来源）
     * @return 章节容器
     */
    Section openSection(Deque<String> titleStack, ViewElement titleElement, SliceContext context,
                        ChunkStrategy strategy) {
        updateTitleStack(titleStack, titleElement.getNormalizedText(),
                levelOf(context, titleElement.getElementId()), strategy);
        Section section = new Section(String.join(" > ", titleStack));
        context.setTitlePath(new ArrayList<>(titleStack));
        return section;
    }

    /**
     * 执行后置处理链：按章节分别处理子片，孤儿片单独处理（跨章节不生效）。
     *
     * @param sections 章节列表
     * @param orphans  无章节归属的切片
     * @param context  切片上下文
     */
    void postProcess(List<Section> sections, List<Chunk> orphans, SliceContext context) {
        for (Section section : sections) {
            applyPostProcessors(section.children, context);
        }
        applyPostProcessors(orphans, context);
    }

    /**
     * 组装父子层级与文档顺序：父片在前、子片随后、孤儿片最后。
     *
     * @param sections    章节列表
     * @param orphans     无章节归属的切片
     * @param stats       运行统计
     * @param parentChild 是否生成父片
     * @return 按文档顺序排列的切片
     */
    List<Chunk> assembleHierarchy(List<Section> sections, List<Chunk> orphans,
                                  ChunkMetadataSupport.Stats stats, boolean parentChild) {
        List<Chunk> ordered = new ArrayList<>();
        long parentStartedNanos = System.nanoTime();
        if (parentChild) {
            for (Section section : sections) {
                if (section.children.isEmpty()) {
                    continue;
                }
                Chunk parent = buildParentChunk(section);
                section.parentChunk = parent;
                ordered.add(parent);
                stats.parentMatched++;
                stats.parentLen += parent.getCharCount();
            }
        }
        stats.parentNanos = System.nanoTime() - parentStartedNanos;
        for (Section section : sections) {
            if (!section.children.isEmpty()) {
                ordered.addAll(section.children);
            }
        }
        ordered.addAll(orphans);
        return ordered;
    }

    /**
     * 父子关联：已生成父片的章节，其子片挂到父片 ID（编号完成后调用）。
     *
     * @param sections 章节列表
     */
    void linkParentIds(List<Section> sections) {
        for (Section section : sections) {
            if (NullUtil.isNull(section.parentChunk)) {
                continue;
            }
            for (Chunk child : section.children) {
                child.setParentChunkId(section.parentChunk.getChunkId());
            }
        }
    }

    /**
     * 归属切片：有当前章节进章节子片，否则进孤儿片。
     *
     * @param section 当前章节（可空）
     * @param orphans 孤儿片列表
     * @param chunks  切片列表
     */
    void attachToSection(Section section, List<Chunk> orphans, List<Chunk> chunks) {
        if (NullUtil.isNotNull(section)) {
            section.children.addAll(chunks);
        } else {
            orphans.addAll(chunks);
        }
    }

    private Integer levelOf(SliceContext ctx, String elementId) {
        UnifiedElement element = ctx.getById().get(elementId);
        return NullUtil.isNull(element) ? null : element.getLevel();
    }

    private void updateTitleStack(Deque<String> stack, String titleText, Integer level, ChunkStrategy strategy) {
        if (StrUtil.isBlank(titleText)) {
            return;
        }
        int targetLevel = NullUtil.isNull(level) ? 1 : Math.max(1, level);
        while (stack.size() >= targetLevel) {
            stack.pollLast();
        }
        stack.addLast(titleText);
        int maxLevel = strategy.pipelineInt(PipelineKey.TITLE_PATH_MAX_LEVEL, properties.getTitlePathMaxLevel());
        while (stack.size() > maxLevel) {
            stack.pollFirst();
        }
    }

    private Chunk buildParentChunk(Section section) {
        List<String> contents = section.children.stream().map(Chunk::getContent).toList();
        List<String> sourceIds = new ArrayList<>();
        Set<Integer> pages = new HashSet<>();
        for (Chunk child : section.children) {
            sourceIds.addAll(child.getSourceElementIds());
            if (NullUtil.isNotNull(child.getPageRange())) {
                pages.addAll(child.getPageRange());
            }
        }
        String content = String.join("\n", contents);
        Chunk parent = new Chunk();
        parent.setContent(content);
        parent.setContentType(ChunkContentType.SECTION.name());
        parent.setTitlePath(section.path);
        parent.setSourceElementIds(sourceIds);
        parent.setPageRange(pages.isEmpty() ? null : pages.stream().sorted().toList());
        parent.setCharCount(content.length());
        return parent;
    }

    private void applyPostProcessors(List<Chunk> chunks, SliceContext context) {
        for (ChunkPostProcessor processor : postProcessors) {
            processor.process(chunks, context);
        }
    }

    /** 章节容器：路径 + 子片 + 父片（编号完成后挂关联） */
    static final class Section {
        private final String path;
        private final List<Chunk> children = new ArrayList<>();
        private Chunk parentChunk;

        Section(String path) {
            this.path = path;
            this.parentChunk = null;
        }
    }
}
