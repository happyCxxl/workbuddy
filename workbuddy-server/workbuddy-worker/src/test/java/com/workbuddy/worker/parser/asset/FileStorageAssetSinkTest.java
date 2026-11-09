package com.workbuddy.worker.parser.asset;

import com.workbuddy.common.domain.parse.ParseAsset;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.parse.ParseAssetKind;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.filecenter.asset.FileAssetArchive;
import com.workbuddy.filecenter.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 解析资产写入：内容寻址、元数据回填、档案登记与异常归类。
 */
@ExtendWith(MockitoExtension.class)
class FileStorageAssetSinkTest {

    @Mock
    private FileStorage fileStorage;

    @Mock
    private FileAssetArchive fileAssetArchive;

    private FileStorageAssetSink sink() {
        return new FileStorageAssetSink(fileStorage, fileAssetArchive, 9L);
    }

    /**
     * 守护 {@code sameContentShouldReuseAsset}：同内容只写一次对象、只登记一份档案。
     */
    @Test
    void sameContentShouldReuseAsset() {
        String sha256 = sha256Hex(new byte[] {1, 2});
        ObjectRef ref = new ObjectRef(1L, StorageType.LOCAL, "files", "parse-assets/" + sha256);
        when(fileStorage.putRaw(anyString(), any(InputStream.class), anyLong(), anyString())).thenReturn(ref);
        when(fileStorage.publicUrl(ref)).thenReturn("http://storage/files/parse-assets/" + sha256);
        FileStorageAssetSink sink = sink();

        ParseAsset first = sink.put(ParseAssetKind.EMBEDDED_IMAGE, new byte[] {1, 2}, "image/png",
                null, null, 8, 8);
        ParseAsset second = sink.put(ParseAssetKind.EMBEDDED_IMAGE, new byte[] {1, 2}, "image/png",
                null, null, 8, 8);

        assertEquals(first, second);
        assertEquals(1, sink.assets().size());
        assertEquals("asset-" + sha256, first.getAssetId());
        assertEquals(sha256, first.getSha256());
        assertEquals("http://storage/files/parse-assets/" + sha256, first.getUrl());
        assertEquals(2L, first.getSize());
        verify(fileAssetArchive, times(1)).register(eq(first), eq(9L));
    }

    /**
     * 守护 {@code emptyContentShouldBeSkipped}：空字节不写对象、不登记档案。
     */
    @Test
    void emptyContentShouldBeSkipped() {
        FileStorageAssetSink sink = sink();

        assertNull(sink.put(ParseAssetKind.EMBEDDED_IMAGE, new byte[0], "image/png", null, null, null, null));
        assertEquals(0, sink.assets().size());
    }

    /**
     * 守护 {@code storageFailureShouldBeWrapped}：存储异常转 {@code ParseAssetException}。
     */
    @Test
    void storageFailureShouldBeWrapped() {
        when(fileStorage.putRaw(anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("disk"));
        FileStorageAssetSink sink = sink();

        assertThrows(ParseAssetException.class, () -> sink.put(ParseAssetKind.EMBEDDED_IMAGE,
                new byte[] {1}, "image/png", null, null, null, null));
    }

    /**
     * 守护 {@code archiveFailureShouldNotBreakParsing}：档案登记失败只告警，资产仍返回给解析产物。
     */
    @Test
    void archiveFailureShouldNotBreakParsing() {
        String sha256 = sha256Hex(new byte[] {1});
        ObjectRef ref = new ObjectRef(1L, StorageType.LOCAL, "files", "parse-assets/" + sha256);
        when(fileStorage.putRaw(anyString(), any(InputStream.class), anyLong(), anyString())).thenReturn(ref);
        when(fileStorage.publicUrl(ref)).thenReturn(null);
        doThrow(new IllegalStateException("db down")).when(fileAssetArchive).register(any(), anyLong());
        FileStorageAssetSink sink = sink();

        ParseAsset asset = sink.put(ParseAssetKind.PAGE_IMAGE, new byte[] {1}, "image/png",
                1, null, null, null);

        assertTrue(asset != null && sink.assets().size() == 1);
    }

    /** 测试用内容指纹。 */
    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
