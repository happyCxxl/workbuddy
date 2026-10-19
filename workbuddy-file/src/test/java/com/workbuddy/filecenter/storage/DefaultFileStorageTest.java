package com.workbuddy.filecenter.storage;

import com.workbuddy.common.domain.entity.WbFileObject;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.storage.StorageRef;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.filecenter.db.WbFileObjectDbService;
import com.workbuddy.filecenter.provider.StorageProvider;
import com.workbuddy.filecenter.provider.StorageRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件存储实现单测。
 *
 * <p>守护三块契约：fileId 寻址写入（档案字段与内容指纹一一对应）、内容寻址去重
 * （相同内容不重复写）、按档案选后端读取（对象缺失 40454 与读取故障 40500 分开、
 * 档案缺失 40410）。存储后端与数据源路由为系统边界，用替身。
 */
@ExtendWith(MockitoExtension.class)
class DefaultFileStorageTest {

    private static final byte[] CONTENT = "hello storage".getBytes(StandardCharsets.UTF_8);

    @Mock
    private StorageRouter router;

    @Mock
    private StorageProvider provider;

    @Mock
    private WbFileObjectDbService fileObjectDbService;

    private DefaultFileStorage storage;

    /**
     * 每个用例重建存储实现；写入侧固定路由到本地类型的数据源 5。
     */
    @BeforeEach
    void setUp() {
        storage = new DefaultFileStorage(router, fileObjectDbService);
    }

    /**
     * 写入文件对象：对象写到当前数据源，档案字段与内容一一对应，指纹按内容计算。
     *
     * <p>守护 {@code putFile}：漏存任一档案字段或指纹算错即失败。
     */
    @Test
    void putFileShouldArchiveObjectWithDigest() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(CONTENT));
        when(file.getSize()).thenReturn((long) CONTENT.length);
        when(file.getContentType()).thenReturn("text/plain");
        when(file.getOriginalFilename()).thenReturn("a.txt");
        stubWriter(StorageType.LOCAL, 5L, "files");
        // 假实现会读完流，摘要写入才能算出内容指纹（纯桩不读流，指纹恒为空内容）
        org.mockito.Mockito.doAnswer(invocation -> {
            InputStream in = invocation.getArgument(2);
            in.readAllBytes();
            return null;
        }).when(provider).put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());

        String fileId = storage.putFile(file);

        ArgumentCaptor<WbFileObject> captor = ArgumentCaptor.forClass(WbFileObject.class);
        verify(fileObjectDbService).save(captor.capture());
        WbFileObject archived = captor.getValue();
        assertEquals(fileId, archived.getFileId());
        assertEquals("a.txt", archived.getFileName());
        assertEquals("text/plain", archived.getMimeType());
        assertEquals(CONTENT.length, archived.getFileSize());
        assertEquals(sha256(CONTENT), archived.getSha256());
        assertEquals("local", archived.getStorageType());
        assertEquals(5L, archived.getStorageSourceId());
        assertEquals("files", archived.getBucket());
        assertEquals(fileId, archived.getObjectKey());
        verify(provider).put(eq("files"), eq(fileId), any(InputStream.class), eq((long) CONTENT.length),
                eq("text/plain"));
    }

    /**
     * 内容寻址写入：对象已存在时不重复写，直接复用。
     *
     * <p>守护 {@code putObject} 的去重短路：去掉 exists 判断即失败（对同内容重复上传）。
     */
    @Test
    void putObjectShouldReuseExistingContent() {
        stubWriter(StorageType.MINIO, 5L, "artifacts");
        when(provider.exists("artifacts", sha256(CONTENT))).thenReturn(true);

        ObjectRef ref = storage.putObject(CONTENT);

        verify(provider, never()).put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());
        assertEquals(5L, ref.sourceId());
        assertEquals(StorageType.MINIO, ref.storageType());
        assertEquals("artifacts", ref.bucket());
        assertEquals(sha256(CONTENT), ref.objectKey());
    }

    /**
     * 内容寻址写入：对象不存在时写入，键即内容指纹。
     *
     * <p>守护 {@code putObject} 的写入路径：漏写即失败（读取侧会缺对象）。
     */
    @Test
    void putObjectShouldWriteWhenAbsent() {
        stubWriter(StorageType.LOCAL, 5L, "artifacts");
        when(provider.exists("artifacts", sha256(CONTENT))).thenReturn(false);

        ObjectRef ref = storage.putObject(CONTENT);

        verify(provider).put(eq("artifacts"), eq(sha256(CONTENT)), any(InputStream.class),
                eq((long) CONTENT.length), eq("application/octet-stream"));
        assertEquals(sha256(CONTENT), ref.objectKey());
    }

    /**
     * 对象写入失败时按系统异常收口（不把底层异常裸抛）。
     *
     * <p>守护 {@code putObject} 的失败兜底（错误码 40500）。
     */
    @Test
    void putObjectShouldWrapWriteFailure() {
        stubWriter(StorageType.LOCAL, 5L, "artifacts");
        when(provider.exists(anyString(), anyString())).thenReturn(false);
        org.mockito.Mockito.doThrow(new IllegalStateException("down"))
                .when(provider).put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> storage.putObject(CONTENT));

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), ex.getCode());
    }

    /**
     * 指定键写入：键由业务持有，位置四元组完整返回。
     *
     * <p>守护 {@code putRaw}：键或桶名传错即失败（业务侧寻址落空）。
     */
    @Test
    void putRawShouldWriteUnderGivenKey() {
        stubWriter(StorageType.LOCAL, 5L, "files");

        ObjectRef ref = storage.putRaw("avatar-1", new ByteArrayInputStream(CONTENT), CONTENT.length, "image/png");

        verify(provider).put(eq("files"), eq("avatar-1"), any(InputStream.class), eq((long) CONTENT.length),
                eq("image/png"));
        assertEquals("avatar-1", ref.objectKey());
        assertEquals("files", ref.bucket());
    }

    /**
     * 读取整个对象按档案选后端。
     *
     * <p>守护 {@code getObject}：后端选择或读流失即失败。
     */
    @Test
    void getObjectShouldReadFromBackendOfRef() {
        ObjectRef ref = new ObjectRef(5L, StorageType.LOCAL, "artifacts", sha256(CONTENT));
        when(router.of(ref)).thenReturn(provider);
        when(provider.get("artifacts", sha256(CONTENT))).thenReturn(new ByteArrayInputStream(CONTENT));

        assertArrayEquals(CONTENT, storage.getObject(ref));
    }

    /**
     * 对象在所属后端不存在时抛 40454（重建可恢复，区别于读取故障）。
     *
     * <p>守护 {@code openRef} 的缺失判定（错误码 40454）。
     */
    @Test
    void getObjectShouldReportMissingObject() {
        ObjectRef ref = new ObjectRef(5L, StorageType.LOCAL, "artifacts", "absent");
        when(router.of(ref)).thenReturn(provider);
        when(provider.get("artifacts", "absent")).thenThrow(new IllegalStateException("down"));
        when(provider.exists("artifacts", "absent")).thenReturn(false);

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> storage.getObject(ref));

        assertEquals(ErrorCode.STORAGE_OBJECT_MISSING.getCode(), ex.getCode());
    }

    /**
     * 后端故障但对象存在时抛 40500（读取故障，不是缺失）。
     *
     * <p>守护 {@code openRef} 的故障区分（错误码 40500）。
     */
    @Test
    void getObjectShouldReportReadFailureWhenObjectExists() {
        ObjectRef ref = new ObjectRef(5L, StorageType.LOCAL, "artifacts", sha256(CONTENT));
        when(router.of(ref)).thenReturn(provider);
        when(provider.get("artifacts", sha256(CONTENT))).thenThrow(new IllegalStateException("down"));
        when(provider.exists("artifacts", sha256(CONTENT))).thenReturn(true);

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> storage.getObject(ref));

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), ex.getCode());
    }

    /**
     * 文件档案不存在时抛 40410。
     *
     * <p>守护 {@code requireObject}（错误码 40410）：漏判空即失败（空档案被当有效数据用）。
     */
    @Test
    void refOfShouldRejectMissingArchive() {
        when(fileObjectDbService.getByFileId("f404")).thenReturn(null);

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> storage.refOf("f404"));

        assertEquals(ErrorCode.FILE_NOT_FOUND.getCode(), ex.getCode());
    }

    /**
     * 档案口径按 fileId 提取（类型码值与数据源实例）。
     *
     * <p>守护 {@code refOf} 的字段映射：类型或数据源取错即失败（读取选错后端）。
     */
    @Test
    void refOfShouldMapStorageRef() {
        WbFileObject row = archived("f1", "MINIO", 7L);

        StorageRef ref = storage.refOf("f1");

        assertEquals("MINIO", ref.storageType());
        assertEquals(7L, ref.sourceId());
    }

    /**
     * 批量口径按 fileId 索引，没有档案的不进结果。
     *
     * <p>守护 {@code refsOf}：漏档或多档即失败。
     */
    @Test
    void refsOfShouldIndexByFileId() {
        WbFileObject first = row("f1", "LOCAL", 5L);
        WbFileObject second = row("f2", "MINIO", 7L);
        when(fileObjectDbService.listByFileIds(List.of("f1", "f2")))
                .thenReturn(List.of(first, second));

        var refs = storage.refsOf(List.of("f1", "f2"));

        assertEquals(2, refs.size());
        assertEquals("LOCAL", refs.get("f1").storageType());
        assertEquals(7L, refs.get("f2").sourceId());
    }

    /**
     * 元数据从档案映射，指纹与大小一一对应。
     *
     * <p>守护 {@code metadata}：字段映射错位即失败。
     */
    @Test
    void metadataShouldMapFromArchive() {
        WbFileObject row = archived("f1", "LOCAL", 5L);
        row.setFileName("a.txt");
        row.setMimeType("text/plain");
        row.setFileSize((long) CONTENT.length);
        row.setSha256(sha256(CONTENT));

        var metadata = storage.metadata("f1");

        assertEquals("f1", metadata.getFileId());
        assertEquals("a.txt", metadata.getFileName());
        assertEquals("text/plain", metadata.getMimeType());
        assertEquals(CONTENT.length, metadata.getFileSize());
        assertEquals(sha256(CONTENT), metadata.getSha256());
    }

    /**
     * 档案存在性按档案表判定。
     *
     * <p>守护 {@code exists}：判空逻辑写反即失败。
     */
    @Test
    void existsShouldFollowArchivePresence() {
        archived("f1", "LOCAL", 5L);
        when(fileObjectDbService.getByFileId("f404")).thenReturn(null);

        assertTrue(storage.exists("f1"));
        assertFalse(storage.exists("f404"));
    }

    /**
     * 接好写入侧路由：当前数据源、文件/产物桶与写入后端。
     *
     * @param type   存储类型
     * @param sourceId 数据源 ID
     * @param bucket 桶名
     */
    private void stubWriter(StorageType type, Long sourceId, String bucket) {
        when(router.writer()).thenReturn(provider);
        // 失败路径在构造 ObjectRef 前就抛出，type 不一定被消费，放宽严格桩
        org.mockito.Mockito.lenient().when(provider.type()).thenReturn(type);
        when(router.currentSourceId()).thenReturn(sourceId);
        lenientBuckets(bucket);
    }

    /**
     * 桶名按需打桩（文件桶与产物桶共用同一值时两个桩都会被消费）。
     *
     * @param bucket 桶名
     */
    private void lenientBuckets(String bucket) {
        org.mockito.Mockito.lenient().when(router.currentFileBucket()).thenReturn(bucket);
        org.mockito.Mockito.lenient().when(router.currentArtifactBucket()).thenReturn(bucket);
    }

    /**
     * 构造一行文件档案。
     *
     * @param fileId   文件 ID
     * @param typeCode 存储类型码值
     * @param sourceId 数据源 ID
     * @return 档案行
     */
    private WbFileObject row(String fileId, String typeCode, Long sourceId) {
        WbFileObject row = new WbFileObject();
        row.setFileId(fileId);
        row.setStorageType(typeCode);
        row.setStorageSourceId(sourceId);
        return row;
    }

    /**
     * 构造一行文件档案并登记按 fileId 可查。
     *
     * @param fileId   文件 ID
     * @param typeCode 存储类型码值
     * @param sourceId 数据源 ID
     * @return 档案行
     */
    private WbFileObject archived(String fileId, String typeCode, Long sourceId) {
        WbFileObject row = row(fileId, typeCode, sourceId);
        when(fileObjectDbService.getByFileId(fileId)).thenReturn(row);
        return row;
    }

    /**
     * 计算内容的 sha256（与实现同口径）。
     *
     * @param content 内容字节
     * @return 十六进制摘要
     */
    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
