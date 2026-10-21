package com.workbuddy.biz.task.runner;

import com.workbuddy.biz.service.db.WbChunkDbService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.support.access.ChainStorageSupport;
import com.workbuddy.biz.task.runner.persistence.ProductPersistence;
import com.workbuddy.biz.task.runner.persistence.StepLogPersistence;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkOutcome;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.task.StepLogInfo;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.chunking.ChunkerPort;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import com.workbuddy.common.convert.KnowledgeConverterImpl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.workbuddy.biz.config.ConfigFixtures;

/** 切片任务执行器测试夹具：构造执行器与任务/产物/输出样例。 */
final class ChunkTaskRunnerFixtures {

    /** 档案与产物所在的数据源 */
    static final Long SOURCE_ID = 1L;

    /** 上游产物的产出任务 ID（门禁按它的状态判定） */
    static final Long UPSTREAM_TASK_ID = 40L;

    private ChunkTaskRunnerFixtures() {
    }

    static ChunkTaskRunner runner(WbPipelineTaskDbService pipelineTaskDbService,
                                  WbFileResultDbService fileResultDbService,
                                  WbSourceFileDbService sourceFileDbService,
                                  WbPipelineProductDbService pipelineProductDbService,
                                  WbPipelineStepLogDbService stepLogDbService,
                                  WbChunkSetDbService chunkSetDbService,
                                  WbChunkDbService chunkDbService,
                                  FileStorage fileStorage,
                                  StorageRouter storageRouter,
                                  ChunkerPort chunker) {
        // 存储守卫用真实实例 + mock 依赖：链类型取不到（默认 mock 返回 null）时不拦
        ChainStorageSupport chainStorage = new ChainStorageSupport(fileResultDbService, sourceFileDbService,
                fileStorage, storageRouter);
        return new ChunkTaskRunner(pipelineTaskDbService, fileResultDbService,
                pipelineProductDbService, chunkSetDbService, chunkDbService,
                fileStorage, chunker, ConfigFixtures.chunk(),
                new ChunkStrategyParser(ConfigFixtures.chunk()),
                new StepLogPersistence(stepLogDbService, new KnowledgeConverterImpl()),
                new ProductPersistence(pipelineProductDbService, pipelineTaskDbService, chainStorage, fileStorage
                        , new KnowledgeConverterImpl()),
                new UpstreamProductGate(pipelineTaskDbService), new KnowledgeConverterImpl());
    }

    static WbPipelineTask upstreamTask(PipelineTaskStatus status) {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(UPSTREAM_TASK_ID);
        task.setFileResultId(10L);
        task.setStage(PipelineStage.PREPROCESS.name());
        task.setStatus(status.name());
        return task;
    }

    static WbPipelineTask queuedTask() {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(70L);
        task.setFileResultId(10L);
        // 上游产物按任务行上的显式引用取（触发时确定）；指定别的上游场景由专门用例自行覆盖
        task.setUpstreamProductId(50L);
        // 策略快照按任务行上的快照取（触发时固定）：触发时写入的是带 type/name/version 的完整快照
        task.setStrategySnapshot("{\"type\":\"CHUNK\",\"name\":\"chunk-hybrid\",\"version\":\"v1\"}");
        task.setStage(PipelineStage.CHUNK.name());
        task.setStatus(PipelineTaskStatus.QUEUED.name());
        return task;
    }

    static WbPipelineProduct preprocessProduct() {
        WbPipelineProduct product = new WbPipelineProduct();
        product.setId(50L);
        product.setTaskId(UPSTREAM_TASK_ID);
        product.setUpstreamProductId(30L);
        product.setArtifactId("abc".repeat(22));
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        return product;
    }

    static PreprocessView view() {
        PreprocessView view = new PreprocessView();
        view.setDocumentId("doc-10");
        return view;
    }

    static byte[] json(Object value) {
        return Objects.requireNonNull(JsonUtil.toJsonStr(value)).getBytes(StandardCharsets.UTF_8);
    }

    static ChunkOutcome successOutcome() {
        ChunkOutcome outcome = new ChunkOutcome();
        outcome.setSuggestedStatus(PipelineTaskStatus.SUCCESS);
        ChunkSet chunkSet = new ChunkSet();
        chunkSet.setChunkSetId("cs-doc-10-chunk-hybrid-v1");
        chunkSet.setChunkCount(2);
        Chunk parent = new Chunk();
        parent.setChunkId("chunk-0001");
        parent.setContent("第一章 总则");
        parent.setContentType(ChunkContentType.SECTION.name());
        parent.setTitlePath("第一章 总则");
        parent.setSourceElementIds(List.of("n-1"));
        parent.setPageRange(List.of(1));
        parent.setOrder(1);
        parent.setCharCount(6);
        parent.setTokenCount(4);
        Chunk child = new Chunk();
        child.setChunkId("chunk-0002");
        child.setParentChunkId("chunk-0001");
        child.setContent("正文内容");
        child.setContentType(ChunkContentType.PARAGRAPH.name());
        child.setTitlePath("第一章 总则");
        child.setSourceElementIds(List.of("n-1"));
        child.setPageRange(List.of(1));
        child.setOrder(2);
        child.setCharCount(4);
        child.setTokenCount(3);
        chunkSet.setChunks(List.of(parent, child));
        outcome.setChunkSet(chunkSet);
        List<StepLogInfo> steps = new ArrayList<>();
        for (String name : List.of("内容路由", "正文切片", "表格切片", "图片切片", "兜底切片", "父子关系补充")) {
            StepLogInfo step = new StepLogInfo();
            step.setStepName(name);
            step.setStatus("SUCCESS");
            step.setMatchedCount(1);
            step.setAvgLen(5);
            steps.add(step);
        }
        outcome.setStepLogs(steps);
        return outcome;
    }

    static WbFileResult fileResult() {
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        fileResult.setSourceFileId(5L);
        return fileResult;
    }
}
