package com.workbuddy.biz.controller.knowledge;

import com.workbuddy.biz.service.KnowledgeBaseService;
import com.workbuddy.common.dto.response.input.FileResultVO;
import com.workbuddy.common.dto.response.input.FileSubmitVO;
import com.workbuddy.common.dto.response.input.SubmitLogVO;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseStatsVO;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseVO;
import com.workbuddy.common.dto.response.knowledge.StrategyBindingVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
 * 知识库接口路由单测。
 *
 * <p>守护 URL 到方法的映射与参数传递：清单端点（分页/统计/绑定批量）、
 * 实例端点（详情、启停、删除、提交、绑定）、以及「精确路径优先于路径变量」
 * （/page、/stats 不得落进 /{id}）。用独立 MockMvc 装配单个 Controller，不加载容器。
 */
class KnowledgeBaseControllerRoutingTest {

    private KnowledgeBaseService knowledgeBaseService;

    private MockMvc mockMvc;

    /**
     * 每个用例重建 MockMvc：服务用替身，返回可空对象即可。
     */
    @BeforeEach
    void setUp() {
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new KnowledgeBaseController(knowledgeBaseService)).build();
    }

    /**
     * 创建：POST 清单路径带合法请求体，落到 create。
     *
     * <p>守护 {@code create} 的映射：路径或方法改错即失败。
     */
    @Test
    void createShouldRouteToService() throws Exception {
        when(knowledgeBaseService.create(any())).thenReturn(1L);

        mockMvc.perform(post("/knowledge-base")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"标书库\"}"))
                .andExpect(status().isOk());

        verify(knowledgeBaseService).create(any());
    }

    /**
     * 更新：PUT 实例路径，路径 ID 与请求体一并传服务。
     *
     * <p>守护 {@code update} 的映射：请求体缺 ID 应被校验拒绝（400）。
     */
    @Test
    void updateShouldRouteWithPathIdAndBody() throws Exception {
        when(knowledgeBaseService.update(any())).thenReturn(true);

        mockMvc.perform(put("/knowledge-base/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":9,\"name\":\"新名\"}"))
                .andExpect(status().isOk());

        verify(knowledgeBaseService).update(any());
    }

    /**
     * 更新请求体缺 ID 时被校验拒绝（40001），不落服务。
     *
     * <p>守护 {@code update} 的 @NotNull 校验。
     */
    @Test
    void updateShouldRejectBodyWithoutId() throws Exception {
        mockMvc.perform(put("/knowledge-base/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新名\"}"))
                .andExpect(status().isBadRequest());

        verify(knowledgeBaseService, org.mockito.Mockito.never()).update(any());
    }

    /**
     * 详情：GET 实例路径。
     *
     * <p>守护 {@code detail} 的映射。
     */
    @Test
    void detailShouldRouteToService() throws Exception {
        when(knowledgeBaseService.detail(9L)).thenReturn(new KnowledgeBaseVO());

        mockMvc.perform(get("/knowledge-base/9")).andExpect(status().isOk());

        verify(knowledgeBaseService).detail(9L);
    }

    /**
     * 分页与统计走精确路径，不落进 /{id}。
     *
     * <p>守护 {@code page}/{@code stats} 的精确路径优先：若 /{id} 抢占了 /page，则分页查询会被当详情处理。
     */
    @Test
    void pageAndStatsShouldTakeExactPathsOverPathVariable() throws Exception {
        when(knowledgeBaseService.page(any())).thenReturn(new Page<>());
        when(knowledgeBaseService.stats()).thenReturn(new KnowledgeBaseStatsVO());

        mockMvc.perform(get("/knowledge-base/page")).andExpect(status().isOk());
        mockMvc.perform(get("/knowledge-base/stats")).andExpect(status().isOk());

        verify(knowledgeBaseService).page(any());
        verify(knowledgeBaseService).stats();
        verify(knowledgeBaseService, org.mockito.Mockito.never()).detail(any());
    }

    /**
     * 启停与删除各走各的 POST/DELETE 动作路径。
     *
     * <p>守护 {@code disable}/{@code enable}/{@code delete} 的映射与方法区分。
     */
    @Test
    void disableEnableDeleteShouldRouteToService() throws Exception {
        when(knowledgeBaseService.disable(9L)).thenReturn(true);
        when(knowledgeBaseService.enable(9L)).thenReturn(true);
        when(knowledgeBaseService.delete(9L)).thenReturn(true);

        mockMvc.perform(post("/knowledge-base/9/disable")).andExpect(status().isOk());
        mockMvc.perform(post("/knowledge-base/9/enable")).andExpect(status().isOk());
        mockMvc.perform(delete("/knowledge-base/9")).andExpect(status().isOk());

        verify(knowledgeBaseService).disable(9L);
        verify(knowledgeBaseService).enable(9L);
        verify(knowledgeBaseService).delete(9L);
    }

    /**
     * 提交文档：POST 动作路径带合法请求体。
     *
     * <p>守护 {@code submit} 的映射与请求体传递：fileId 缺失应被校验拒绝。
     */
    @Test
    void submitShouldRouteWithBody() throws Exception {
        when(knowledgeBaseService.submit(eq(9L), any())).thenReturn(new FileSubmitVO());

        mockMvc.perform(post("/knowledge-base/9/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":\"f-1\"}"))
                .andExpect(status().isOk());

        verify(knowledgeBaseService).submit(eq(9L), any());
    }

    /**
     * 提交记录与文件结果两个分页端点分别路由。
     *
     * <p>守护 {@code pageSubmitLogs}/{@code pageFileResults} 的映射。
     */
    @Test
    void submitLogsAndFileResultsShouldRouteToService() throws Exception {
        when(knowledgeBaseService.pageSubmitLogs(eq(9L), any())).thenReturn(new Page<SubmitLogVO>());
        when(knowledgeBaseService.pageFileResults(eq(9L), any())).thenReturn(new Page<FileResultVO>());

        mockMvc.perform(get("/knowledge-base/9/submit-logs")).andExpect(status().isOk());
        mockMvc.perform(get("/knowledge-base/9/file-results")).andExpect(status().isOk());

        verify(knowledgeBaseService).pageSubmitLogs(eq(9L), any());
        verify(knowledgeBaseService).pageFileResults(eq(9L), any());
    }

    /**
     * 策略绑定：单库查询按 query 参数、批量查询按类型、单库更新与批量更新各走各端点。
     *
     * <p>守护 {@code strategyBinding}/{@code strategyBindings}/{@code bindStrategy}/{@code bindStrategies} 的映射与参数传递：strategyType 缺失应被校验拒绝（400）。
     */
    @Test
    void strategyBindingEndpointsShouldRouteToService() throws Exception {
        when(knowledgeBaseService.strategyBinding(9L, "CHUNK")).thenReturn(new StrategyBindingVO());
        when(knowledgeBaseService.strategyBindings("CHUNK")).thenReturn(List.of(new StrategyBindingVO()));
        when(knowledgeBaseService.bindStrategy(eq(9L), any())).thenReturn(true);
        when(knowledgeBaseService.bindStrategies(eq(9L), any())).thenReturn(true);

        mockMvc.perform(get("/knowledge-base/9/strategy-binding")
                        .param("strategyType", "CHUNK"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/knowledge-base/strategy-bindings")
                        .param("strategyType", "CHUNK"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/knowledge-base/9/strategy-binding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategyType\":\"CHUNK\",\"strategyVersionId\":3}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/knowledge-base/9/strategy-bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bindings\":[{\"strategyType\":\"CHUNK\",\"strategyVersionId\":3}]}"))
                .andExpect(status().isOk());

        verify(knowledgeBaseService).strategyBinding(9L, "CHUNK");
        verify(knowledgeBaseService).strategyBindings("CHUNK");
        verify(knowledgeBaseService).bindStrategy(eq(9L), any());
        verify(knowledgeBaseService).bindStrategies(eq(9L), any());
    }

    /**
     * 单库绑定查询缺 strategyType 时被校验拒绝（400）。
     *
     * <p>守护 {@code strategyBinding} 的必填参数。
     */
    @Test
    void strategyBindingShouldRejectMissingType() throws Exception {
        mockMvc.perform(get("/knowledge-base/9/strategy-binding")).andExpect(status().isBadRequest());

        verify(knowledgeBaseService, org.mockito.Mockito.never()).strategyBinding(any(), any());
    }
}
