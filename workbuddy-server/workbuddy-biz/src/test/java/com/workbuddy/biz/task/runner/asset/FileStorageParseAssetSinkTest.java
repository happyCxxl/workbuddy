package com.workbuddy.biz.task.runner.asset;

import cn.hutool.crypto.digest.DigestUtil;
import com.workbuddy.common.domain.parse.ParseAsset;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.parse.ParseAssetKind;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.parser.asset.ParseAssetException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 对象存储资产写入：内容寻址、元数据回填、异常归类。
 */
@ExtendWith(MockitoExtension.class)
class FileStorageParseAssetSinkTest {

    @Mock
    private FileStorage fileStorage;

    /** 校验点：{@code FileStorageParseAssetSink#put} 对同内容只登记一份资产，assetId 由对象键派生。 */
    @Test
    void sameContentShouldReuseAsset() {
        String sha256 = DigestUtil.sha256Hex(new byte[]{1, 2});
        ObjectRef ref = new ObjectRef(1L, StorageType.LOCAL, "files", "parse-assets/" + sha256);
        when(fileStorage.putRaw(anyString(), any(InputStream.class), anyLong(), anyString())).thenReturn(ref);
        when(fileStorage.publicUrl(ref)).thenReturn("http://storage/files/parse-assets/" + sha256);
        FileStorageParseAssetSink sink = new FileStorageParseAssetSink(fileStorage);

        ParseAsset first = sink.put(ParseAssetKind.EMBEDDED_IMAGE, new byte[]{1, 2}, "image/png",
                null, null, 8, 8);
        ParseAsset second = sink.put(ParseAssetKind.EMBEDDED_IMAGE, new byte[]{1, 2}, "image/png",
                null, null, 8, 8);

        assertEquals(first, second);
        assertEquals(1, sink.assets().size());
        assertEquals("asset-" + sha256, first.getAssetId());
        assertEquals(sha256, first.getSha256());
        assertEquals("http://storage/files/parse-assets/" + sha256, first.getUrl());
        assertEquals(2L, first.getSize());
    }

    /** 校验点：{@code FileStorageParseAssetSink#put} 对空字节不写资产。 */
    @Test
    void emptyContentShouldBeSkipped() {
        FileStorageParseAssetSink sink = new FileStorageParseAssetSink(fileStorage);

        assertNull(sink.put(ParseAssetKind.EMBEDDED_IMAGE, new byte[0], "image/png", null, null, null, null));
        assertEquals(0, sink.assets().size());
    }

    /** 校验点：存储异常转 {@code ParseAssetException}，由管线归 {@code PipelineTaskErrorCode#PARSE_FAILED}。 */
    @Test
    void storageFailureShouldBeWrapped() {
        when(fileStorage.putRaw(anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("disk"));
        FileStorageParseAssetSink sink = new FileStorageParseAssetSink(fileStorage);

        assertThrows(ParseAssetException.class, () -> sink.put(ParseAssetKind.EMBEDDED_IMAGE,
                new byte[]{1}, "image/png", null, null, null, null));
    }
}
