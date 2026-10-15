package com.workbuddy.biz.task;

import com.workbuddy.biz.service.db.KbFileResultDbService;
import com.workbuddy.biz.service.db.KbPipelineProductDbService;
import com.workbuddy.biz.service.db.KbPipelineTaskDbService;
import com.workbuddy.biz.service.db.KbSourceFileDbService;
import com.workbuddy.biz.service.support.ChainStorageSupport;
import com.workbuddy.biz.task.runner.persistence.ProductPersistence;
import com.workbuddy.common.domain.entity.KbPipelineProduct;
import com.workbuddy.common.domain.entity.KbPipelineTask;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.service.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 产物落库助手的执行幂等单测：一次执行至多一份产物 ——
 * 首次落库写对象并插行，同一任务再次落库复用既有行（不写对象、不插行、不覆盖任务指针）。
 *
 * @author cxxl
 */
@ExtendWith(MockitoExtension.class)
class ProductPersistenceTest {

    /** 档案与产物所在的数据源 */
    private static final Long SOURCE_ID = 1L;

    @Mock
    private KbPipelineProductDbService pipelineProductDbService;
    @Mock
    private KbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private KbFileResultDbService fileResultDbService;
    @Mock
    private KbSourceFileDbService sourceFileDbService;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private StorageRouter storageRouter;

    private ProductPersistence persistence;

    @BeforeEach
    void setUp() {
        // 存储守卫用真实实例 + mock 依赖：链类型取不到（默认 mock 返回 null）时不拦
        ChainStorageSupport chainStorage = new ChainStorageSupport(fileResultDbService, sourceFileDbService,
                fileStorage, storageRouter);
        persistence = new ProductPersistence(pipelineProductDbService, pipelineTaskDbService, chainStorage,
                fileStorage);
    }

    private KbPipelineTask task() {
        KbPipelineTask task = new KbPipelineTask();
        task.setId(70L);
        task.setFileResultId(10L);
        task.setStage(PipelineStage.CHUNK.name());
        return task;
    }

    @Test
    void firstPersistShouldWriteObjectAndInsertRow() {
        when(pipelineProductDbService.getByTaskId(70L)).thenReturn(null);
        when(fileStorage.putObject(any(byte[].class)))
                .thenReturn(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(22)));
        when(pipelineProductDbService.save(any(KbPipelineProduct.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbPipelineProduct.class).setId(80L);
            return true;
        });

        KbPipelineProduct product = persistence.persist(task(), PipelineStage.CHUNK, 50L, "{}", Map.of("a", 1));

        assertEquals(80L, product.getId());
        assertEquals(70L, product.getTaskId());
        assertEquals(PipelineStage.CHUNK.name(), product.getStage());
        verify(fileStorage).putObject(any(byte[].class));
        verify(pipelineProductDbService).save(any(KbPipelineProduct.class));
        verify(pipelineTaskDbService).updateProductId(70L, 80L);
    }

    @Test
    void secondPersistShouldReuseExistingRowWithoutWritingAgain() {
        KbPipelineProduct existing = new KbPipelineProduct();
        existing.setId(80L);
        existing.setTaskId(70L);
        existing.setArtifactId("abc".repeat(22));
        when(pipelineProductDbService.getByTaskId(70L)).thenReturn(existing);

        KbPipelineProduct product = persistence.persist(task(), PipelineStage.CHUNK, 50L, "{}", Map.of("a", 1));

        assertSame(existing, product);
        verify(fileStorage, never()).putObject(any(byte[].class));
        verify(pipelineProductDbService, never()).save(any(KbPipelineProduct.class));
        verify(pipelineTaskDbService, never()).updateProductId(any(), any());
    }
}
