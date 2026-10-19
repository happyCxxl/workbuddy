package com.workbuddy.filecenter.provider;

import com.workbuddy.common.enums.storage.StorageType;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MinIO 存储提供者单测（替换内部客户端，不连真实服务）。
 *
 * <p>守护四个方法的失败口径：写/读/探测失败都转成 IllegalStateException（由上层翻译成 40500），
 * 桶就绪按需建桶；一异常吞掉会让对象存储故障表现为空内容。
 */
class MinioStorageProviderTest {

    private MinioClient client;

    private MinioStorageProvider provider;

    /**
     * 每个用例注入 MinIO 客户端替身（不建立网络连接）。
     */
    @BeforeEach
    void setUp() {
        provider = new MinioStorageProvider(def());
        client = mock(MinioClient.class);
        ReflectionTestUtils.setField(provider, "minioClient", client);
    }

    /**
     * 存储类型固定为 MINIO。
     *
     * <p>守护 {@code type}：类型错会让路由写到别的后端。
     */
    @Test
    void typeShouldBeMinio() {
        assertEquals(StorageType.MINIO, provider.type());
    }

    /**
     * 桶已存在：不重复建桶；不存在：按需建两个桶。
     *
     * <p>守护 {@code initialize}/{@code ensureBucket}：漏建桶会让首次写入失败。
     */
    @Test
    void initializeShouldCreateOnlyMissingBuckets() throws Exception {
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        provider.initialize();
        verify(client, never()).makeBucket(any(MakeBucketArgs.class));

        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
        provider.initialize();
        verify(client, org.mockito.Mockito.times(2)).makeBucket(any(MakeBucketArgs.class));
    }

    /**
     * 桶探测异常：转成 IllegalStateException。
     *
     * <p>守护 {@code ensureBucket} 的失败翻译：吞异常会让启动看似成功、运行期才炸。
     */
    @Test
    void initializeShouldWrapFailure() throws Exception {
        when(client.bucketExists(any(BucketExistsArgs.class))).thenThrow(new RuntimeException("down"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> provider.initialize());

        assertTrue(e.getMessage().contains("桶初始化失败"), e.getMessage());
    }

    /**
     * 写入：把流、大小与内容类型交给客户端；失败转 IllegalStateException。
     *
     * <p>守护 {@code put}：参数错位会让对象内容损坏。
     */
    @Test
    void putShouldDelegateAndWrapFailure() throws Exception {
        byte[] content = "hello".getBytes();
        provider.put("files", "k", new ByteArrayInputStream(content), content.length, "text/plain");

        verify(client).putObject(any(PutObjectArgs.class));

        when(client.putObject(any(PutObjectArgs.class))).thenThrow(new RuntimeException("down"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> provider.put("files", "k", new ByteArrayInputStream(content), content.length, "text/plain"));
        assertTrue(e.getMessage().contains("写入失败"), e.getMessage());
    }

    /**
     * 读取：返回客户端给的流；失败转 IllegalStateException。
     *
     * <p>守护 {@code get}。
     */
    @Test
    void getShouldReturnClientStreamAndWrapFailure() throws Exception {
        GetObjectResponse stream = mock(GetObjectResponse.class);
        when(client.getObject(any(GetObjectArgs.class))).thenReturn(stream);
        assertSame(stream, provider.get("files", "k"));

        when(client.getObject(any(GetObjectArgs.class))).thenThrow(new RuntimeException("down"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> provider.get("files", "k"));
        assertTrue(e.getMessage().contains("读取失败"), e.getMessage());
    }

    /**
     * 存在性：statObject 成功为 true，抛异常为 false。
     *
     * <p>守护 {@code exists}：异常被当成存在会让内容寻址跳过写入。
     */
    @Test
    void existsShouldReflectStatResult() throws Exception {
        when(client.statObject(any(StatObjectArgs.class))).thenReturn(mock(StatObjectResponse.class));
        assertTrue(provider.exists("files", "k"));

        when(client.statObject(any(StatObjectArgs.class))).thenThrow(new RuntimeException("missing"));
        assertFalse(provider.exists("files", "k"));
    }

    /** 数据源定义夹具（不连网，仅提供构造参数） */
    private StorageSourceDef def() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(StorageSourceDef.KEY_ENDPOINT, "http://127.0.0.1:9000");
        params.put(StorageSourceDef.KEY_ACCESS_KEY, "ak");
        params.put(StorageSourceDef.KEY_SECRET_KEY, "sk");
        params.put(StorageSourceDef.KEY_FILE_BUCKET, "files");
        params.put(StorageSourceDef.KEY_ARTIFACT_BUCKET, "artifacts");
        return new StorageSourceDef(1L, "minio", StorageType.MINIO, params);
    }
}
