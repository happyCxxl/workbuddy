package com.workbuddy.filecenter.asset;

import com.workbuddy.common.domain.entity.WbFileAsset;
import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.ParseAsset;
import com.workbuddy.common.enums.parse.ParseAssetKind;
import com.workbuddy.filecenter.db.WbFileAssetDbService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 解析资产档案：登记幂等、字段回填与按归属清理。
 */
@ExtendWith(MockitoExtension.class)
class FileAssetArchiveTest {

    @Mock
    private WbFileAssetDbService fileAssetDbService;

    private FileAssetArchive archive() {
        return new FileAssetArchive(fileAssetDbService);
    }

    /**
     * 守护 {@code registerShouldSkipExisting}：同归属同内容已存在时不再写档案。
     */
    @Test
    void registerShouldSkipExisting() {
        ParseAsset asset = asset();
        when(fileAssetDbService.getByKnowledgeFileIdAndSha(9L, "sha")).thenReturn(new WbFileAsset());

        archive().register(asset, 9L);

        verify(fileAssetDbService, never()).save(any());
    }

    /**
     * 守护 {@code registerShouldPersistRow}：新资产写入档案，归属与位置字段逐项回填。
     */
    @Test
    void registerShouldPersistRow() {
        ParseAsset asset = asset();
        when(fileAssetDbService.getByKnowledgeFileIdAndSha(9L, "sha")).thenReturn(null);
        ArgumentCaptor<WbFileAsset> captor = ArgumentCaptor.forClass(WbFileAsset.class);

        archive().register(asset, 9L);

        verify(fileAssetDbService).save(captor.capture());
        WbFileAsset row = captor.getValue();
        assertEquals("asset-sha", row.getAssetId());
        assertEquals(9L, row.getKnowledgeFileId());
        assertEquals(ParseAssetKind.IMAGE_CROP.name(), row.getKind());
        assertEquals(3, row.getPage());
        assertEquals("sha", row.getSha256());
        assertEquals("image/png", row.getMimeType());
        assertEquals(12L, row.getFileSize());
        assertEquals(64, row.getWidth());
        assertEquals(32, row.getHeight());
        assertEquals("{\"x\":1,\"y\":2,\"width\":64,\"height\":32}", row.getBbox());
        assertEquals("LOCAL", row.getStorageType());
        assertEquals(1L, row.getStorageSourceId());
        assertEquals("files", row.getBucket());
        assertEquals("parse-assets/sha", row.getObjectKey());
        assertEquals("http://storage/files/parse-assets/sha", row.getUrl());
    }

    /**
     * 守护 {@code purgeShouldRemoveRows}：按归属清理时删除全部档案行。
     */
    @Test
    void purgeShouldRemoveRows() {
        WbFileAsset first = new WbFileAsset();
        first.setId(1L);
        WbFileAsset second = new WbFileAsset();
        second.setId(2L);
        when(fileAssetDbService.listByKnowledgeFileId(9L)).thenReturn(List.of(first, second));

        assertEquals(2, archive().purgeByKnowledgeFileId(9L));

        verify(fileAssetDbService).removeByIds(any());
    }

    /**
     * 守护 {@code purgeShouldSkipEmpty}：无档案时不调用删除。
     */
    @Test
    void purgeShouldSkipEmpty() {
        when(fileAssetDbService.listByKnowledgeFileId(9L)).thenReturn(List.of());

        assertEquals(0, archive().purgeByKnowledgeFileId(9L));

        verify(fileAssetDbService, never()).removeByIds(any());
    }

    /**
     * 守护 {@code registerShouldIgnoreNullOwner}：归属为空时不写档案。
     */
    @Test
    void registerShouldIgnoreNullOwner() {
        archive().register(asset(), null);

        verifyNoInteractions(fileAssetDbService);
    }

    /** 资产样例：一处裁剪图。 */
    private static ParseAsset asset() {
        ParseAsset asset = new ParseAsset();
        asset.setAssetId("asset-sha");
        asset.setKind(ParseAssetKind.IMAGE_CROP);
        asset.setPage(3);
        asset.setSha256("sha");
        asset.setMimeType("image/png");
        asset.setSize(12L);
        asset.setWidth(64);
        asset.setHeight(32);
        asset.setBbox(new BBox(1, 2, 64, 32));
        asset.setStorageType("LOCAL");
        asset.setStorageSourceId(1L);
        asset.setBucket("files");
        asset.setObjectKey("parse-assets/sha");
        asset.setUrl("http://storage/files/parse-assets/sha");
        return asset;
    }
}
