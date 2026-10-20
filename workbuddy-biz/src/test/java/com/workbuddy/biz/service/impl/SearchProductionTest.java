package com.workbuddy.biz.service.impl;

import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbRetrievalRun;
import com.workbuddy.common.dto.response.retrieval.SearchHitVO;
import com.workbuddy.common.dto.response.retrieval.SearchVO;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.worker.indexing.ComboSnapshot;
import com.workbuddy.worker.indexing.IndexRow;
import com.workbuddy.worker.indexing.search.FullTextHit;
import com.workbuddy.worker.indexing.search.VectorQuery;
import com.workbuddy.worker.retrieval.RetrievalRuleSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生产检索用例：回退链（三级→基线）/ HYBRID RRF 融合排序 / 纯全文跳过向量化 / 父片展开 /
 * 预留能力引擎拒执行 / 未发布 40446 / 响应外壳与运行记录。
 *
 * <p>测试台与运行记录用例分别在 {@link SearchBenchmarkTest}、{@link SearchRunQueryTest}；
 * 装配与夹具数据见 {@link SearchTestFixture}。
 */
@ExtendWith(MockitoExtension.class)
class SearchProductionTest {

    private SearchTestFixture fixture;

    private SearchServiceImpl service;

    @BeforeEach
    void setUp() {
        fixture = new SearchTestFixture();
        service = fixture.service();
    }

    /**
     * 生产检索回退链第三级：版本无默认规则、知识库无默认规则 → 引擎基线；纯全文规则不查向量。
     *
     * <p>守护 {@code search}：回退链断在中间或纯全文仍查 EMBED 策略行即失败。
     */
    @Test
    void productionSearchShouldFallbackToBaselineWhenNoRuleBound() {
        // 回退链第三级：版本行无规则、kb 无默认 → 引擎基线；纯全文规则跳过向量化
        fixture.stubPublishedSet(1L, 5L, 20L);
        fixture.stubOnlineVersion(20L, "v2");
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(fixture.knowledgeBase(1L));
        when(fixture.retrievalRuleResolver.validate(anyString())).thenReturn(fixture.fullTextSpec());
        fixture.stubFullText("kb_1_v2", fixture.fullTextHit("c1"));

        SearchVO vo = service.search(1L, fixture.request());

        assertEquals("baseline-v0", vo.getRuleNameVersion());
        assertEquals("v2", vo.getVersionNo());
        assertEquals(1, vo.getHits().size());
        assertEquals("全文-c1", vo.getHits().getFirst().getContent());
        assertNull(vo.getHits().getFirst().getScore());
        verify(fixture.milvusIndexPort, never()).searchVector(anyString(), any());
        verify(fixture.retrievalRunDbService, never()).save(any());
    }

    /**
     * 生产检索（混合通道 + 父片展开）：RRF 融合、同父片去重取最高分、父片全文替换并标 isParent。
     *
     * <p>守护 {@code orderHits} 的融合分支与 {@code expandParent} 的主路径：
     * 同父片双子片未去重、父片全文未替换、isParent 未标即失败。
     */
    @Test
    void productionSearchShouldFuseHybridAndExpandParents() {
        WbIndexSet set = new WbIndexSet();
        set.setCurrentPublishedVersionId(30L);
        when(fixture.indexSetDbService.getByKb(9L)).thenReturn(set);
        fixture.stubOnlineVersion(30L, "v9").setDefaultRuleId(500L);
        when(fixture.strategyVersionDbService.getById(500L)).thenReturn(fixture.ruleRow());
        RetrievalRuleSpec spec = fixture.hybridSpec();
        spec.getPostprocess().setMode(RetrievalRuleSpec.MODE_PARENT_EXPAND);
        when(fixture.retrievalRuleResolver.validate(anyString())).thenReturn(spec);
        FullTextHit child1 = FullTextHit.builder().chunkId("c1").content("子片一").documentId(9L)
                .titlePath("旧路径").parentChunkId("p1").build();
        FullTextHit child2 = FullTextHit.builder().chunkId("c2").content("子片二").documentId(9L)
                .titlePath("旧路径").parentChunkId("p1").build();
        FullTextHit standalone = FullTextHit.builder().chunkId("c3").content("独立片").documentId(9L)
                .titlePath(null).parentChunkId(null).build();
        fixture.stubFullText("kb_9_v9", child1, child2, standalone);
        IndexRow parent = new IndexRow();
        parent.setChunkId("p1");
        parent.setContent("整段全文");
        parent.setTitlePath("第一章");
        parent.setDocumentId(99L);
        parent.setParentChunkId(null);
        parent.setSourceElementIds("[\"e1\"]");
        fixture.stubParents("kb_9_v9", List.of("p1"), parent);

        SearchVO vo = service.search(9L, fixture.request());

        assertEquals(2, vo.getHits().size());
        SearchHitVO expanded = vo.getHits().get(0);
        assertTrue(expanded.getIsParent());
        assertEquals("p1", expanded.getChunkId());
        assertEquals("整段全文", expanded.getContent());
        assertEquals("第一章", expanded.getTitlePath());
        assertEquals("[\"e1\"]", expanded.getSourceElementIds());
        // 父片行自身的文档与父片指向随替换一起更新（文档取父片的 99，父片为空）
        assertEquals(99L, expanded.getDocumentId());
        assertNull(expanded.getParentChunkId());
        SearchHitVO plain = vo.getHits().get(1);
        assertEquals("c3", plain.getChunkId());
        assertFalse(plain.getIsParent());
        // 生产检索默认不留痕（开关关闭）
        verify(fixture.retrievalRunDbService, never()).save(any(WbRetrievalRun.class));
    }

    /**
     * 纯全文通道：不走向量化（EMBED 策略行不被查询），命中按原顺序直通。
     *
     * <p>守护 {@code search} 的单通道路径与 {@code embedQuery} 的跳过逻辑：
     * 纯全文还查 EMBED 策略行即失败（多余的模型依赖）。
     */
    @Test
    void fullTextOnlyChannelShouldSkipEmbeddingAndPassThrough() {
        WbIndexSet set = new WbIndexSet();
        set.setCurrentPublishedVersionId(30L);
        when(fixture.indexSetDbService.getByKb(9L)).thenReturn(set);
        fixture.stubOnlineVersion(30L, "v9");
        when(fixture.retrievalRuleResolver.validate(anyString())).thenReturn(fixture.fullTextSpec());
        FullTextHit hit = FullTextHit.builder().chunkId("c1").content("全文-c1").documentId(9L)
                .parentChunkId(null).build();
        fixture.stubFullText("kb_9_v9", hit);

        SearchVO vo = service.search(9L, fixture.request());

        assertEquals(1, vo.getHits().size());
        assertEquals("全文-c1", vo.getHits().get(0).getContent());
        assertNull(vo.getHits().get(0).getScore());
        verify(fixture.strategyVersionDbService, never())
                .getByTypeAndNameAndVersion(anyString(), anyString(), anyString());
        verify(fixture.milvusIndexPort, never()).searchVector(anyString(), any(VectorQuery.class));
    }

    /**
     * 生产检索的响应外壳与命中字段逐列搬运。
     *
     * <p>守护 {@code search}/{@code toVO}：查询回显、耗时、命中定位字段漏搬都会让测试台对不上标注。
     */
    @Test
    void productionSearchShouldFillResponseEnvelope() {
        fixture.stubProduction(9L, fixture.comboAll(), null);
        when(fixture.retrievalRuleResolver.validate(anyString())).thenReturn(fixture.fullTextSpec());
        FullTextHit hit = FullTextHit.builder().chunkId("c1").content("正文").documentId(9L)
                .titlePath("第一章").contentType("PARAGRAPH").parentChunkId("p1")
                .sourceElementIds("[\"e1\"]").build();
        fixture.stubFullText("kb_9_v9", hit);

        SearchVO vo = service.search(9L, fixture.request());

        assertEquals("保证金", vo.getQuery());
        assertEquals("baseline-v0", vo.getRuleNameVersion());
        assertEquals("v9", vo.getVersionNo());
        assertNotNull(vo.getElapsedMs());
        assertTrue(vo.getElapsedMs() >= 0 && vo.getElapsedMs() < 60_000L, String.valueOf(vo.getElapsedMs()));
        SearchHitVO hitVo = vo.getHits().getFirst();
        assertEquals("c1", hitVo.getChunkId());
        assertEquals("正文", hitVo.getContent());
        assertEquals("第一章", hitVo.getTitlePath());
        assertEquals("[\"e1\"]", hitVo.getSourceElementIds());
        assertEquals(9L, hitVo.getDocumentId());
        assertEquals("PARAGRAPH", hitVo.getContentType());
        assertEquals("p1", hitVo.getParentChunkId());
        assertNull(hitVo.getScore());
        assertFalse(hitVo.getIsParent());
    }

    /**
     * 生产检索开关打开时落运行记录：快照与定位字段逐列写入。
     *
     * <p>守护 {@code recordRun}：漏写任一列都会让对比回放读不到快照。
     */
    @Test
    void productionSearchShouldRecordRunWhenSwitchOn() {
        fixture.stubProduction(9L, fixture.comboAll(), null);
        when(fixture.runSettings.recordProduction()).thenReturn(true);
        when(fixture.retrievalRuleResolver.validate(anyString())).thenReturn(fixture.fullTextSpec());
        fixture.stubFullText("kb_9_v9", fixture.fullTextHit("c1"));
        fixture.stubRunSaveAssignsId(900L);

        SearchVO vo = service.search(9L, fixture.request());

        ArgumentCaptor<WbRetrievalRun> captor = ArgumentCaptor.forClass(WbRetrievalRun.class);
        verify(fixture.retrievalRunDbService).save(captor.capture());
        WbRetrievalRun run = captor.getValue();
        assertEquals(9L, run.getKbId());
        assertEquals(30L, run.getVersionId());
        assertEquals("v9", run.getVersionNo());
        assertNull(run.getRuleId());
        assertEquals("baseline-v0", run.getRuleNameVersion());
        assertEquals("保证金", run.getQuery());
        assertTrue(run.getResultSnapshot().contains("\"c1\""), run.getResultSnapshot());
        assertEquals(vo.getElapsedMs().intValue(), run.getElapsedMs());
        assertEquals(900L, vo.getRunId());
    }

    /**
     * 版本行带默认规则时：运行记录要写规则 ID 与 name-version。
     *
     * <p>守护 {@code recordRun} 的规则列：漏写 ruleId 会让对比回放找不到规则来源。
     */
    @Test
    void productionSearchWithVersionDefaultRuleShouldRecordRuleId() {
        fixture.stubProduction(9L, fixture.comboAll(), 500L);
        when(fixture.strategyVersionDbService.getById(500L)).thenReturn(fixture.ruleRow());
        when(fixture.runSettings.recordProduction()).thenReturn(true);
        when(fixture.retrievalRuleResolver.validate(anyString())).thenReturn(fixture.fullTextSpec());
        fixture.stubFullText("kb_9_v9", fixture.fullTextHit("c1"));
        when(fixture.retrievalRunDbService.save(any(WbRetrievalRun.class))).thenAnswer(inv -> true);

        service.search(9L, fixture.request());

        ArgumentCaptor<WbRetrievalRun> captor = ArgumentCaptor.forClass(WbRetrievalRun.class);
        verify(fixture.retrievalRunDbService).save(captor.capture());
        assertEquals(500L, captor.getValue().getRuleId());
        assertEquals("hybrid-rrf-k60-top10-v1", captor.getValue().getRuleNameVersion());
    }

    /**
     * 组合快照缺环节策略维度：查询向量化中止，向量通道降级为空、全文照跑。
     *
     * <p>守护 {@code embedQuery} 的快照不全分支：拿空维度去查 EMBED 策略会拼出错误策略名。
     */
    @Test
    void hybridShouldDegradeVectorWhenComboIncomplete() {
        fixture.stubHybridProduction(new ComboSnapshot(), 500L);
        fixture.stubFullText("kb_9_v9", fixture.fullTextHit("c1"));

        SearchVO vo = service.search(9L, fixture.request());

        assertEquals(1, vo.getHits().size());
        assertEquals("c1", vo.getHits().getFirst().getChunkId());
        verify(fixture.milvusIndexPort, never()).searchVector(anyString(), any());
    }

    /**
     * EMBED 策略版本行不存在：查询向量化中止、向量通道降级。
     *
     * <p>守护 {@code embedQuery} 的策略行缺失分支。
     */
    @Test
    void hybridShouldDegradeVectorWhenEmbedRowMissing() {
        fixture.stubHybridProduction(fixture.comboAll(), 500L);
        when(fixture.strategyVersionDbService.getByTypeAndNameAndVersion("EMBED", "embed-default", "v1"))
                .thenReturn(null);
        fixture.stubFullText("kb_9_v9", fixture.fullTextHit("c1"));

        SearchVO vo = service.search(9L, fixture.request());

        assertEquals(1, vo.getHits().size());
        verify(fixture.milvusIndexPort, never()).searchVector(anyString(), any());
    }

    /**
     * 策略可解析但模型调用层未接入：输入拼好后中止，不得用空向量去检索。
     *
     * <p>守护 {@code embedQuery} 的模型缺口分支：返回空表而非 null 会触发一次空向量检索。
     */
    @Test
    void hybridShouldStopAtModelGapAfterBuildingInput() {
        fixture.stubHybridProduction(fixture.comboAll(), 500L);
        WbPipelineStrategyVersion embedRow = fixture.stubEmbedRow("{\"model\":\"text-embedding-v4\"}");
        fixture.stubParsedEmbedStrategy(embedRow.getConfigSnapshot(), "text-embedding-v4", " ");
        fixture.stubFullText("kb_9_v9", fixture.fullTextHit("c1"));

        SearchVO vo = service.search(9L, fixture.request());

        assertEquals(1, vo.getHits().size());
        verify(fixture.milvusIndexPort, never()).searchVector(anyString(), any());
    }

    /**
     * 混合通道：同 chunk 的重复全文命中按一条返回（按 chunkId 归并）。
     *
     * <p>守护 {@code orderHits} 的两处归并：归并写错会返回重复命中。
     */
    @Test
    void hybridShouldDedupeDuplicateFullTextHits() {
        fixture.stubHybridProduction(fixture.comboAll(), 500L);
        FullTextHit dup1 = FullTextHit.builder().chunkId("c1").content("一").documentId(9L).build();
        FullTextHit dup2 = FullTextHit.builder().chunkId("c1").content("二").documentId(9L).build();
        fixture.stubFullText("kb_9_v9", dup1, dup2);

        SearchVO vo = service.search(9L, fixture.request());

        assertEquals(1, vo.getHits().size());
        assertEquals("一", vo.getHits().getFirst().getContent());
        verify(fixture.milvusIndexPort, never()).searchVector(anyString(), any());
    }

    /**
     * 父片展开：命中都没有父片时原样返回；父片行重复时按一条归并。
     *
     * <p>守护 {@code expandParent} 的空父片短路与父片行归并：短路写错会把命中清空。
     */
    @Test
    void expandParentShouldShortCircuitWithoutParents() {
        fixture.stubHybridRule(fixture.comboAll(), 500L);
        RetrievalRuleSpec spec = fixture.fullTextSpec();
        spec.getPostprocess().setMode(RetrievalRuleSpec.MODE_PARENT_EXPAND);
        when(fixture.retrievalRuleResolver.validate(anyString())).thenReturn(spec);
        fixture.stubFullText("kb_9_v9",
                FullTextHit.builder().chunkId("c1").content("无父片").documentId(9L).build());

        SearchVO vo = service.search(9L, fixture.request());

        assertEquals(1, vo.getHits().size());
        assertEquals("无父片", vo.getHits().getFirst().getContent());
        verify(fixture.milvusIndexPort, never()).queryChunkByIds(anyString(), any());
    }

    /**
     * 父片展开：父片行重复（同 chunkId）时按一条归并，命中仍替换为父片全文。
     *
     * <p>守护 {@code expandParent} 的父片行归并：归并写错会取到空父片、展开失效。
     */
    @Test
    void expandParentShouldMergeDuplicateParentRows() {
        fixture.stubHybridRule(fixture.comboAll(), 500L);
        RetrievalRuleSpec spec = fixture.fullTextSpec();
        spec.getPostprocess().setMode(RetrievalRuleSpec.MODE_PARENT_EXPAND);
        when(fixture.retrievalRuleResolver.validate(anyString())).thenReturn(spec);
        fixture.stubFullText("kb_9_v9", FullTextHit.builder().chunkId("c1").content("子片").documentId(9L)
                .parentChunkId("p1").build());
        IndexRow parent = new IndexRow();
        parent.setChunkId("p1");
        parent.setContent("父片全文");
        parent.setDocumentId(9L);
        IndexRow duplicate = new IndexRow();
        duplicate.setChunkId("p1");
        duplicate.setContent("父片全文副本");
        duplicate.setDocumentId(9L);
        fixture.stubParents("kb_9_v9", List.of("p1"), parent, duplicate);

        SearchVO vo = service.search(9L, fixture.request());

        assertEquals(1, vo.getHits().size());
        assertEquals("p1", vo.getHits().getFirst().getChunkId());
        assertEquals("父片全文", vo.getHits().getFirst().getContent());
        assertTrue(vo.getHits().getFirst().getIsParent());
    }

    /**
     * 生产检索：知识库没有在线版本按 40446 拒绝。
     *
     * <p>守护 {@code search}：无在线版本仍执行即失败。
     */
    @Test
    void productionWithoutPublishedShouldThrow40446() {
        when(fixture.indexSetDbService.getByKb(1L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.search(1L, fixture.request()));

        assertEquals(ErrorCode.INDEX_NOT_PUBLISHED, e.getErrorCode());
    }

    /**
     * 集合指针存在但版本行已不存在：按 40446 拒绝。
     *
     * <p>守护 {@code resolveVersion} 的产线兜底：指针悬空时不能拿 null 版本继续检索。
     */
    @Test
    void productionSearchWhenPublishedVersionRowMissingShouldReject() {
        WbIndexSet set = new WbIndexSet();
        set.setCurrentPublishedVersionId(30L);
        when(fixture.indexSetDbService.getByKb(9L)).thenReturn(set);
        when(fixture.indexVersionDbService.getById(30L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.search(9L, fixture.request()));

        assertEquals(ErrorCode.INDEX_NOT_PUBLISHED, e.getErrorCode());
    }
}
