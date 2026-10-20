package com.workbuddy.biz.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbStrategyBindingDbService;
import com.workbuddy.biz.service.support.KnowledgeBaseAccessSupport;
import com.workbuddy.biz.service.support.KnowledgeBaseBindingSupport;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbStrategyBinding;
import com.workbuddy.common.dto.request.knowledge.StrategyBindingUpdateDTO;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseVO;
import com.workbuddy.common.enums.base.DelFlag;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseSort;
import com.workbuddy.common.enums.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库策略绑定摘要单测：审计摘要渲染、详情/分页的摘要回填与批量查询。
 *
 * <p>守护 {@code bindingSummary} / {@code fillStrategyBindings}：摘要写错、漏填类型或
 * 对失效版本硬填半截数据都会让前端展示与审计失真。
 */
class KnowledgeBaseBindingSummaryTest extends KnowledgeBaseTestFixture {

    private WbKnowledgeBaseDbService knowledgeBaseDbService;

    private WbAuditLogDbService kbAuditLogDbService;

    private WbFileResultDbService kbFileResultDbService;

    private WbStrategyBindingDbService strategyBindingDbService;

    private WbPipelineStrategyVersionDbService strategyVersionDbService;

    private KnowledgeBaseCrudServiceImpl crudService;

    private KnowledgeBaseBindingServiceImpl bindingService;

    /**
     * 每个用例重建绑定摘要的真实助手与被测服务，默认以普通用户登录。
     */
    @BeforeEach
    void setUp() {
        knowledgeBaseDbService = mock(WbKnowledgeBaseDbService.class);
        kbAuditLogDbService = mock(WbAuditLogDbService.class);
        kbFileResultDbService = mock(WbFileResultDbService.class);
        strategyBindingDbService = mock(WbStrategyBindingDbService.class);
        strategyVersionDbService = mock(WbPipelineStrategyVersionDbService.class);
        KnowledgeBaseAccessSupport accessSupport = new KnowledgeBaseAccessSupport(knowledgeBaseDbService);
        KnowledgeBaseBindingSupport bindingSupport =
                new KnowledgeBaseBindingSupport(strategyBindingDbService, strategyVersionDbService);
        crudService = new KnowledgeBaseCrudServiceImpl(knowledgeBaseDbService, kbAuditLogDbService,
                kbFileResultDbService, mock(WbIndexSetDbService.class), mock(WbIndexVersionDbService.class),
                accessSupport, bindingSupport);
        bindingService = new KnowledgeBaseBindingServiceImpl(kbAuditLogDbService, strategyBindingDbService,
                strategyVersionDbService, accessSupport, bindingSupport);
        login(ME, UserRole.USER);
    }

    /**
     * 审计里的「变更前」摘要：无绑定与已删行都渲染成「无」。
     *
     * <p>守护 {@code bindingSummary}：摘要写成空串或把已删行当有效绑定即失败。
     */
    @Test
    void bindStrategyAuditShouldRenderMissingAndDeletedBeforeAsNone() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        when(strategyVersionDbService.getById(66L)).thenReturn(chunkVersion());
        StrategyBindingUpdateDTO dto = new StrategyBindingUpdateDTO();
        dto.setStrategyType("CHUNK");
        dto.setStrategyVersionId(66L);
        // ① 无现有绑定 → 「无」
        when(strategyBindingDbService.getAnyByKbAndType(2L, "CHUNK")).thenReturn(null);
        assertTrue(bindingService.bindStrategy(2L, dto));

        // ② 现有行已逻辑删除 → 仍按「无」
        WbStrategyBinding deleted = binding(55L);
        deleted.setDelFlag(DelFlag.DELETED.getCode());
        when(strategyBindingDbService.getAnyByKbAndType(2L, "CHUNK")).thenReturn(deleted);
        assertTrue(bindingService.bindStrategy(2L, dto));

        verify(kbAuditLogDbService, times(2)).saveAudit(eq(AuditActionType.BIND), eq("KNOWLEDGE_BASE"),
                eq(2L), eq("CHUNK=无"), eq("CHUNK=chunk-hybrid-v1"));
    }

    /**
     * 审计里的「变更前」摘要：绑定指向的版本已不存在时渲染成「失效」。
     *
     * <p>守护 {@code bindingSummary}：跟随失效版本取名字会得到半截摘要。
     */
    @Test
    void bindStrategyAuditShouldRenderInvalidBeforeState() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        when(strategyVersionDbService.getById(66L)).thenReturn(chunkVersion());
        WbStrategyBinding orphan = binding(999L);
        when(strategyBindingDbService.getAnyByKbAndType(2L, "CHUNK")).thenReturn(orphan);
        when(strategyVersionDbService.getById(999L)).thenReturn(null);
        StrategyBindingUpdateDTO dto = new StrategyBindingUpdateDTO();
        dto.setStrategyType("CHUNK");
        dto.setStrategyVersionId(66L);

        assertTrue(bindingService.bindStrategy(2L, dto));

        verify(kbAuditLogDbService).saveAudit(eq(AuditActionType.BIND), eq("KNOWLEDGE_BASE"), eq(2L),
                eq("CHUNK=失效"), eq("CHUNK=chunk-hybrid-v1"));
    }

    /**
     * 详情：填充切片策略绑定摘要。
     *
     * <p>守护 {@code detail}：摘要漏填即失败。
     */
    @Test
    void detailShouldFillChunkBindingSummary() {
        WbKnowledgeBase k = kb(2L, 1);
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(k);
        when(strategyBindingDbService.getByKbAndType(2L, "CHUNK")).thenReturn(binding(66L));
        when(strategyVersionDbService.getById(66L)).thenReturn(chunkVersion());

        KnowledgeBaseVO vo = crudService.detail(2L);

        assertEquals(66L, vo.getChunkStrategyVersionId());
        assertEquals("chunk-hybrid-v1", vo.getChunkStrategyVersion());
    }

    /**
     * 详情：无绑定时摘要字段留空。
     *
     * <p>守护 {@code detail}：半截数据即失败。
     */
    @Test
    void detailWithoutBindingShouldLeaveSummaryEmpty() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        when(strategyBindingDbService.getByKbAndType(2L, "PREPROCESS")).thenReturn(null);
        when(strategyBindingDbService.getByKbAndType(2L, "CHUNK")).thenReturn(null);

        KnowledgeBaseVO vo = crudService.detail(2L);

        assertNull(vo.getChunkStrategyVersionId());
        assertNull(vo.getChunkStrategyVersion());
        assertNull(vo.getPreprocessStrategyVersionId());
        assertNull(vo.getPreprocessStrategyVersion());
    }

    /**
     * 详情：填充预处理策略绑定摘要。
     *
     * <p>守护 {@code detail}：摘要漏填即失败。
     */
    @Test
    void detailShouldFillPreprocessBindingSummary() {
        WbKnowledgeBase k = kb(2L, 1);
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(k);
        WbStrategyBinding preprocessBinding = new WbStrategyBinding();
        preprocessBinding.setId(2L);
        preprocessBinding.setKnowledgeBaseId(2L);
        preprocessBinding.setStrategyType("PREPROCESS");
        preprocessBinding.setStrategyVersionId(77L);
        when(strategyBindingDbService.getByKbAndType(2L, "PREPROCESS")).thenReturn(preprocessBinding);
        when(strategyVersionDbService.getById(77L)).thenReturn(versionOf(77L, "PREPROCESS", "preproc-strict", "v2"));
        when(strategyBindingDbService.getByKbAndType(2L, "CHUNK")).thenReturn(null);

        KnowledgeBaseVO vo = crudService.detail(2L);

        assertEquals(77L, vo.getPreprocessStrategyVersionId());
        assertEquals("preproc-strict-v2", vo.getPreprocessStrategyVersion());
        assertNull(vo.getChunkStrategyVersionId());
    }

    /**
     * 详情要填满三种策略的绑定摘要，向量化那一路同样不能漏。
     *
     * <p>守护 {@code fillStrategyBindings} 的三次调用：漏掉 EMBED 会让前端显示未绑定。
     */
    @Test
    void detailShouldFillEmbedBindingSummary() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        WbStrategyBinding embedBinding = new WbStrategyBinding();
        embedBinding.setId(3L);
        embedBinding.setKnowledgeBaseId(2L);
        embedBinding.setStrategyType("EMBED");
        embedBinding.setStrategyVersionId(88L);
        when(strategyBindingDbService.getByKbAndType(2L, "EMBED")).thenReturn(embedBinding);
        when(strategyVersionDbService.getById(88L)).thenReturn(versionOf(88L, "EMBED", "embed-default", "v3"));

        KnowledgeBaseVO vo = crudService.detail(2L);

        assertEquals(88L, vo.getEmbedStrategyVersionId());
        assertEquals("embed-default-v3", vo.getEmbedStrategyVersion());
    }

    /**
     * 分页时三种策略的绑定摘要都按批量方式回填，缺版本的行留空。
     *
     * <p>守护 {@code fillStrategyBindings(records)}/{@code fillBindingForType}：只填一种类型、
     * 或把失效版本硬填成半截数据即失败。
     */
    @Test
    void pageShouldFillBindingsForEachTypeAndSkipMissingVersion() {
        Page<WbKnowledgeBase> page = kbPage(kb(7L, 1), kb(8L, 1), kb(9L, 1));
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, null, null,
                KnowledgeBaseSort.DEFAULT, ME)).thenReturn(page);
        when(kbFileResultDbService.countGroupByKb(List.of(7L, 8L, 9L))).thenReturn(Map.of(7L, 2L));
        when(strategyBindingDbService.listActiveByTypeAndKbIds("PREPROCESS", List.of(7L, 8L, 9L)))
                .thenReturn(List.of(bindingOf(7L, "PREPROCESS", 11L)));
        when(strategyBindingDbService.listActiveByTypeAndKbIds("CHUNK", List.of(7L, 8L, 9L)))
                .thenReturn(List.of(bindingOf(7L, "CHUNK", 22L), bindingOf(9L, "CHUNK", 999L)));
        when(strategyBindingDbService.listActiveByTypeAndKbIds("EMBED", List.of(7L, 8L, 9L)))
                .thenReturn(List.of(bindingOf(7L, "EMBED", 33L)));
        when(strategyVersionDbService.listByIds(List.of(11L)))
                .thenReturn(List.of(versionOf(11L, "PREPROCESS", "preproc-strict", "v2")));
        when(strategyVersionDbService.listByIds(List.of(22L, 999L)))
                .thenReturn(List.of(versionOf(22L, "CHUNK", "chunk-hybrid", "v1")));
        when(strategyVersionDbService.listByIds(List.of(33L)))
                .thenReturn(List.of(versionOf(33L, "EMBED", "embed-default", "v1")));

        List<KnowledgeBaseVO> records = crudService.page(kbQuery(null, null, null)).getRecords();

        KnowledgeBaseVO bound = records.get(0);
        assertEquals(11L, bound.getPreprocessStrategyVersionId());
        assertEquals("preproc-strict-v2", bound.getPreprocessStrategyVersion());
        assertEquals(22L, bound.getChunkStrategyVersionId());
        assertEquals("chunk-hybrid-v1", bound.getChunkStrategyVersion());
        assertEquals(33L, bound.getEmbedStrategyVersionId());
        assertEquals("embed-default-v1", bound.getEmbedStrategyVersion());
        assertEquals(2L, bound.getDocumentCount());
        // 无绑定的库留空
        assertNull(records.get(1).getChunkStrategyVersionId());
        assertNull(records.get(1).getEmbedStrategyVersionId());
        // 绑定指向已失效版本：留空而不是半截数据
        assertNull(records.get(2).getChunkStrategyVersionId());
        assertNull(records.get(2).getChunkStrategyVersion());
    }

    /**
     * 分页没有绑定行时不再去查策略版本。
     *
     * <p>守护 {@code fillBindingForType} 的空集短路：多打一次 {@code listByIds(空)} 是白查询。
     */
    @Test
    void pageWithoutBindingsShouldNotLoadVersions() {
        when(knowledgeBaseDbService.pageByCondition(1L, 10L, null, null,
                KnowledgeBaseSort.DEFAULT, ME))
                .thenReturn(kbPage(kb(7L, 1)));
        when(kbFileResultDbService.countGroupByKb(List.of(7L))).thenReturn(Map.of());
        when(strategyBindingDbService.listActiveByTypeAndKbIds(any(), any())).thenReturn(List.of());

        KnowledgeBaseVO vo = crudService.page(kbQuery(null, null, null)).getRecords().getFirst();

        assertNull(vo.getChunkStrategyVersionId());
        verify(strategyVersionDbService, never()).listByIds(any());
    }
}
