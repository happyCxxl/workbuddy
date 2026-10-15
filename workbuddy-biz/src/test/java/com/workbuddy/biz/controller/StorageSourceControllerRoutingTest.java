package com.workbuddy.biz.controller;

import com.workbuddy.biz.service.StorageSourceService;
import com.workbuddy.common.dto.response.setting.StorageSourceVO;
import com.workbuddy.common.dto.response.setting.StorageSwitchPreviewVO;
import com.workbuddy.common.dto.response.setting.StorageSwitchResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 存储数据源接口路由测试：清单、切换预览与设为当前启用的路径映射，以及已下线的动作段不再占用路径。
 *
 * <p>用独立 MockMvc 装配单个 Controller：不加载数据源与容器，只验证 URL 到方法的映射。
 *
 * @author cxxl
 */
class StorageSourceControllerRoutingTest {

    private StorageSourceService storageSourceService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        storageSourceService = mock(StorageSourceService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new StorageSourceController(storageSourceService)).build();
    }

    @Test
    void listPathShouldRouteToService() throws Exception {
        when(storageSourceService.list()).thenReturn(List.of(new StorageSourceVO()));

        mockMvc.perform(get("/system/storage-sources")).andExpect(status().isOk());

        verify(storageSourceService).list();
    }

    @Test
    void previewPathShouldRouteToPreviewWithoutBody() throws Exception {
        when(storageSourceService.previewCurrent(11L)).thenReturn(new StorageSwitchPreviewVO());

        mockMvc.perform(post("/system/storage-sources/11/current/preview")).andExpect(status().isOk());

        verify(storageSourceService).previewCurrent(11L);
        verify(storageSourceService, never()).switchCurrent(any(), any());
    }

    @Test
    void currentPathShouldRouteToSwitch() throws Exception {
        when(storageSourceService.switchCurrent(eq(11L), any())).thenReturn(new StorageSwitchResultVO());

        mockMvc.perform(post("/system/storage-sources/11/current")).andExpect(status().isOk());

        verify(storageSourceService).switchCurrent(11L, null);
        verify(storageSourceService, never()).previewCurrent(any());
    }

    @Test
    void removedActionPathsShouldNotBeMapped() throws Exception {
        // 详情、新增、编辑、启停、删除与连接测试都没有映射：GET 详情与各动作段一律 404，POST 清单路径没有对应方法
        mockMvc.perform(get("/system/storage-sources/11")).andExpect(status().isNotFound());
        mockMvc.perform(post("/system/storage-sources/11/status")).andExpect(status().isNotFound());
        mockMvc.perform(post("/system/storage-sources/test")).andExpect(status().isNotFound());
        mockMvc.perform(post("/system/storage-sources/11")).andExpect(status().isNotFound());
        mockMvc.perform(delete("/system/storage-sources/11")).andExpect(status().isNotFound());
        mockMvc.perform(post("/system/storage-sources")).andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(storageSourceService);
    }
}
