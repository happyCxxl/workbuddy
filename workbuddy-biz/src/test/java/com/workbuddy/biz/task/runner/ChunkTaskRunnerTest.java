package com.workbuddy.biz.task.runner;

import com.workbuddy.biz.service.db.KbChunkDbService;
import com.workbuddy.biz.service.db.KbChunkSetDbService;
import com.workbuddy.biz.service.db.KbFileResultDbService;
import com.workbuddy.biz.service.db.KbPipelineProductDbService;
import com.workbuddy.biz.service.db.KbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.KbPipelineTaskDbService;
import com.workbuddy.biz.service.db.KbSourceFileDbService;
import com.workbuddy.biz.service.support.ChainStorageSupport;
import com.workbuddy.biz.task.runner.persistence.ProductPersistence;
import com.workbuddy.biz.task.runner.persistence.StepLogPersistence;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkOutcome;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.entity.KbChunk;
import com.workbuddy.common.domain.entity.KbChunkSet;
import com.workbuddy.common.domain.entity.KbFileResult;
import com.workbuddy.common.domain.entity.KbPipelineProduct;
import com.workbuddy.common.domain.entity.KbPipelineTask;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.structure.DocumentInfo;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.task.StepLogInfo;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.service.FileStorage;
import com.workbuddy.worker.chunking.ChunkContext;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.ChunkerPort;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 切片任务执行器单测：成功落库链（ChunkSet 产物 + 两表 + 6 步日志）/ 上游缺失 / 管线失败 / 结构参照。
 *
 * @author cxxl
 */
@ExtendWith(MockitoExtension.class)
class ChunkTaskRunnerTest {

    /** 档案与产物所在的数据源 */
    private static final Long SOURCE_ID = 1L;

    /** 上游产物的产出任务 ID（门禁按它的状态判定） */
    private static final Long UPSTREAM_TASK_ID = 40L;

    @Mock
    private KbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private KbFileResultDbService fileResultDbService;
    @Mock
    private KbSourceFileDbService sourceFileDbService;
    @Mock
    private KbPipelineProductDbService pipelineProductDbService;
    @Mock
    private KbPipelineStepLogDbService stepLogDbService;
    @Mock
    private KbChunkSetDbService chunkSetDbService;
    @Mock
    private KbChunkDbService chunkDbService;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private StorageRouter storageRouter;
    @Mock
    private ChunkerPort chunker;

    private ChunkTaskRunner runner;

    @BeforeEach
    void setUp() {
        // 存储守卫用真实实例 + mock 依赖：链类型取不到（默认 mock 返回 null）时不拦
        ChainStorageSupport chainStorage = new ChainStorageSupport(fileResultDbService, sourceFileDbService,
                fileStorage, storageRouter);
        runner = new ChunkTaskRunner(pipelineTaskDbService, fileResultDbService,
                pipelineProductDbService, chunkSetDbService, chunkDbService,
                fileStorage, chunker, new ChunkProperties(),
                new ChunkStrategyParser(new ChunkProperties()),
                new StepLogPersistence(stepLogDbService),
                new ProductPersistence(pipelineProductDbService, pipelineTaskDbService, chainStorage, fileStorage),
                new UpstreamProductGate(pipelineTaskDbService));
        // 上游产物的产出任务默认成功：门禁放行；不放行的状态由各用例自行改写（宽松桩）
        lenient().when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID)).thenReturn(upstreamTask(PipelineTaskStatus.SUCCESS));
    }

    /** 上游产物的产出任务（环节 PREPROCESS） */
    private KbPipelineTask upstreamTask(PipelineTaskStatus status) {
        KbPipelineTask task = new KbPipelineTask();
        task.setId(UPSTREAM_TASK_ID);
        task.setFileResultId(10L);
        task.setStage(PipelineStage.PREPROCESS.name());
        task.setStatus(status.name());
        return task;
    }

    private KbPipelineTask queuedTask() {
        KbPipelineTask task = new KbPipelineTask();
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

    private KbPipelineProduct preprocessProduct() {
        KbPipelineProduct product = new KbPipelineProduct();
        product.setId(50L);
        product.setTaskId(UPSTREAM_TASK_ID);
        product.setUpstreamProductId(30L);
        product.setArtifactId("abc".repeat(22));
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        return product;
    }

    private PreprocessView view() {
        PreprocessView view = new PreprocessView();
        view.setDocumentId("doc-10");
        return view;
    }

    private byte[] json(Object value) {
        return Objects.requireNonNull(JsonUtil.toJsonStr(value)).getBytes(StandardCharsets.UTF_8);
    }

    private ChunkOutcome successOutcome() {
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

    private KbFileResult fileResult() {
        KbFileResult fileResult = new KbFileResult();
        fileResult.setId(10L);
        fileResult.setSourceFileId(5L);
        return fileResult;
    }

    @Test
    void successPathShouldPersistChunkSetProductAndRows() {
        when(pipelineTaskDbService.getById(70L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L))
                .thenReturn(preprocessProduct());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(json(view()));
        when(chunker.chunk(any(ChunkContext.class))).thenReturn(successOutcome());
        when(fileStorage.putObject(any(byte[].class)))
                .thenReturn(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "def".repeat(21) + "0"));
        when(pipelineProductDbService.save(any(KbPipelineProduct.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbPipelineProduct.class).setId(80L);
            return true;
        });
        when(chunkSetDbService.save(any(KbChunkSet.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbChunkSet.class).setId(90L);
            return true;
        });

        runner.run(70L);

        ArgumentCaptor<ChunkContext> contextCaptor = ArgumentCaptor.forClass(ChunkContext.class);
        verify(chunker).chunk(contextCaptor.capture());
        assertEquals("doc-10", contextCaptor.getValue().getView().getDocumentId());
        assertNotNull(contextCaptor.getValue().getStrategy());

        ArgumentCaptor<KbPipelineProduct> productCaptor = ArgumentCaptor.forClass(KbPipelineProduct.class);
        verify(pipelineProductDbService).save(productCaptor.capture());
        assertEquals(PipelineStage.CHUNK.name(), productCaptor.getValue().getStage());
        assertEquals(50L, productCaptor.getValue().getUpstreamProductId());

        ArgumentCaptor<KbChunkSet> chunkSetCaptor = ArgumentCaptor.forClass(KbChunkSet.class);
        verify(chunkSetDbService).save(chunkSetCaptor.capture());
        assertEquals(2, chunkSetCaptor.getValue().getChunkCount());
        assertEquals("chunk-hybrid-v1", chunkSetCaptor.getValue().getChunkStrategyVersion());
        // 切片集合行沿用产物行的对象位置（含数据源 ID）
        assertEquals(SOURCE_ID, chunkSetCaptor.getValue().getStorageSourceId());

        @SuppressWarnings({ "unchecked", "rawtypes" })
        ArgumentCaptor<List<KbChunk>> chunkRowsCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(chunkDbService).saveBatch(chunkRowsCaptor.capture(), eq(500));
        assertEquals(2, chunkRowsCaptor.getValue().size());
        assertEquals("chunk-0002", chunkRowsCaptor.getValue().get(1).getChunkId());
        assertEquals("chunk-0001", chunkRowsCaptor.getValue().get(1).getParentChunkId());
        assertEquals(90L, chunkRowsCaptor.getValue().getFirst().getChunkSetId());

        verify(pipelineTaskDbService).finish(70L, PipelineTaskStatus.SUCCESS, null, null);
        verify(pipelineTaskDbService).updateProductId(70L, 80L);
        verify(stepLogDbService, times(6)).save(any());
    }

    @Test
    void specifiedUpstreamProductShouldBePreferred() {
        KbPipelineTask task = queuedTask();
        task.setUpstreamProductId(88L);
        when(pipelineTaskDbService.getById(70L)).thenReturn(task);
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        KbPipelineProduct specified = preprocessProduct();
        specified.setId(88L);
        specified.setArtifactId("spec".repeat(16));
        // 该用例只验证"优先取指定上游产物"，不涉结构参照：置空避免 readStructureDocument 内部产生严格桩参数不匹配告警
        specified.setUpstreamProductId(null);
        when(pipelineProductDbService.getById(88L)).thenReturn(specified);
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "spec".repeat(16))))
                .thenReturn(json(view()));
        when(chunker.chunk(any(ChunkContext.class))).thenReturn(successOutcome());
        when(fileStorage.putObject(any(byte[].class)))
                .thenReturn(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "out".repeat(16)));
        when(pipelineProductDbService.save(any(KbPipelineProduct.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbPipelineProduct.class).setId(80L);
            return true;
        });
        when(chunkSetDbService.save(any(KbChunkSet.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbChunkSet.class).setId(90L);
            return true;
        });

        runner.run(70L);

        verify(fileStorage).getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "spec".repeat(16)));
        verify(pipelineProductDbService, never()).getByFileResultIdAndStage(any(), any());
        ArgumentCaptor<KbPipelineProduct> productCaptor = ArgumentCaptor.forClass(KbPipelineProduct.class);
        verify(pipelineProductDbService).save(productCaptor.capture());
        assertEquals(88L, productCaptor.getValue().getUpstreamProductId());
    }

    @Test
    void missingUpstreamProductShouldFailTaskUpstreamMissing() {
        when(pipelineTaskDbService.getById(70L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());

        runner.run(70L);

        verify(pipelineTaskDbService).finish(eq(70L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.TASK_UPSTREAM_MISSING.name()), any());
        verify(fileStorage, never()).putObject(any(byte[].class));
    }

    @Test
    void failedUpstreamTaskShouldFailChunkFailed() {
        when(pipelineTaskDbService.getById(70L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(preprocessProduct());
        // 上游产物所属任务已失败，产物本体却已落盘：门禁按任务状态拒绝继续
        when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID)).thenReturn(upstreamTask(PipelineTaskStatus.FAILED));

        runner.run(70L);

        ArgumentCaptor<String> errorMsg = ArgumentCaptor.forClass(String.class);
        verify(pipelineTaskDbService).finish(eq(70L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.CHUNK_FAILED.name()), errorMsg.capture());
        assertTrue(errorMsg.getValue().contains(PipelineTaskStatus.FAILED.name()), errorMsg.getValue());
        // 门禁在读取产物本体之前：不读对象、不进切片管线
        verify(fileStorage, never()).getObject(any(ObjectRef.class));
        verify(chunker, never()).chunk(any());
    }

    @Test
    void corruptStrategySnapshotShouldFailChunkFailed() {
        // 快照坏（"{oops"）：解析抛异常被显式接住，任务落 CHUNK_FAILED 且文案干净可辨识
        KbPipelineTask task = queuedTask();
        task.setStrategySnapshot("{oops");
        when(pipelineTaskDbService.getById(70L)).thenReturn(task);
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(preprocessProduct());

        runner.run(70L);

        verify(pipelineTaskDbService).finish(eq(70L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.CHUNK_FAILED.name()),
                eq("任务未带切片策略快照或快照已损坏，无法切片"));
        // 守卫在读取上游产物本体之前：坏快照不读对象、不进管线
        verify(fileStorage, never()).getObject(any(ObjectRef.class));
        verify(chunker, never()).chunk(any());
    }

    @Test
    void missingStrategySnapshotShouldFailChunkFailed() {
        // 空快照与坏快照同一口径：解析即抛异常，不再回退代码里的默认策略
        KbPipelineTask task = queuedTask();
        task.setStrategySnapshot(null);
        when(pipelineTaskDbService.getById(70L)).thenReturn(task);
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(preprocessProduct());

        runner.run(70L);

        verify(pipelineTaskDbService).finish(eq(70L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.CHUNK_FAILED.name()),
                eq("任务未带切片策略快照或快照已损坏，无法切片"));
        verify(chunker, never()).chunk(any());
    }

    @Test
    void failedOutcomeShouldFinishFailedWithoutProduct() {
        when(pipelineTaskDbService.getById(70L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L))
                .thenReturn(preprocessProduct());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(json(view()));
        ChunkOutcome outcome = new ChunkOutcome();
        outcome.setSuggestedStatus(PipelineTaskStatus.FAILED);
        outcome.setErrorCode(PipelineTaskErrorCode.CHUNK_EMPTY.name());
        outcome.setErrorMsg("无可切内容（元素均被剔除或无文本）");
        when(chunker.chunk(any(ChunkContext.class))).thenReturn(outcome);

        runner.run(70L);

        verify(pipelineTaskDbService).finish(eq(70L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.CHUNK_EMPTY.name()), any());
        verify(fileStorage, never()).putObject(any(byte[].class));
        // IService 的 save/saveBatch 为默认方法，never+匹配器会触发 InvalidUseOfMatchers，用无交互断言
        verifyNoInteractions(chunkSetDbService, chunkDbService);
    }

    @Test
    void missingStructureReferenceShouldNotBlock() {
        when(pipelineTaskDbService.getById(70L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        KbPipelineProduct preprocess = preprocessProduct();
        when(pipelineProductDbService.getById(50L))
                .thenReturn(preprocess);
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(json(view()));
        // 上游 STRUCTURE 产物缺失 → 结构参照为 null，不阻断
        when(pipelineProductDbService.getById(30L)).thenReturn(null);
        when(chunker.chunk(any(ChunkContext.class))).thenReturn(successOutcome());
        when(fileStorage.putObject(any(byte[].class)))
                .thenReturn(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "def".repeat(21) + "0"));
        when(pipelineProductDbService.save(any(KbPipelineProduct.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbPipelineProduct.class).setId(80L);
            return true;
        });
        when(chunkSetDbService.save(any(KbChunkSet.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbChunkSet.class).setId(90L);
            return true;
        });

        runner.run(70L);

        ArgumentCaptor<ChunkContext> contextCaptor = ArgumentCaptor.forClass(ChunkContext.class);
        verify(chunker).chunk(contextCaptor.capture());
        assertNull(contextCaptor.getValue().getDocument());
        verify(pipelineTaskDbService).finish(70L, PipelineTaskStatus.SUCCESS, null, null);
    }

    @Test
    void structureReferenceShouldBeReadFromUpstreamProduct() {
        when(pipelineTaskDbService.getById(70L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L))
                .thenReturn(preprocessProduct());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(json(view()));
        KbPipelineProduct structureProduct = new KbPipelineProduct();
        structureProduct.setId(30L);
        structureProduct.setArtifactId("ghi".repeat(21) + "0");
        structureProduct.setStorageType(StorageType.MINIO.getCode());
        structureProduct.setStorageSourceId(SOURCE_ID);
        structureProduct.setBucket("artifacts");
        when(pipelineProductDbService.getById(30L)).thenReturn(structureProduct);
        UnifiedDocument document = new UnifiedDocument();
        DocumentInfo info = new DocumentInfo();
        info.setDocumentId("doc-10");
        document.setDocumentInfo(info);
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "ghi".repeat(21) + "0")))
                .thenReturn(json(document));
        when(chunker.chunk(any(ChunkContext.class))).thenReturn(successOutcome());
        when(fileStorage.putObject(any(byte[].class)))
                .thenReturn(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "def".repeat(21) + "0"));
        when(pipelineProductDbService.save(any(KbPipelineProduct.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbPipelineProduct.class).setId(80L);
            return true;
        });
        when(chunkSetDbService.save(any(KbChunkSet.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbChunkSet.class).setId(90L);
            return true;
        });

        runner.run(70L);

        ArgumentCaptor<ChunkContext> contextCaptor = ArgumentCaptor.forClass(ChunkContext.class);
        verify(chunker).chunk(contextCaptor.capture());
        assertEquals("doc-10", contextCaptor.getValue().getDocument().getDocumentInfo().getDocumentId());
    }

    // ---------------- 页码范围格式化（无损压缩连续段，绝不截断） ----------------

    @Test
    void formatPageRangeShouldKeepSingleAndContiguous() {
        assertNull(ChunkTaskRunner.formatPageRange(null));
        assertNull(ChunkTaskRunner.formatPageRange(List.of()));
        assertEquals("3", ChunkTaskRunner.formatPageRange(List.of(3)));
        assertEquals("1-3", ChunkTaskRunner.formatPageRange(List.of(1, 2, 3)));
    }

    @Test
    void formatPageRangeShouldCompressContiguousRuns() {
        // 无序+重复输入：先去重排序再压缩
        assertEquals("1-3,5-6,9", ChunkTaskRunner.formatPageRange(List.of(9, 2, 1, 3, 5, 6, 3)));
        // 全单页（无连续段）：保持逐页枚举
        assertEquals("1,3,5", ChunkTaskRunner.formatPageRange(List.of(1, 3, 5)));
    }

    @Test
    void formatPageRangeShouldNotTruncateLongList() {
        // 100 个交替页（1,3,...,199）：压缩后仍全量保留（回归：旧列宽 32 放不下也不截断）
        List<Integer> pages = new ArrayList<>();
        for (int p = 1; p <= 199; p += 2) {
            pages.add(p);
        }
        String formatted = Objects.requireNonNull(ChunkTaskRunner.formatPageRange(pages));
        assertEquals(100, formatted.split(",").length);
        assertTrue(formatted.startsWith("1,"));
        assertTrue(formatted.endsWith(",199"));
        assertTrue(formatted.length() > 32);
    }
}
