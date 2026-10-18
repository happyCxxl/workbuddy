package com.workbuddy.filecenter.provider;

import com.workbuddy.common.enums.storage.StorageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地磁盘存储提供者测试：两层目录布局、覆盖写、存在性、路径逃逸防护与存储根取值。
 *
 * <p>用 {@link TempDir} 而不是固定路径：测试不写进仓库目录，也不会互相干扰。
 */
class LocalStorageProviderTest {

    @TempDir
    Path root;

    private LocalStorageProvider provider;

    @BeforeEach
    void setUp() {
        provider = new LocalStorageProvider(localDef(root.toString()));
    }

    /** 本地磁盘数据源定义：存储根指向临时目录 */
    private static StorageSourceDef localDef(String rootDir) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(StorageSourceDef.KEY_ROOT_DIR, rootDir);
        params.put(StorageSourceDef.KEY_FILE_DIR, "files");
        params.put(StorageSourceDef.KEY_ARTIFACT_DIR, "artifacts");
        return new StorageSourceDef(1L, "本地磁盘", StorageType.LOCAL, params);
    }

    @AfterEach
    void tearDown() {
        provider = null;
    }

    @Test
    void shouldReportLocalStorageType() {
        assertEquals(StorageType.LOCAL, provider.type());
    }

    @Test
    void shouldStoreObjectUnderBucketDirectory() {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);

        provider.put("artifacts", "abc123", new ByteArrayInputStream(content), content.length, "text/plain");

        // 两层布局与对象存储一致：root/{bucket}/{key}
        assertTrue(Files.isRegularFile(root.resolve("artifacts").resolve("abc123")));
        assertTrue(provider.exists("artifacts", "abc123"));
    }

    @Test
    void shouldCreateStorageRootOnInitialize() {
        Path nested = root.resolve("nested").resolve("store");
        assertFalse(Files.exists(nested));

        new LocalStorageProvider(localDef(nested.toString())).initialize();

        assertTrue(Files.isDirectory(nested));
    }

    @Test
    void shouldReadBackExactBytes() throws IOException {
        byte[] content = "内容含中文与换行\n".getBytes(StandardCharsets.UTF_8);
        provider.put("files", "F-1", new ByteArrayInputStream(content), content.length, null);

        try (InputStream in = provider.get("files", "F-1")) {
            assertArrayEquals(content, in.readAllBytes());
        }
    }

    @Test
    void shouldOverwriteExistingObject() {
        provider.put("artifacts", "same", stream("first"), 5, null);
        provider.put("artifacts", "same", stream("second"), 6, null);

        assertEquals("second", read("artifacts", "same"));
        // 覆盖写用临时文件 + 移动实现，不应留下 .part 残留
        assertFalse(Files.exists(root.resolve("artifacts").resolve("same.part")));
    }

    @Test
    void shouldReportMissingObjectAsNotExisting() {
        assertFalse(provider.exists("artifacts", "never-written"));
    }

    @Test
    void shouldRejectMissingBucketOrKey() {
        assertThrows(IllegalArgumentException.class, () -> provider.exists("", "k"));
        assertThrows(IllegalArgumentException.class, () -> provider.exists("b", ""));
        assertThrows(IllegalArgumentException.class, () -> provider.exists(null, "k"));
        assertThrows(IllegalArgumentException.class, () -> provider.exists("b", null));
    }

    @Test
    void shouldRejectPathEscapingRoot() {
        // key 里的 .. 会被规范化后检出越界；提供者按通用层处理，不假设 key 一定干净
        assertThrows(IllegalArgumentException.class, () -> provider.exists("artifacts", "../../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> provider.exists("artifacts", "a/../../../outside"));
    }

    @Test
    void shouldRaiseOnReadingMissingObject() {
        assertThrows(IllegalStateException.class, () -> provider.get("artifacts", "absent"));
    }

    @Test
    void shouldCreateBucketDirectoryOnDemand() {
        Path bucketDir = root.resolve("new-bucket");
        assertFalse(Files.exists(bucketDir));

        provider.put("new-bucket", "k", stream("x"), 1, null);

        assertTrue(Files.isDirectory(bucketDir));
    }

    @Test
    void shouldIsolateObjectsAcrossBucketsWithSameKey() {
        // files 与 artifacts 里可以存在同名 key（fileId 与 sha256 空间不同），不能互相覆盖
        provider.put("files", "dup", stream("from-files"), 10, null);
        provider.put("artifacts", "dup", stream("from-artifacts"), 14, null);

        assertEquals("from-files", read("files", "dup"));
        assertEquals("from-artifacts", read("artifacts", "dup"));
    }

    private InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private String read(String bucket, String key) {
        try (InputStream in = provider.get(bucket, key)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
