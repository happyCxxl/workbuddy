package com.workbuddy.biz.task.runner;

import com.workbuddy.biz.service.db.WbChunkDbService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.common.domain.entity.WbChunk;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.structure.DocumentInfo;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.chunking.ChunkContext;
import com.workbuddy.worker.chunking.ChunkerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.workbuddy.biz.task.runner.ChunkTaskRunnerFixtures.SOURCE_ID;
import static com.workbuddy.biz.task.runner.ChunkTaskRunnerFixtures.UPSTREAM_TASK_ID;
import static com.workbuddy.biz.task.runner.ChunkTaskRunnerFixtures.fileResult;
import static com.workbuddy.biz.task.runner.ChunkTaskRunnerFixtures.json;
import static com.workbuddy.biz.task.runner.ChunkTaskRunnerFixtures.preprocessProduct;
import static com.workbuddy.biz.task.runner.ChunkTaskRunnerFixtures.queuedTask;
import static com.workbuddy.biz.task.runner.ChunkTaskRunnerFixtures.runner;
import static com.workbuddy.biz.task.runner.ChunkTaskRunnerFixtures.successOutcome;
import static com.workbuddy.biz.task.runner.ChunkTaskRunnerFixtures.upstreamTask;
import static com.workbuddy.biz.task.runner.ChunkTaskRunnerFixtures.view;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 切片任务执行器成功路径单测：成功落库链（ChunkSet 产物 + 两表 + 6 步日志）/ 指定上游 /
 * 结构参照读取与缺失降级。
 */
@ExtendWith(MockitoExtension.class)
class ChunkTaskRunnerSuccessTest {

    @Mock
    private WbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private WbFileResultDbService fileResultDbService;
    @Mock
    private WbSourceFileDbService sourceFileDbService;
    @Mock
    private WbPipelineProductDbService pipelineProductDbService;
    @Mock
    private WbPipelineStepLogDbService stepLogDbService;
    @Mock
    private WbChunkSetDbService chunkSetDbService;
    @Mock
    private WbChunkDbService chunkDbService;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private StorageRouter storageRouter;
    @Mock
    private ChunkerPort chunker;

    private ChunkTaskRunner runner;

    @BeforeEach
    void setUp() {
        runner = runner(pipelineTaskDbService, fileResultDbService, sourceFileDbService, pipelineProductDbService,
                stepLogDbService, chunkSetDbService, chunkDbService, fileStorage, storageRouter, chunker);
        // 上游产物的产出任务默认成功：门禁放行；不放行的状态由各用例自行改写（宽松桩）
        lenient().when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID))
                .thenReturn(upstreamTask(PipelineTaskStatus.SUCCESS));
    }

    /**
     * 守护 {@code successPathShouldPersistChunkSetProductAndRows}：该场景行为改动即失败。
     */
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
        when(pipelineProductDbService.save(any(WbPipelineProduct.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineProduct.class).setId(80L);
            return true;
        });
        when(chunkSetDbService.save(any(WbChunkSet.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbChunkSet.class).setId(90L);
            return true;
        });

        runner.run(70L);

        ArgumentCaptor<ChunkContext> contextCaptor = ArgumentCaptor.forClass(ChunkContext.class);
        verify(chunker).chunk(contextCaptor.capture());
        assertEquals("doc-10", contextCaptor.getValue().getView().getDocumentId());
        assertNotNull(contextCaptor.getValue().getStrategy());

        ArgumentCaptor<WbPipelineProduct> productCaptor = ArgumentCaptor.forClass(WbPipelineProduct.class);
        verify(pipelineProductDbService).save(productCaptor.capture());
        assertEquals(PipelineStage.CHUNK.name(), productCaptor.getValue().getStage());
        assertEquals(50L, productCaptor.getValue().getUpstreamProductId());

        ArgumentCaptor<WbChunkSet> chunkSetCaptor = ArgumentCaptor.forClass(WbChunkSet.class);
        verify(chunkSetDbService).save(chunkSetCaptor.capture());
        assertEquals(2, chunkSetCaptor.getValue().getChunkCount());
        assertEquals("chunk-hybrid-v1", chunkSetCaptor.getValue().getChunkStrategyVersion());
        // 切片集合行沿用产物行的对象位置（含数据源 ID）
        assertEquals(SOURCE_ID, chunkSetCaptor.getValue().getStorageSourceId());

        @SuppressWarnings({ "unchecked", "rawtypes" })
        ArgumentCaptor<List<WbChunk>> chunkRowsCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(chunkDbService).saveBatch(chunkRowsCaptor.capture(), eq(500));
        assertEquals(2, chunkRowsCaptor.getValue().size());
        assertEquals("chunk-0002", chunkRowsCaptor.getValue().get(1).getChunkId());
        assertEquals("chunk-0001", chunkRowsCaptor.getValue().get(1).getParentChunkId());
        assertEquals(90L, chunkRowsCaptor.getValue().getFirst().getChunkSetId());

        verify(pipelineTaskDbService).finish(70L, PipelineTaskStatus.SUCCESS, null, null);
        verify(pipelineTaskDbService).updateProductId(70L, 80L);
        verify(stepLogDbService, times(6)).save(any());
    }

    /**
     * 守护 {@code specifiedUpstreamProductShouldBePreferred}：该场景行为改动即失败。
     */
    @Test
    void specifiedUpstreamProductShouldBePreferred() {
        WbPipelineTask task = queuedTask();
        task.setUpstreamProductId(88L);
        when(pipelineTaskDbService.getById(70L)).thenReturn(task);
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineProduct specified = preprocessProduct();
        specified.setId(88L);
        specified.setArtifactId("spec".repeat(16));
        // 该用例只验证"优先取指定上游产物"，不涉结构参照：置空避免 readStructureDocument 内部产生严格桩参数不匹配告警
        specified.setUpstreamProductId(null);
        when(pipelineProductDbService.getById(88L)).thenReturn(specified);
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(),
                "artifacts", "spec".repeat(16)))).thenReturn(json(view()));
        when(chunker.chunk(any(ChunkContext.class))).thenReturn(successOutcome());
        when(fileStorage.putObject(any(byte[].class)))
                .thenReturn(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "out".repeat(16)));
        when(pipelineProductDbService.save(any(WbPipelineProduct.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineProduct.class).setId(80L);
            return true;
        });
        when(chunkSetDbService.save(any(WbChunkSet.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbChunkSet.class).setId(90L);
            return true;
        });

        runner.run(70L);

        verify(fileStorage).getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(),
                "artifacts", "spec".repeat(16)));
        verify(pipelineProductDbService, never()).getByFileResultIdAndStage(any(), any());
        ArgumentCaptor<WbPipelineProduct> productCaptor = ArgumentCaptor.forClass(WbPipelineProduct.class);
        verify(pipelineProductDbService).save(productCaptor.capture());
        assertEquals(88L, productCaptor.getValue().getUpstreamProductId());
    }

    /**
     * 守护 {@code missingStructureReferenceShouldNotBlock}：该场景行为改动即失败。
     */
    @Test
    void missingStructureReferenceShouldNotBlock() {
        when(pipelineTaskDbService.getById(70L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(preprocessProduct());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(json(view()));
        // 上游 STRUCTURE 产物缺失 → 结构参照为 null，不阻断
        when(pipelineProductDbService.getById(30L)).thenReturn(null);
        when(chunker.chunk(any(ChunkContext.class))).thenReturn(successOutcome());
        when(fileStorage.putObject(any(byte[].class)))
                .thenReturn(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "def".repeat(21) + "0"));
        when(pipelineProductDbService.save(any(WbPipelineProduct.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineProduct.class).setId(80L);
            return true;
        });
        when(chunkSetDbService.save(any(WbChunkSet.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbChunkSet.class).setId(90L);
            return true;
        });

        runner.run(70L);

        ArgumentCaptor<ChunkContext> contextCaptor = ArgumentCaptor.forClass(ChunkContext.class);
        verify(chunker).chunk(contextCaptor.capture());
        assertNull(contextCaptor.getValue().getDocument());
        verify(pipelineTaskDbService).finish(70L, PipelineTaskStatus.SUCCESS, null, null);
    }

    /**
     * 守护 {@code structureReferenceShouldBeReadFromUpstreamProduct}：该场景行为改动即失败。
     */
    @Test
    void structureReferenceShouldBeReadFromUpstreamProduct() {
        when(pipelineTaskDbService.getById(70L)).thenReturn(queuedTask());
        when(pipelineTaskDbService.claim(70L)).thenReturn(1);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(preprocessProduct());
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22))))
                .thenReturn(json(view()));
        WbPipelineProduct structureProduct = new WbPipelineProduct();
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
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(),
                "artifacts", "ghi".repeat(21) + "0"))).thenReturn(json(document));
        when(chunker.chunk(any(ChunkContext.class))).thenReturn(successOutcome());
        when(fileStorage.putObject(any(byte[].class)))
                .thenReturn(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "def".repeat(21) + "0"));
        when(pipelineProductDbService.save(any(WbPipelineProduct.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineProduct.class).setId(80L);
            return true;
        });
        when(chunkSetDbService.save(any(WbChunkSet.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbChunkSet.class).setId(90L);
            return true;
        });

        runner.run(70L);

        ArgumentCaptor<ChunkContext> contextCaptor = ArgumentCaptor.forClass(ChunkContext.class);
        verify(chunker).chunk(contextCaptor.capture());
        assertEquals("doc-10", contextCaptor.getValue().getDocument().getDocumentInfo().getDocumentId());
    }
}
