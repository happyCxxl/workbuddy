package com.workbuddy.filecenter.provider;

import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.infra.storage.StorageProvider;
import com.workbuddy.infra.storage.StorageSourceDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存储路由测试：按数据源 ID 注册与取用、写入走当前启用的数据源、未注册的 ID 与没有当前启用时拒绝。
 *
 * <p>只用本地磁盘形态：MinIO 形态的初始化会连真实服务并建桶，不适合放在单测里。
 */
class StorageRouterTest {

    /** 数据源 A */
    private static final Long SOURCE_A = 1L;

    /** 数据源 B */
    private static final Long SOURCE_B = 2L;

    @TempDir
    Path root;

    private StorageRouter router;

    @BeforeEach
    void setUp() {
        router = new StorageRouter();
        router.register(localDef(SOURCE_A, root.resolve("a").toString()));
        router.register(localDef(SOURCE_B, root.resolve("b").toString()));
    }

    /** 本地磁盘数据源定义：存储根指向指定目录 */
    private static StorageSourceDef localDef(Long id, String rootDir) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(StorageSourceDef.KEY_ROOT_DIR, rootDir);
        params.put(StorageSourceDef.KEY_FILE_DIR, "files");
        params.put(StorageSourceDef.KEY_ARTIFACT_DIR, "artifacts");
        return new StorageSourceDef(id, "本地磁盘-" + id, StorageType.LOCAL, params);
    }

    /**
     * 守护 {@code shouldReuseSameProviderInstancePerSource}：该场景行为改动即失败。
     */
    @Test
    void shouldReuseSameProviderInstancePerSource() {
        assertEquals(router.of(SOURCE_A), router.of(SOURCE_A));
        assertFalse(router.of(SOURCE_A) == router.of(SOURCE_B));
    }

    /**
     * 守护 {@code shouldCreateProviderBoundToRegisteredRoot}：该场景行为改动即失败。
     */
    @Test
    void shouldCreateProviderBoundToRegisteredRoot() {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);

        router.of(SOURCE_A).put("files", "k", new ByteArrayInputStream(content), content.length, null);

        assertTrue(Files.isRegularFile(root.resolve("a").resolve("files").resolve("k")));
    }

    /**
     * 守护 {@code reregisterShouldRebuildProviderWithNewParams}：该场景行为改动即失败。
     */
    @Test
    void reregisterShouldRebuildProviderWithNewParams() {
        byte[] content = "y".getBytes(StandardCharsets.UTF_8);
        router.register(localDef(SOURCE_A, root.resolve("moved").toString()));

        router.of(SOURCE_A).put("files", "k", new ByteArrayInputStream(content), content.length, null);

        assertTrue(Files.isRegularFile(root.resolve("moved").resolve("files").resolve("k")));
    }

    /**
     * 守护 {@code shouldRejectUnregisteredSourceIdWith40455}：该场景行为改动即失败。
     */
    @Test
    void shouldRejectUnregisteredSourceIdWith40455() {
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> router.of(99L));

        assertEquals(ErrorCode.STORAGE_BACKEND_UNCONFIGURED, e.getErrorCode());
        assertTrue(e.getMessage().contains("99"), e.getMessage());
    }

    /**
     * 守护 {@code shouldRejectNullSourceIdWith40455}：该场景行为改动即失败。
     */
    @Test
    void shouldRejectNullSourceIdWith40455() {
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> router.of((Long) null));

        assertEquals(ErrorCode.STORAGE_BACKEND_UNCONFIGURED, e.getErrorCode());
    }

    /**
     * 守护 {@code shouldRouteByObjectRefSourceId}：该场景行为改动即失败。
     */
    @Test
    void shouldRouteByObjectRefSourceId() {
        byte[] content = "z".getBytes(StandardCharsets.UTF_8);
        ObjectRef ref = new ObjectRef(SOURCE_B, StorageType.LOCAL, "files", "k");

        router.of(ref).put(ref.bucket(), ref.objectKey(), new ByteArrayInputStream(content), content.length, null);

        assertTrue(Files.isRegularFile(root.resolve("b").resolve("files").resolve("k")));
    }

    /**
     * 守护 {@code writerShouldRouteToCurrentSource}：该场景行为改动即失败。
     */
    @Test
    void writerShouldRouteToCurrentSource() {
        router.markCurrent(SOURCE_B);

        assertEquals(SOURCE_B, router.currentSourceId());
        assertEquals(StorageType.LOCAL, router.writeType());
        assertEquals("本地磁盘-2", router.nameOf(SOURCE_B));
        assertEquals(router.of(SOURCE_B), router.writer());
    }

    /**
     * 守护 {@code currentNameShouldFollowCurrentSource}：该场景行为改动即失败。
     */
    @Test
    void currentNameShouldFollowCurrentSource() {
        assertNull(router.currentName());

        router.markCurrent(SOURCE_B);

        assertEquals("本地磁盘-2", router.currentName());
    }

    /**
     * 守护 {@code shouldRejectWriterWithoutCurrentSource}：该场景行为改动即失败。
     */
    @Test
    void shouldRejectWriterWithoutCurrentSource() {
        assertNull(router.currentSourceId());
        assertNull(router.writeType());
        assertNull(router.currentDef());
        assertThrows(KnowledgeException.class, () -> router.writer());
        assertThrows(KnowledgeException.class, () -> router.currentFileBucket());
    }

    /**
     * 守护 {@code markCurrentShouldRejectUnregisteredId}：该场景行为改动即失败。
     */
    @Test
    void markCurrentShouldRejectUnregisteredId() {
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> router.markCurrent(99L));

        assertEquals(ErrorCode.STORAGE_BACKEND_UNCONFIGURED, e.getErrorCode());
        assertNull(router.currentSourceId());
    }

    /**
     * 守护 {@code unregisterShouldDropDefinitionAndClearCurrent}：该场景行为改动即失败。
     */
    @Test
    void unregisterShouldDropDefinitionAndClearCurrent() {
        router.markCurrent(SOURCE_A);

        router.unregister(SOURCE_A);

        assertFalse(router.registered(SOURCE_A));
        assertNull(router.currentSourceId());
        assertThrows(KnowledgeException.class, () -> router.of(SOURCE_A));
    }

    /**
     * 守护 {@code unregisterShouldKeepCurrentWhenOtherSourceRemoved}：该场景行为改动即失败。
     */
    @Test
    void unregisterShouldKeepCurrentWhenOtherSourceRemoved() {
        router.markCurrent(SOURCE_A);

        router.unregister(SOURCE_B);

        assertEquals(SOURCE_A, router.currentSourceId());
    }
}
