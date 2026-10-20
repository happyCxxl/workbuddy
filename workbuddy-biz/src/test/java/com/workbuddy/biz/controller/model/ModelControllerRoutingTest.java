package com.workbuddy.biz.controller.model;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.service.ModelService;
import com.workbuddy.common.dto.request.model.ModelQueryDTO;
import com.workbuddy.common.dto.request.model.ModelSaveDTO;
import com.workbuddy.common.dto.request.model.ModelStatusDTO;
import com.workbuddy.common.dto.request.model.ModelUpdateDTO;
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
 */
class ModelControllerRoutingTest {

    private ModelService modelService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        modelService = mock(ModelService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ModelController(modelService)).build();
    }

    /**
     * 守护 {@code pagePathShouldRouteToServiceWithBoundQuery}：该场景行为改动即失败。
     */
    @Test
    void pagePathShouldRouteToServiceWithBoundQuery() throws Exception {
        when(modelService.page(any())).thenReturn(new Page<>(1, 10));

        mockMvc.perform(get("/model/page")
                .param("current", "2")
                .param("size", "5")
                .param("capability", "CHAT")
                .param("status", "ENABLED")
                .param("keyword", "qwen")).andExpect(status().isOk());

        ArgumentCaptor<ModelQueryDTO> captor = ArgumentCaptor.forClass(ModelQueryDTO.class);
        verify(modelService).page(captor.capture());
        assertEquals(2L, captor.getValue().getCurrent());
        assertEquals(5L, captor.getValue().getSize());
        assertEquals("CHAT", captor.getValue().getCapability());
        assertEquals("ENABLED", captor.getValue().getStatus());
        assertEquals("qwen", captor.getValue().getKeyword());
    }

    /**
     * 守护 {@code createPathShouldRouteToServiceWithBody}：该场景行为改动即失败。
     */
    @Test
    void createPathShouldRouteToServiceWithBody() throws Exception {
        when(modelService.create(any())).thenReturn(new ModelItemVO());

        mockMvc.perform(post("/model/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vendorCode\":\"deepseek\",\"modelKey\":\"qwen-max\","
                        + "\"capabilities\":[\"CHAT\"],\"currency\":\"CNY\",\"priceInPerM\":20,\"rpmLimit\":600}"))
                .andExpect(status().isOk());

        ModelSaveDTO dto = capturedSave();
        assertEquals("deepseek", dto.getVendorCode());
        assertEquals("qwen-max", dto.getModelKey());
        assertEquals(List.of("CHAT"), dto.getCapabilities());
        assertEquals(600, dto.getRpmLimit());
    }

    /**
     * 守护 {@code updatePathShouldRouteToServiceWithIdAndBody}：该场景行为改动即失败。
     */
    @Test
    void updatePathShouldRouteToServiceWithIdAndBody() throws Exception {
        when(modelService.update(any(), any())).thenReturn(new ModelItemVO());

        mockMvc.perform(put("/model/update/5")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vendorCode\":\"deepseek\",\"modelKey\":\"qwen-plus\","
                        + "\"capabilities\":[\"CHAT\"],\"currency\":\"CNY\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<ModelUpdateDTO> dtoCaptor = ArgumentCaptor.forClass(ModelUpdateDTO.class);
        verify(modelService).update(idCaptor.capture(), dtoCaptor.capture());
        assertEquals(5L, idCaptor.getValue());
        assertEquals("qwen-plus", dtoCaptor.getValue().getModelKey());
    }

    /**
     * 守护 {@code statusPathShouldRouteToServiceWithStatus}：该场景行为改动即失败。
     */
    @Test
    void statusPathShouldRouteToServiceWithStatus() throws Exception {
        when(modelService.updateStatus(any(), any())).thenReturn(new ModelItemVO());

        mockMvc.perform(patch("/model/updateStatus/6")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DISABLED\"}")).andExpect(status().isOk());

        ArgumentCaptor<ModelStatusDTO> captor = ArgumentCaptor.forClass(ModelStatusDTO.class);
        verify(modelService).updateStatus(eq(6L), captor.capture());
        assertEquals("DISABLED", captor.getValue().getStatus());
    }

    /**
     * 守护 {@code undefinedPathsShouldNotBeMapped}：该场景行为改动即失败。
     */
    @Test
    void undefinedPathsShouldNotBeMapped() throws Exception {
        mockMvc.perform(get("/model/credentials")).andExpect(status().isNotFound());
        mockMvc.perform(post("/model/credentials")).andExpect(status().isNotFound());
        mockMvc.perform(get("/model/route-policies")).andExpect(status().isNotFound());
        mockMvc.perform(post("/model/create/1/rotate")).andExpect(status().isNotFound());
        mockMvc.perform(put("/model/update")).andExpect(status().isNotFound());

        verifyNoInteractions(modelService);
    }

    /**
     * 守护 {@code everyEndpointShouldBeOpenToAnyLoggedInUser}：该场景行为改动即失败。
     */
    @Test
    void everyEndpointShouldBeOpenToAnyLoggedInUser() {
        for (String name : List.of("page", "create", "update", "updateStatus")) {
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

    /** 取最近一次新增接口的收参 */
    private ModelSaveDTO capturedSave() {
        ArgumentCaptor<ModelSaveDTO> captor = ArgumentCaptor.forClass(ModelSaveDTO.class);
        verify(modelService).create(captor.capture());
        return captor.getValue();
    }
}
