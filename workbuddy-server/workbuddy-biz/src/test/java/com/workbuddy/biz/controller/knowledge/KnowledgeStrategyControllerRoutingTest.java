package com.workbuddy.biz.controller.knowledge;

import com.workbuddy.biz.service.StrategyVersionService;
import com.workbuddy.common.dto.response.strategy.StrategyVersionVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 策略版本接口路由单测。
 *
 * <p>守护 URL 到方法的映射与参数传递：清单（下拉/管理两档）、创建/编辑/启停/删除。
 * 用独立 MockMvc 装配单个 Controller，不加载容器。
 */
class KnowledgeStrategyControllerRoutingTest {

    private StrategyVersionService strategyVersionService;

    private MockMvc mockMvc;

    /**
     * 每个用例重建 MockMvc；服务用替身，返回可空对象即可。
     */
    @BeforeEach
    void setUp() {
        strategyVersionService = mock(StrategyVersionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new KnowledgeStrategyController(strategyVersionService)).build();
    }

    /**
     * 清单按类型与含停用标记路由。
     *
     * <p>守护 {@code list} 的映射与参数传递：type 或 includeInactive 丢失即失败。
     */
    @Test
    void listShouldRouteTypeAndIncludeInactive() throws Exception {
        when(strategyVersionService.list("CHUNK", false)).thenReturn(List.of(new StrategyVersionVO()));
        when(strategyVersionService.list("CHUNK", true)).thenReturn(List.of(new StrategyVersionVO()));

        mockMvc.perform(get("/strategy-versions").param("type", "CHUNK")).andExpect(status().isOk());
        mockMvc.perform(get("/strategy-versions")
                        .param("type", "CHUNK").param("includeInactive", "true"))
                .andExpect(status().isOk());

        verify(strategyVersionService).list("CHUNK", false);
        verify(strategyVersionService).list("CHUNK", true);
    }

    /**
     * 创建与编辑各走各的方法。
     *
     * <p>守护 {@code create}/{@code update} 的映射。
     */
    @Test
    void createAndUpdateShouldRouteToService() throws Exception {
        when(strategyVersionService.create(any())).thenReturn(new StrategyVersionVO());
        when(strategyVersionService.update(eq(9L), any())).thenReturn(new StrategyVersionVO());

        mockMvc.perform(post("/strategy-versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CHUNK\",\"name\":\"聚合\",\"version\":\"v1\",\"configSnapshot\":\"{}\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/strategy-versions/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新名\",\"version\":\"v2\",\"configSnapshot\":\"{}\"}"))
                .andExpect(status().isOk());

        verify(strategyVersionService).create(any());
        verify(strategyVersionService).update(eq(9L), any());
    }

    /**
     * 启停与删除按实例路径路由。
     *
     * <p>守护 {@code enable}/{@code disable}/{@code delete} 的映射。
     */
    @Test
    void enableDisableDeleteShouldRouteToService() throws Exception {
        when(strategyVersionService.enable(9L)).thenReturn(new StrategyVersionVO());
        when(strategyVersionService.disable(9L)).thenReturn(new StrategyVersionVO());
        when(strategyVersionService.delete(9L)).thenReturn(true);

        mockMvc.perform(post("/strategy-versions/9/enable")).andExpect(status().isOk());
        mockMvc.perform(post("/strategy-versions/9/disable")).andExpect(status().isOk());
        mockMvc.perform(delete("/strategy-versions/9")).andExpect(status().isOk());

        verify(strategyVersionService).enable(9L);
        verify(strategyVersionService).disable(9L);
        verify(strategyVersionService).delete(9L);
    }
}
