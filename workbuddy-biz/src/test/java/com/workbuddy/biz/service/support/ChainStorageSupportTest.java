package com.workbuddy.biz.service.support;

import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.common.domain.storage.StorageRef;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.service.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 处理链存储口径单测：链的数据源取「文件结果 → 来源文件 → 文件档案」三段；
 * 链路缺环、档案数据源取不到、当前没有启用的数据源都不判定为不一致；两侧都取到且不同时拒绝执行（40453）。
 *
 * @author cxxl
 */
@ExtendWith(MockitoExtension.class)
class ChainStorageSupportTest {

    /** 档案所在的数据源 */
    private static final Long ARCHIVE_SOURCE = 1L;

    /** 当前启用的数据源 */
    private static final Long CURRENT_SOURCE = 2L;

    @Mock
    private WbFileResultDbService fileResultDbService;
    @Mock
    private WbSourceFileDbService sourceFileDbService;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private StorageRouter storageRouter;

    private ChainStorageSupport support;

    @BeforeEach
    void setUp() {
        support = new ChainStorageSupport(fileResultDbService, sourceFileDbService, fileStorage, storageRouter);
    }

    /** 链的各环齐全：文件结果 10 → 来源文件 5 → 文件档案 F-88 */
    private void stubChain() {
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        fileResult.setSourceFileId(5L);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult);
        WbSourceFile sourceFile = new WbSourceFile();
        sourceFile.setId(5L);
        sourceFile.setFileId("F-88");
        when(sourceFileDbService.getById(5L)).thenReturn(sourceFile);
    }

    @Test
    void chainSourceIdShouldBeReadFromArchive() {
        stubChain();
        when(fileStorage.refOf("F-88")).thenReturn(new StorageRef(StorageType.MINIO.getCode(), ARCHIVE_SOURCE));

        assertEquals(ARCHIVE_SOURCE, support.chainSourceIdOf(10L));
    }

    @Test
    void chainSourceIdShouldBeNullWhenFileResultMissing() {
        when(fileResultDbService.getById(10L)).thenReturn(null);

        assertNull(support.chainSourceIdOf(10L));
    }

    @Test
    void chainSourceIdShouldBeNullWhenSourceFileMissing() {
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        fileResult.setSourceFileId(5L);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult);
        when(sourceFileDbService.getById(5L)).thenReturn(null);

        assertNull(support.chainSourceIdOf(10L));
    }

    @Test
    void archiveSourceShouldBeNullWhenArchiveMissing() {
        when(fileStorage.refOf("F-88"))
                .thenThrow(new KnowledgeException(ErrorCode.FILE_NOT_FOUND, "文件档案不存在"));

        assertNull(support.storageSourceIdOfFile("F-88"));
    }

    @Test
    void archiveSourceShouldBeNullWhenArchiveHasNoSourceId() {
        when(fileStorage.refOf("F-88")).thenReturn(new StorageRef(StorageType.MINIO.getCode(), null));

        assertNull(support.storageSourceIdOfFile("F-88"));
    }

    @Test
    void archiveSourceShouldRethrowOtherFailure() {
        when(fileStorage.refOf("F-88"))
                .thenThrow(new KnowledgeException(ErrorCode.SYSTEM_ERROR, "存储不可用"));

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> support.storageSourceIdOfFile("F-88"));

        assertEquals(ErrorCode.SYSTEM_ERROR, e.getErrorCode());
    }

    @Test
    void blankFileIdShouldHaveNoArchiveSource() {
        assertNull(support.storageSourceIdOfFile("  "));
        assertNull(support.storageSourceIdOfFile(null));
    }

    @Test
    void batchChainSourceIdsShouldResolveEachResultFromArchive() {
        WbFileResult fr10 = new WbFileResult();
        fr10.setId(10L);
        fr10.setSourceFileId(5L);
        WbFileResult fr11 = new WbFileResult();
        fr11.setId(11L);
        fr11.setSourceFileId(6L);
        WbFileResult fr12 = new WbFileResult();
        fr12.setId(12L);
        fr12.setSourceFileId(null);
        when(fileResultDbService.listByIds(List.of(10L, 11L, 12L))).thenReturn(List.of(fr10, fr11, fr12));
        WbSourceFile source5 = new WbSourceFile();
        source5.setId(5L);
        source5.setFileId("F-88");
        WbSourceFile source6 = new WbSourceFile();
        source6.setId(6L);
        source6.setFileId("F-99");
        when(sourceFileDbService.listByIds(List.of(5L, 6L))).thenReturn(List.of(source5, source6));
        when(fileStorage.refsOf(List.of("F-88", "F-99")))
                .thenReturn(Map.of("F-88", new StorageRef(StorageType.MINIO.getCode(), ARCHIVE_SOURCE),
                        "F-99", new StorageRef(StorageType.MINIO.getCode(), null)));

        Map<Long, Long> sourceIds = support.chainSourceIdsOf(List.of(10L, 11L, 12L, 10L));

        // 11 的档案无数据源、12 没有来源文件：都不出现在映射里
        assertEquals(Map.of(10L, ARCHIVE_SOURCE), sourceIds);
    }

    @Test
    void batchChainSourceIdsShouldBeEmptyWithoutIds() {
        assertEquals(Map.of(), support.chainSourceIdsOf(List.of()));
    }

    @Test
    void requireStorageMatchShouldRejectMismatchWith40453() {
        stubChain();
        when(fileStorage.refOf("F-88")).thenReturn(new StorageRef(StorageType.MINIO.getCode(), ARCHIVE_SOURCE));
        when(storageRouter.currentSourceId()).thenReturn(CURRENT_SOURCE);
        when(storageRouter.nameOf(ARCHIVE_SOURCE)).thenReturn("演示 MinIO");
        when(storageRouter.nameOf(CURRENT_SOURCE)).thenReturn("本地磁盘");

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> support.requireStorageMatch(10L));

        assertEquals(ErrorCode.STORAGE_TYPE_MISMATCH, e.getErrorCode());
        // 文案按数据源名称给出两侧，用户能直接看出差在哪两个数据源
        assertTrue(e.getMessage().contains("演示 MinIO"), e.getMessage());
        assertTrue(e.getMessage().contains("本地磁盘"), e.getMessage());
        assertTrue(e.getMessage().contains("无法继续执行"), e.getMessage());
    }

    @Test
    void requireStorageMatchShouldPassWhenBothSidesMatch() {
        stubChain();
        when(fileStorage.refOf("F-88")).thenReturn(new StorageRef(StorageType.LOCAL.getCode(), CURRENT_SOURCE));
        when(storageRouter.currentSourceId()).thenReturn(CURRENT_SOURCE);

        assertDoesNotThrow(() -> support.requireStorageMatch(10L));
    }

    @Test
    void requireStorageMatchShouldPassWhenNoCurrentSource() {
        stubChain();
        when(fileStorage.refOf("F-88")).thenReturn(new StorageRef(StorageType.MINIO.getCode(), ARCHIVE_SOURCE));
        // 当前没有启用的数据源：取不到两侧的判定基准，不拦
        when(storageRouter.currentSourceId()).thenReturn(null);

        assertDoesNotThrow(() -> support.requireStorageMatch(10L));
    }

    @Test
    void archiveSourceShouldBeNullWhenArchiveIsMissing() {
        when(fileStorage.refOf("F-88")).thenReturn(null);

        assertNull(support.storageSourceIdOfFile("F-88"));
    }

    @Test
    void requireStorageMatchShouldPassWhenChainSourceUnavailable() {
        when(fileResultDbService.getById(10L)).thenReturn(null);
        when(storageRouter.currentSourceId()).thenReturn(CURRENT_SOURCE);

        assertDoesNotThrow(() -> support.requireStorageMatch(10L));
    }

    @Test
    void storageMismatchOfArchiveShouldJudgeAgainstCurrentSource() {
        when(storageRouter.currentSourceId()).thenReturn(CURRENT_SOURCE);

        assertTrue(support.storageMismatchOfArchive(ARCHIVE_SOURCE));
        assertFalse(support.storageMismatchOfArchive(CURRENT_SOURCE));
        assertFalse(support.storageMismatchOfArchive(null));
    }

    @Test
    void storageMismatchShouldJudgeChainAgainstCurrentSource() {
        stubChain();
        when(fileStorage.refOf("F-88")).thenReturn(new StorageRef(StorageType.MINIO.getCode(), ARCHIVE_SOURCE));
        when(storageRouter.currentSourceId()).thenReturn(CURRENT_SOURCE);

        assertTrue(support.storageMismatch(10L));
    }

    @Test
    void sourceNameShouldUseRegisteredNameThenTypeThenId() {
        when(storageRouter.nameOf(ARCHIVE_SOURCE)).thenReturn("演示 MinIO");
        assertEquals("演示 MinIO", support.sourceName(ARCHIVE_SOURCE));

        when(storageRouter.typeOf(CURRENT_SOURCE)).thenReturn(StorageType.LOCAL);
        assertEquals("本地", support.sourceName(CURRENT_SOURCE));

        assertEquals("数据源#77", support.sourceName(77L));
        assertEquals("未记录的数据源", support.sourceName(null));
    }

    @Test
    void currentSourceNameShouldFollowRouter() {
        when(storageRouter.currentSourceId()).thenReturn(CURRENT_SOURCE);
        when(storageRouter.nameOf(CURRENT_SOURCE)).thenReturn("本地磁盘");

        assertEquals(CURRENT_SOURCE, support.currentSourceId());
        assertEquals("本地磁盘", support.currentSourceName());
    }
}
