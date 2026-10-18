package com.workbuddy.worker.chunking.impl.body;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.chunk.ChunkRoute;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.strategy.ChunkRouteConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 正文切片公共工具：切片构建 / 页码合并 / 缓冲长度 / 句边界切分 / 缓冲结算（各正文策略与兜底共用口径）。
 */
public final class BodyChunkSupport {

    /** 句边界分隔符（保留分隔符的 lookbehind 切分） */
    public static final String SENTENCE_BOUNDARIES = "[。！？；\\n]";

    /** 降级原因：整段超过软上限（段落/结构混合/句子聚合共用） */
    public static final String REASON_PARAGRAPH_TOO_LONG = "超长段落递归降级";

    /** 降级原因：一节文本超过片长上限（标题边界） */
    public static final String REASON_TITLE_BOUNDARY_TOO_LONG = "标题边界超长降级";

    private BodyChunkSupport() {
    }

    /** 正文缓冲结算结果（归一文本/来源元素 ID/页码范围）。 */
    public record BodyBuffer(String text, List<String> elementIds, List<Integer> pages) {
    }

    /** 构建切片（content/type/titlePath/来源/页码/字符数统一口径）。 */
    public static Chunk buildChunk(String contentType, String content, SliceContext context,
                                   List<String> sourceIds, List<Integer> pages) {
        Chunk chunk = new Chunk();
        chunk.setContent(content);
        chunk.setContentType(contentType);
        chunk.setTitlePath(String.join(" > ", context.getTitlePath()));
        chunk.setSourceElementIds(new ArrayList<>(sourceIds));
        chunk.setPageRange(pages);
        chunk.setCharCount(content.length());
        return chunk;
    }

    /** 单元素页码（无页 → null）。 */
    public static List<Integer> pageRangeOf(ViewElement element) {
        return NullUtil.isNotNull(element.getPage()) ? List.of(element.getPage()) : null;
    }

    /** 合并组内元素页码（去重升序；无页 → null）。 */
    public static List<Integer> mergePages(List<ViewElement> group) {
        Set<Integer> pages = new HashSet<>();
        for (ViewElement element : group) {
            if (NullUtil.isNotNull(element.getPage())) {
                pages.add(element.getPage());
            }
        }
        if (pages.isEmpty()) {
            return null;
        }
        return pages.stream().sorted().toList();
    }

    /** 缓冲结算：归一文本拼接 + 来源元素 ID 去重保序 + 页码合并 + 清空缓冲（每次运行独立）。 */
    public static BodyBuffer settleBuffer(SliceContext context) {
        List<ViewElement> buffer = context.getBodyBuffer();
        String text = String.join("\n", buffer.stream().map(ViewElement::getNormalizedText)
                .filter(StrUtil::isNotBlank).toList());
        Set<String> ids = new LinkedHashSet<>();
        for (ViewElement element : buffer) {
            ids.add(element.getElementId());
        }
        List<Integer> pages = mergePages(buffer);
        buffer.clear();
        return new BodyBuffer(text, new ArrayList<>(ids), pages);
    }

    /** 缓冲文本总长（空白文本不计）。 */
    public static int bufferLength(List<ViewElement> buffer) {
        int length = 0;
        for (ViewElement element : buffer) {
            if (StrUtil.isNotBlank(element.getNormalizedText())) {
                length += element.getNormalizedText().length();
            }
        }
        return length;
    }

    /** 按句边界切分（保留分隔符，空白句过滤）。 */
    public static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        for (String part : text.split("(?<=" + SENTENCE_BOUNDARIES + ")")) {
            if (StrUtil.isNotBlank(part)) {
                sentences.add(part);
            }
        }
        return sentences;
    }

    /**
     * 超长降级（正文各策略共用唯一入口）：按策略所选兜底切片器切片，逐片标 {@code contentType=FALLBACK}
     * 与降级原因。调用前须先结算既有缓冲（降级片不与聚合片混作一组）。
     *
     * @param text      超长文本（整元素或一节文本）
     * @param sourceIds 来源元素 ID（一节文本为组内元素并集）
     * @param pages     页码范围（可为 null）
     * @param context   切片上下文（含策略路由与兜底切片器）
     * @param reason    降级原因（写入 fallbackReason）
     */
    public static List<Chunk> fallbackChunks(String text, List<String> sourceIds, List<Integer> pages,
                                             SliceContext context, String reason) {
        ChunkRouteConfig fallbackConfig = context.getStrategy().route(ChunkRoute.FALLBACK);
        List<Chunk> chunks = new ArrayList<>();
        for (String piece : context.getFallback().slice(text, fallbackConfig)) {
            Chunk chunk = buildChunk(ChunkContentType.FALLBACK.name(), piece, context, sourceIds, pages);
            chunk.setFallbackReason(reason);
            chunks.add(chunk);
        }
        return chunks;
    }
}
