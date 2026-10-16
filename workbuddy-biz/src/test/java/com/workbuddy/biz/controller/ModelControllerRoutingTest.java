package com.workbuddy.biz.controller;

import com.workbuddy.biz.controller.model.ModelController;
import com.workbuddy.biz.service.ModelAdminService;
import com.workbuddy.common.dto.request.model.ModelQueryDto;
import com.workbuddy.common.dto.request.model.ModelSaveDto;
import com.workbuddy.common.dto.request.model.ModelStatusDto;
import com.workbuddy.common.dto.response.model.ModelItemVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模型管理接口路由测试：四条路径的映射与收参绑定、未定义的子路径不占用、
 * 四个方法都不要求管理员（模型管理对所有登录用户开放）。
 *
 * <p>用独立 MockMvc 装配单个 Controller：不加载数据源与容器，只验证 URL 到方法的映射与收参绑定。
 *
 * @author cxxl
 */
class ModelControllerRoutingTest {

    private ModelAdminService modelAdminService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        modelAdminService = mock(ModelAdminService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ModelController(modelAdminService)).build();
    }

    @Test
    void listPathShouldRouteToServiceWithBoundQuery() throws Exception {
        when(modelAdminService.list(any())).thenReturn(List.of(new ModelItemVO()));

        mockMvc.perform(get("/model/models")
                .param("capability", "CHAT")
                .param("status", "ENABLED")
                .param("keyword", "qwen")).andExpect(status().isOk());

        ModelQueryDto query = capturedListQuery();
        assertEquals("CHAT", query.getCapability());
        assertEquals("ENABLED", query.getStatus());
        assertEquals("qwen", query.getKeyword());
        verify(modelAdminService, never()).create(any());
    }

    @Test
    void createPathShouldRouteToServiceWithBody() throws Exception {
        when(modelAdminService.create(any())).thenReturn(new ModelItemVO());

        mockMvc.perform(post("/model/models")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vendorCode\":\"dashscope\",\"modelKey\":\"qwen-max\","
                        + "\"capabilities\":[\"CHAT\"],\"priceInPerM\":20,\"rpmLimit\":600}"))
                .andExpect(status().isOk());

        ModelSaveDto dto = capturedSave();
        assertEquals("dashscope", dto.getVendorCode());
        assertEquals("qwen-max", dto.getModelKey());
        assertEquals(List.of("CHAT"), dto.getCapabilities());
        assertEquals(600, dto.getRpmLimit());
    }

    @Test
    void updatePathShouldRouteToServiceWithIdAndBody() throws Exception {
        when(modelAdminService.update(any(), any())).thenReturn(new ModelItemVO());

        mockMvc.perform(put("/model/models/5")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vendorCode\":\"dashscope\",\"modelKey\":\"qwen-plus\",\"capabilities\":[\"CHAT\"]}"))
                .andExpect(status().isOk());

        ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<ModelSaveDto> dtoCaptor = ArgumentCaptor.forClass(ModelSaveDto.class);
        verify(modelAdminService).update(idCaptor.capture(), dtoCaptor.capture());
        assertEquals(5L, idCaptor.getValue());
        assertEquals("qwen-plus", dtoCaptor.getValue().getModelKey());
    }

    @Test
    void statusPathShouldRouteToServiceWithStatus() throws Exception {
        when(modelAdminService.updateStatus(any(), any())).thenReturn(new ModelItemVO());

        mockMvc.perform(patch("/model/models/6/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DISABLED\"}")).andExpect(status().isOk());

        ArgumentCaptor<ModelStatusDto> captor = ArgumentCaptor.forClass(ModelStatusDto.class);
        verify(modelAdminService).updateStatus(eq(6L), captor.capture());
        assertEquals("DISABLED", captor.getValue().getStatus());
    }

    @Test
    void undefinedPathsShouldNotBeMapped() throws Exception {
        mockMvc.perform(get("/model/credentials")).andExpect(status().isNotFound());
        mockMvc.perform(post("/model/credentials")).andExpect(status().isNotFound());
        mockMvc.perform(get("/model/route-policies")).andExpect(status().isNotFound());
        mockMvc.perform(post("/model/models/1/rotate")).andExpect(status().isNotFound());
        mockMvc.perform(put("/model/models")).andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(modelAdminService);
    }

    @Test
    void everyEndpointShouldBeOpenToAnyLoggedInUser() {
        for (String name : List.of("models", "create", "update", "updateStatus")) {
            assertFalse(hasAdminOnly(declaredMethod(name)),
                    name + " 不应挂 @AdminOnly：模型管理对所有登录用户开放");
        }
    }

    /** 按名字取 Controller 上的方法：取不到即用例失败 */
    private Method declaredMethod(String methodName) {
        for (Method method : ModelController.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        throw new AssertionError(methodName + " 在 ModelAdminController 上找不到");
    }

    /** 方法上是否挂了 `AdminOnly` 注解：按注解类型名判定 */
    private boolean hasAdminOnly(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            if ("AdminOnly".equals(annotation.annotationType().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    /** 取最近一次清单接口的收参 */
    private ModelQueryDto capturedListQuery() {
        ArgumentCaptor<ModelQueryDto> captor = ArgumentCaptor.forClass(ModelQueryDto.class);
        verify(modelAdminService).list(captor.capture());
        return captor.getValue();
    }

    /** 取最近一次新增接口的收参 */
    private ModelSaveDto capturedSave() {
        ArgumentCaptor<ModelSaveDto> captor = ArgumentCaptor.forClass(ModelSaveDto.class);
        verify(modelAdminService).create(captor.capture());
        return captor.getValue();
    }
}
