package com.workbuddy.biz.controller.knowledge;

import com.workbuddy.biz.service.IndexSetService;
import com.workbuddy.biz.service.support.assemble.BuildOrderAssembler;
import com.workbuddy.common.dto.response.index.IndexValidateVO;
import com.workbuddy.common.dto.response.index.IndexVersionVO;
import com.workbuddy.common.dto.response.index.IndexBuildTriggerVO;
import com.workbuddy.common.dto.response.index.IndexComboVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 索引构建与发布接口路由单测。
 *
 * <p>守护生命周期端点的映射与参数传递：候选构建 → 校验 → 原子发布 → 回退 → 回收，
 * 挂在 /knowledge-base/{id} 之下。用独立 MockMvc 装配单个 Controller，不加载容器。
 */
class KnowledgeFileIndexControllerRoutingTest {

    private IndexSetService indexSetService;

    private BuildOrderAssembler buildOrderAssembler;

    private MockMvc mockMvc;

    /**
     * 每个用例重建 MockMvc；构建装配器返回空命令即可（路由测试不校验命令内容）。
     */
    @BeforeEach
    void setUp() {
        indexSetService = mock(IndexSetService.class);
        buildOrderAssembler = mock(BuildOrderAssembler.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new KnowledgeFileIndexController(indexSetService, buildOrderAssembler)).build();
    }

    /**
     * 版本列表与组合列表分别路由；组合列表的 fileResultIds 参数可选。
     *
     * <p>守护 {@code list}/{@code combos} 的映射与可选参数。
     */
    @Test
    void listAndCombosShouldRouteToService() throws Exception {
        when(indexSetService.listVersions(9L)).thenReturn(List.of(new IndexVersionVO()));
        when(indexSetService.listCombos(eq(9L), anyList())).thenReturn(List.of(new IndexComboVO()));

        mockMvc.perform(get("/knowledge-base/9/index-sets")).andExpect(status().isOk());
        mockMvc.perform(get("/knowledge-base/9/index-combos")
                        .param("fileResultIds", "1", "2"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/knowledge-base/9/index-combos")).andExpect(status().isOk());

        verify(indexSetService).listVersions(9L);
        verify(indexSetService).listCombos(9L, List.of(1L, 2L));
        verify(indexSetService).listCombos(9L, null);
    }

    /**
     * 触发构建：POST 动作路径，组合快照经装配器转命令后交服务。
     *
     * <p>守护 {@code build} 的映射与装配链：组合快照缺失应被校验拒绝（400）。
     */
    @Test
    void buildShouldRouteThroughAssembler() throws Exception {
        when(buildOrderAssembler.toCommand(any(), eq(9L))).thenReturn(null);
        when(indexSetService.buildCandidate(any())).thenReturn(new IndexBuildTriggerVO());

        mockMvc.perform(post("/knowledge-base/9/index-sets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comboSnapshot\":{},\"trigger\":\"manual\"}"))
                .andExpect(status().isOk());

        verify(buildOrderAssembler).toCommand(any(), eq(9L));
        verify(indexSetService).buildCandidate(any());
    }

    /**
     * 组合快照缺失时被校验拒绝（400），不进装配器。
     *
     * <p>守护 {@code build} 的 @NotNull 校验。
     */
    @Test
    void buildShouldRejectMissingComboSnapshot() throws Exception {
        mockMvc.perform(post("/knowledge-base/9/index-sets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trigger\":\"manual\"}"))
                .andExpect(status().isBadRequest());

        verify(buildOrderAssembler, org.mockito.Mockito.never()).toCommand(any(), any());
    }

    /**
     * 版本详情、校验、发布、回退、回收各走各的动作路径。
     *
     * <p>守护 {@code versionDetail}/{@code validate}/{@code publish}/{@code rollback}/{@code recycle} 的映射与版本 ID 传递。
     */
    @Test
    void lifecycleEndpointsShouldRouteToService() throws Exception {
        when(indexSetService.versionDetail(9L, 3L)).thenReturn(new IndexVersionVO());
        when(indexSetService.validate(3L)).thenReturn(new IndexValidateVO());

        mockMvc.perform(get("/knowledge-base/9/index-sets/3")).andExpect(status().isOk());
        mockMvc.perform(post("/knowledge-base/9/index-sets/3/validate")).andExpect(status().isOk());
        mockMvc.perform(post("/knowledge-base/9/index-sets/3/publish")).andExpect(status().isOk());
        mockMvc.perform(post("/knowledge-base/9/index-sets/3/rollback")).andExpect(status().isOk());
        mockMvc.perform(delete("/knowledge-base/9/index-sets/3")).andExpect(status().isOk());

        verify(indexSetService).versionDetail(9L, 3L);
        verify(indexSetService).validate(3L);
        verify(indexSetService).publish(3L);
        verify(indexSetService).rollback(3L);
        verify(indexSetService).recycle(3L);
    }
}
