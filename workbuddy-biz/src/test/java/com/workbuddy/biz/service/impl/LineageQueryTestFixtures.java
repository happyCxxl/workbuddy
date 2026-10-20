package com.workbuddy.biz.service.impl;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.QualityInfo;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.filecenter.storage.FileStorage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.mockito.Mockito.when;

/**
 * 执行树聚合测试共享夹具：任务/产物行、解析/组装/预处理/切片产物与对象内容打桩。
 */
final class LineageQueryTestFixtures {

    /** 被测文件结果 ID */
    static final Long FILE_RESULT_ID = 10L;

    /** 归属校验用的知识库 ID */
    static final Long KB_ID = 10L;

    /** 产物所在的数据源 */
    static final Long SOURCE_ID = 1L;

    private LineageQueryTestFixtures() {
    }

    /** 任务行夹具 */
    static WbPipelineTask task(Long id, String stage, Long upstreamProductId, Long productId, String snapshot) {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(id);
        task.setFileResultId(FILE_RESULT_ID);
        task.setStage(stage);
        task.setUpstreamProductId(upstreamProductId);
        task.setProductId(productId);
        task.setStrategySnapshot(snapshot);
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        return task;
    }

    /** 产物行夹具（读取按记录里的数据源与桶名定位） */
    static WbPipelineProduct product(Long id, String stage, String artifactId, String capability) {
        WbPipelineProduct product = new WbPipelineProduct();
        product.setId(id);
        product.setFileResultId(FILE_RESULT_ID);
        product.setStage(stage);
        product.setArtifactId(artifactId);
        product.setContentHash(artifactId);
        product.setCapabilitySnapshot(capability);
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        return product;
    }

    /** 产物里的一个元素（只有类型参与汇总） */
    static ParseElement element(ElementType type) {
        return ParseElement.of("e-" + type.name(), type);
    }

    /** 解析产物夹具（1 段落） */
    static ParseResult parseResultOfOneParagraph() {
        ParseSource source = new ParseSource();
        source.setElements(List.of(element(ElementType.PARAGRAPH)));
        ParseResult result = new ParseResult();
        result.setSources(List.of(source));
        result.setQuality(new QualityInfo());
        return result;
    }

    /** 统一文档夹具（1 元素） */
    static UnifiedDocument documentOfOneElement() {
        UnifiedElement element = new UnifiedElement();
        element.setId("e-1");
        element.setType("PARAGRAPH");
        element.setText("正文");
        UnifiedDocument document = new UnifiedDocument();
        document.setElements(List.of(element));
        return document;
    }

    /** 预处理视图夹具（1 保留 + 1 剔除） */
    static PreprocessView viewOfKeptAndExcluded() {
        ViewElement kept = new ViewElement();
        kept.setElementId("e-1");
        kept.setStatus(ViewElementStatus.NORMAL.name());
        ViewElement excluded = new ViewElement();
        excluded.setElementId("e-2");
        excluded.setStatus(ViewElementStatus.EXCLUDED_HEADER.name());
        PreprocessView view = new PreprocessView();
        view.setElements(List.of(kept, excluded));
        return view;
    }

    /** 切片集合夹具（1 片） */
    static ChunkSet chunkSetOfOneChunk() {
        Chunk chunk = new Chunk();
        chunk.setChunkId("c-1");
        chunk.setContentType("PARAGRAPH");
        chunk.setContent("正文");
        chunk.setCharCount(10);
        ChunkSet chunkSet = new ChunkSet();
        chunkSet.setChunks(List.of(chunk));
        return chunkSet;
    }

    /** 一次运行的文件结果（挂 10 号知识库，归属校验要能过） */
    static WbFileResult fileResultOfKb10() {
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(FILE_RESULT_ID);
        fileResult.setKnowledgeBaseId(KB_ID);
        return fileResult;
    }

    /** 归属校验用的知识库行 */
    static WbKnowledgeBase knowledgeBaseOf(Long id) {
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(id);
        return kb;
    }

    /** 产物本体夹具：按产物对象位置写 JSON 字节 */
    static void stubBody(FileStorage fileStorage, String artifactId, Object body) {
        ObjectRef ref = ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", artifactId);
        when(fileStorage.getObject(ref))
                .thenReturn(Objects.requireNonNull(JsonUtil.toJsonStr(body)).getBytes(StandardCharsets.UTF_8));
    }
}
