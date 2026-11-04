package com.workbuddy.biz.task.runner.asset;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 对象存储资产写入：内容寻址、元数据回填、异常归类。
 */
@ExtendWith(MockitoExtension.class)
class FileStorageParseAssetSinkTest {

    @Mock
    private FileStorage fileStorage;

    /** 同内容只登记一份资产，assetId 由对象键派生。 */
    @Test
    void sameContentShouldReuseAsset() {
        ObjectRef ref = new ObjectRef(1L, StorageType.LOCAL, "artifact", "abc123");
        when(fileStorage.putObject(any())).thenReturn(ref);
        FileStorageParseAssetSink sink = new FileStorageParseAssetSink(fileStorage);

        ParseAsset first = sink.put(ParseAssetKind.EMBEDDED_IMAGE, new byte[]{1, 2}, "image/png",
                null, null, 8, 8);
        ParseAsset second = sink.put(ParseAssetKind.EMBEDDED_IMAGE, new byte[]{1, 2}, "image/png",
                null, null, 8, 8);

        assertEquals(first, second);
        assertEquals(1, sink.assets().size());
        assertEquals("asset-abc123", first.getAssetId());
        assertEquals("abc123", first.getSha256());
        assertEquals(2L, first.getSize());
    }

    /** 空字节不写资产。 */
    @Test
    void emptyContentShouldBeSkipped() {
        FileStorageParseAssetSink sink = new FileStorageParseAssetSink(fileStorage);

        assertNull(sink.put(ParseAssetKind.EMBEDDED_IMAGE, new byte[0], "image/png", null, null, null, null));
        assertEquals(0, sink.assets().size());
    }

    /** 存储异常转 ParseAssetException，由管线归 PARSE_FAILED。 */
    @Test
    void storageFailureShouldBeWrapped() {
        when(fileStorage.putObject(any())).thenThrow(new IllegalStateException("disk"));
        FileStorageParseAssetSink sink = new FileStorageParseAssetSink(fileStorage);

        assertThrows(ParseAssetException.class, () -> sink.put(ParseAssetKind.EMBEDDED_IMAGE,
                new byte[]{1}, "image/png", null, null, null, null));
    }
}
