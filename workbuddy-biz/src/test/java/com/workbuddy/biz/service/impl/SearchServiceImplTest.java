package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbRetrievalRunDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.support.RetrievalRuleResolver;
import com.workbuddy.biz.service.support.RetrievalRunSettings;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbRetrievalRun;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.dto.request.retrieval.SearchDTO;
import com.workbuddy.common.dto.response.retrieval.SearchHitVO;
import com.workbuddy.common.dto.response.retrieval.SearchVO;
import com.workbuddy.common.enums.index.IndexVersionStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.worker.embedding.strategy.EmbedStrategy;
import com.workbuddy.worker.embedding.strategy.EmbedStrategyParser;
import com.workbuddy.worker.indexing.ComboSnapshot;
import com.workbuddy.worker.indexing.IndexRow;
import com.workbuddy.worker.indexing.MilvusIndexPort;
import com.workbuddy.worker.indexing.search.FullTextHit;
import com.workbuddy.worker.indexing.search.FullTextQuery;
import com.workbuddy.worker.indexing.search.VectorHit;
import com.workbuddy.worker.indexing.search.VectorQuery;
import com.workbuddy.worker.retrieval.RetrievalRuleSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索引擎编排单测：生产回退链（三级→基线）/ 测试台显式版本+规则（含冻结集）/
 * HYBRID RRF 融合排序 / 纯全文跳过向量化 / 父片展开 / 预留能力引擎拒执行 / 未发布 40446 /
 * 测试台落运行记录 / 勾选对比回放快照。
 */
@ExtendWith(MockitoExtension.class)
class SearchServiceImplTest {

    @Mock
    private WbIndexSetDbService indexSetDbService;
    @Mock
    private WbIndexVersionDbService indexVersionDbService;
    @Mock
    private WbKnowledgeBaseDbService knowledgeBaseDbService;
    @Mock
    private WbPipelineStrategyVersionDbService strategyVersionDbService;
    @Mock
    private MilvusIndexPort milvusIndexPort;
    @Mock
    private EmbedStrategyParser embedStrategyParser;
    @Mock
    private RetrievalRuleResolver retrievalRuleResolver;
    @Mock
    private WbRetrievalRunDbService retrievalRunDbService;
    @Mock
    private RetrievalRunSettings runSettings;

    private SearchServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SearchServiceImpl(indexSetDbService, indexVersionDbService, knowledgeBaseDbService,
                strategyVersionDbService, milvusIndexPort, embedStrategyParser,
                retrievalRuleResolver, retrievalRunDbService, runSettings);
    }

    private WbIndexVersion versionRow(Long id, String versionNo, String status, ComboSnapshot combo) {
        WbIndexVersion version = new WbIndexVersion();
        version.setId(id);
        version.setVersionNo(versionNo);
        version.setStatus(status);
        version.setComboSnapshot(JsonUtil.toJsonStr(combo));
        return version;
    }

    private ComboSnapshot comboAll() {
        return ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
    }

    private WbPipelineStrategyVersion ruleRow() {
        WbPipelineStrategyVersion row = new WbPipelineStrategyVersion();
        row.setId(500L);
        row.setType("RETRIEVAL");
        row.setName("hybrid-rrf-k60-top10");
        row.setVersion("v1");
        row.setConfigSnapshot("{\"channel\":\"HYBRID\"}");
        return row;
    }

    private SearchDTO request() {
        SearchDTO request = new SearchDTO();
        request.setQuery("保证金");
        return request;
    }

    private FullTextHit fullTextHit(String chunkId) {
        return FullTextHit.builder().chunkId(chunkId).content("全文-" + chunkId)
                .documentId(10L).parentChunkId(null).build();
    }

    private VectorHit vectorHit(String chunkId, String parentChunkId) {
        return VectorHit.builder().chunkId(chunkId).content("向量-" + chunkId)
                .documentId(10L).parentChunkId(parentChunkId).score(0.9F).build();
    }

    /**
     * 生产检索回退链第三级：版本无默认规则、知识库无默认规则 → 引擎基线；纯全文规则不查向量。
     *
     * <p>守护 {@code search}：回退链断在中间或纯全文仍查 EMBED 策略行即失败。
     */
    @Test
    void productionSearchShouldFallbackToBaselineWhenNoRuleBound() {
        // 回退链第三级：版本行无规则、kb 无默认 → 引擎基线；纯全文规则跳过向量化
        WbIndexSet set = new WbIndexSet();
        set.setId(5L);
        set.setKnowledgeBaseId(1L);
        set.setCurrentPublishedVersionId(20L);
        when(indexSetDbService.getByKb(1L)).thenReturn(set);
        WbIndexVersion version = versionRow(20L, "v2", IndexVersionStatus.ONLINE.name(), comboAll());
        when(indexVersionDbService.getById(20L)).thenReturn(version);
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(1L);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(kb);
        RetrievalRuleSpec spec = new RetrievalRuleSpec();
        spec.setChannel(RetrievalRuleSpec.CHANNEL_FULLTEXT);
        when(retrievalRuleResolver.validate(anyString())).thenReturn(spec);
        when(milvusIndexPort.searchFullText(eq("kb_1_v2"), any(FullTextQuery.class)))
                .thenReturn(List.of(fullTextHit("c1")));

        SearchVO vo = service.search(1L, request());

        assertEquals("baseline-v0", vo.getRuleNameVersion());
        assertEquals("v2", vo.getVersionNo());
        assertEquals(1, vo.getHits().size());
        assertEquals("全文-c1", vo.getHits().getFirst().getContent());
        assertNull(vo.getHits().getFirst().getScore());
        verify(milvusIndexPort, never()).searchVector(anyString(), any());
        verify(retrievalRunDbService, never()).save(any());
    }

    /**
     * 测试台：显式版本 + 显式规则，HYBRID 按 RRF 融合并落运行记录（模型接入后恢复）。
     *
     * <p>守护 {@code search}：融合序或记录字段错即失败。
     */
    @Test
    // TODO 模型调用层待接入：向量通道当前恒降级，本用例待恢复
    @Disabled("模型调用层待接入：向量通道恒降级")
    void benchSearchShouldUseExplicitVersionAndRuleAndRecordRun() {
        SearchDTO request = request();
        request.setVersionId(30L);
        request.setRuleId(500L);
        WbIndexVersion version = versionRow(30L, "v9", IndexVersionStatus.READY.name(), comboAll());
        when(indexVersionDbService.getById(30L)).thenReturn(version);
        WbPipelineStrategyVersion rule = ruleRow();
        when(strategyVersionDbService.getById(500L)).thenReturn(rule);
        RetrievalRuleSpec spec = new RetrievalRuleSpec();
        spec.setChannel(RetrievalRuleSpec.CHANNEL_HYBRID);
        when(retrievalRuleResolver.validate(rule.getConfigSnapshot())).thenReturn(spec);
        // 查询向量化：跟随集合 EMBED 策略
        WbPipelineStrategyVersion embedRow = new WbPipelineStrategyVersion();
        embedRow.setConfigSnapshot("{\"model\":\"text-embedding-v4\"}");
        when(strategyVersionDbService.getByTypeAndNameAndVersion("EMBED", "embed-default", "v1"))
                .thenReturn(embedRow);
        EmbedStrategy embedStrategy = new EmbedStrategy();
        embedStrategy.setModel("text-embedding-v4");
        embedStrategy.setQueryTemplate("{query}");
        embedStrategy.setTimeoutMs(5000);
        when(embedStrategyParser.parse("{\"model\":\"text-embedding-v4\"}")).thenReturn(embedStrategy);
        when(milvusIndexPort.searchFullText(eq("kb_1_v9"), any(FullTextQuery.class)))
                .thenReturn(List.of(fullTextHit("c1"), fullTextHit("c2")));
        when(milvusIndexPort.searchVector(eq("kb_1_v9"), any(VectorQuery.class)))
                .thenReturn(List.of(vectorHit("c2", null), vectorHit("c3", null)));
        when(retrievalRunDbService.save(any(WbRetrievalRun.class))).thenAnswer(inv -> {
            inv.<WbRetrievalRun>getArgument(0).setId(900L);
            return true;
        });

        SearchVO vo = service.search(1L, request);

        // 融合序：c2(1/61+1/62) > c1(1/61) > c3(1/62)
        assertEquals(List.of("c2", "c1", "c3"), vo.getHits().stream().map(SearchHitVO::getChunkId).toList());
        assertEquals(1.0 / 61 + 1.0 / 62, vo.getHits().getFirst().getScore(), 1e-9);
        assertEquals(900L, vo.getRunId());
        assertEquals("v9", vo.getVersionNo());
        assertEquals("hybrid-rrf-k60-top10-v1", vo.getRuleNameVersion());
        verify(retrievalRunDbService).save(any(WbRetrievalRun.class));
    }

    /**
     * 父片展开：子片命中 → 取集合内父片全文替换并标 isParent（模型接入后恢复）。
     *
     * <p>守护 {@code expandParent}：未替换父片全文即失败。
     */
    @Test
    // TODO 模型调用层待接入：向量通道当前恒降级，本用例待恢复
    @Disabled("模型调用层待接入：向量通道恒降级")
    void parentExpandShouldFetchParentFromCollection() {
        SearchDTO request = request();
        request.setRuleId(500L);
        WbIndexVersion version = versionRow(30L, "v9", IndexVersionStatus.READY.name(), comboAll());
        when(indexVersionDbService.getById(30L)).thenReturn(version);
        WbPipelineStrategyVersion rule = ruleRow();
        when(strategyVersionDbService.getById(500L)).thenReturn(rule);
        RetrievalRuleSpec spec = new RetrievalRuleSpec();
        spec.setChannel(RetrievalRuleSpec.CHANNEL_VECTOR);
        spec.getPostprocess().setMode(RetrievalRuleSpec.MODE_PARENT_EXPAND);
        when(retrievalRuleResolver.validate(rule.getConfigSnapshot())).thenReturn(spec);
        WbPipelineStrategyVersion embedRow = new WbPipelineStrategyVersion();
        embedRow.setConfigSnapshot("{\"model\":\"text-embedding-v4\"}");
        when(strategyVersionDbService.getByTypeAndNameAndVersion("EMBED", "embed-default", "v1"))
                .thenReturn(embedRow);
        EmbedStrategy embedStrategy = new EmbedStrategy();
        embedStrategy.setModel("text-embedding-v4");
        embedStrategy.setQueryTemplate("{query}");
        embedStrategy.setTimeoutMs(5000);
        when(embedStrategyParser.parse("{\"model\":\"text-embedding-v4\"}")).thenReturn(embedStrategy);
        when(milvusIndexPort.searchVector(eq("kb_1_v9"), any(VectorQuery.class)))
                .thenReturn(List.of(vectorHit("c-12", "c-1")));
        IndexRow parent = new IndexRow();
        parent.setChunkId("c-1");
        parent.setContent("第三节 保证金（父片全文）");
        parent.setDocumentId(10L);
        when(milvusIndexPort.queryChunkByIds(eq("kb_1_v9"), eq(List.of("c-1")))).thenReturn(List.of(parent));

        SearchVO vo = service.search(1L, request);

        assertEquals(1, vo.getHits().size());
        assertEquals("c-1", vo.getHits().getFirst().getChunkId());
        assertEquals("第三节 保证金（父片全文）", vo.getHits().getFirst().getContent());
        assertTrue(vo.getHits().getFirst().getIsParent());
    }

    /**
     * EMBED 策略行快照损坏：查询向量化中止、向量通道降级为空，全文照跑且不造默认策略。
     *
     * <p>守护 {@code embedQuery}：抛异常给用户或伪造默认策略即失败。
     */
    @Test
    void corruptEmbedStrategyRowShouldDegradeVectorChannel() {
        // EMBED 策略版本行配置快照坏：查询向量化中止（向量通道降级为空），全文通道照跑，不炸不造默认
        SearchDTO request = request();
        request.setVersionId(30L);
        request.setRuleId(500L);
        WbIndexVersion version = versionRow(30L, "v9", IndexVersionStatus.READY.name(), comboAll());
        when(indexVersionDbService.getById(30L)).thenReturn(version);
        WbPipelineStrategyVersion rule = ruleRow();
        when(strategyVersionDbService.getById(500L)).thenReturn(rule);
        RetrievalRuleSpec spec = new RetrievalRuleSpec();
        spec.setChannel(RetrievalRuleSpec.CHANNEL_HYBRID);
        when(retrievalRuleResolver.validate(rule.getConfigSnapshot())).thenReturn(spec);
        WbPipelineStrategyVersion embedRow = new WbPipelineStrategyVersion();
        embedRow.setConfigSnapshot("{oops");
        when(strategyVersionDbService.getByTypeAndNameAndVersion("EMBED", "embed-default", "v1"))
                .thenReturn(embedRow);
        when(embedStrategyParser.parse("{oops")).thenThrow(
                new IllegalStateException("向量化策略快照为空或已损坏，无法解析, snapshot={oops"));
        when(milvusIndexPort.searchFullText(eq("kb_1_v9"), any(FullTextQuery.class)))
                .thenReturn(List.of(fullTextHit("c1")));
        when(retrievalRunDbService.save(any(WbRetrievalRun.class))).thenReturn(true);

        SearchVO vo = service.search(1L, request);

        assertEquals(1, vo.getHits().size());
        assertEquals("c1", vo.getHits().getFirst().getChunkId());
        verify(milvusIndexPort, never()).searchVector(anyString(), any());
    }

    /**
     * 引擎防御：预留能力被规则选中时拒绝执行（注册侧已拦，双保险防历史数据）。
     *
     * <p>守护 {@code search}：预留能力被执行即失败。
     */
    @Test
    void lockedCapabilityRuleShouldBeRejectedAtEngine() {
        SearchDTO request = request();
        request.setVersionId(30L);
        request.setRuleId(500L);
        WbIndexVersion version = versionRow(30L, "v9", IndexVersionStatus.READY.name(), comboAll());
        when(indexVersionDbService.getById(30L)).thenReturn(version);
        WbPipelineStrategyVersion rule = ruleRow();
        when(strategyVersionDbService.getById(500L)).thenReturn(rule);
        when(retrievalRuleResolver.validate(rule.getConfigSnapshot()))
                .thenThrow(new KnowledgeException(ErrorCode.RETRIEVAL_CAPABILITY_LOCKED, "该检索能力尚未启用: 加权融合"));

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.search(1L, request));

        assertEquals(ErrorCode.RETRIEVAL_CAPABILITY_LOCKED, e.getErrorCode());
        verify(milvusIndexPort, never()).searchVector(anyString(), any());
        verify(milvusIndexPort, never()).searchFullText(anyString(), any());
    }

    /**
     * 测试台入参：只给版本或只给规则按 40001 拒绝（两者必须成对）。
     *
     * <p>守护 {@code search}：半套参数放行即失败。
     */
    @Test
    void benchWithPartialIdsShouldReject() {
        SearchDTO request = request();
        request.setVersionId(30L);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.search(1L, request));

        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    /**
     * 生产检索：知识库没有在线版本按 40446 拒绝。
     *
     * <p>守护 {@code search}：无在线版本仍执行即失败。
     */
    @Test
    void productionWithoutPublishedShouldThrow40446() {
        when(indexSetDbService.getByKb(1L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.search(1L, request()));

        assertEquals(ErrorCode.INDEX_NOT_PUBLISHED, e.getErrorCode());
    }

    /**
     * 对比回放：直接读快照 JSON，不再执行检索。
     *
     * <p>守护 {@code compareRuns}：回放时重跑检索或快照字段错即失败。
     */
    @Test
    void compareRunsShouldReplaySnapshotsWithoutReExecuting() {
        WbRetrievalRun run = new WbRetrievalRun();
        run.setId(1L);
        run.setKbId(1L);
        run.setQuery("保证金");
        run.setRuleNameVersion("hybrid-rrf-k60-top10-v1");
        run.setVersionNo("v2");
        run.setElapsedMs(12);
        run.setResultSnapshot("[{\"chunkId\":\"c1\",\"content\":\"快照内容\"}]");
        when(retrievalRunDbService.listByIdsOrdered(List.of(1L))).thenReturn(List.of(run));

        List<SearchVO> vos = service.compareRuns(1L, List.of(1L));

        assertEquals(1, vos.size());
        assertEquals("保证金", vos.getFirst().getQuery());
        assertEquals("hybrid-rrf-k60-top10-v1", vos.getFirst().getRuleNameVersion());
        assertEquals("v2", vos.getFirst().getVersionNo());
        assertEquals(12L, vos.getFirst().getElapsedMs());
        assertEquals("c1", vos.getFirst().getHits().getFirst().getChunkId());
        assertEquals("快照内容", vos.getFirst().getHits().getFirst().getContent());
        assertEquals(1L, vos.getFirst().getRunId());
        verify(milvusIndexPort, never()).searchVector(anyString(), any());
        verify(milvusIndexPort, never()).searchFullText(anyString(), any());
    }

    /**
     * 对比回放：运行记录不属于该知识库按 40001 拒绝。
     *
     * <p>守护 {@code compareRuns}：跨库记录被回放即失败。
     */
    @Test
    void compareRunsShouldRejectForeignRun() {
        WbRetrievalRun run = new WbRetrievalRun();
        run.setId(1L);
        run.setKbId(2L);
        run.setResultSnapshot("[]");
        when(retrievalRunDbService.listByIdsOrdered(List.of(1L))).thenReturn(List.of(run));

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.compareRuns(1L, List.of(1L)));

        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    /**
     * 运行记录列表按 VO 映射。
     *
     * <p>守护 {@code listRuns}/{@code toRunVO}：字段错位即失败（对比回放读错快照）。
     */
    @Test
    void listRunsShouldMapToVo() {
        WbRetrievalRun run = new WbRetrievalRun();
        run.setId(5L);
        run.setVersionId(30L);
        run.setVersionNo("v9");
        run.setRuleId(500L);
        run.setRuleNameVersion("hybrid-rrf-k60-top10-v1");
        run.setQuery("保证金");
        run.setElapsedMs(88);
        run.setCreateTime(java.time.LocalDateTime.of(2026, 10, 9, 10, 0));
        when(retrievalRunDbService.listByKb(9L, 5)).thenReturn(List.of(run));

        List<com.workbuddy.common.dto.response.retrieval.RetrievalRunVO> runs = service.listRuns(9L, 5);

        assertEquals(1, runs.size());
        assertEquals(5L, runs.get(0).getId());
        assertEquals(30L, runs.get(0).getVersionId());
        assertEquals("v9", runs.get(0).getVersionNo());
        assertEquals(500L, runs.get(0).getRuleId());
        assertEquals("hybrid-rrf-k60-top10-v1", runs.get(0).getRuleNameVersion());
        assertEquals("保证金", runs.get(0).getQuery());
        assertEquals(88L, runs.get(0).getElapsedMs().longValue());
        assertEquals(java.time.LocalDateTime.of(2026, 10, 9, 10, 0), runs.get(0).getCreateTime());
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
        when(indexSetDbService.getByKb(9L)).thenReturn(set);
        WbIndexVersion version = versionRow(30L, "v9", IndexVersionStatus.ONLINE.name(), comboAll());
        version.setDefaultRuleId(500L);
        when(indexVersionDbService.getById(30L)).thenReturn(version);
        when(strategyVersionDbService.getById(500L)).thenReturn(ruleRow());
        RetrievalRuleSpec spec = new RetrievalRuleSpec();
        spec.setChannel(RetrievalRuleSpec.CHANNEL_HYBRID);
        spec.getPostprocess().setMode(RetrievalRuleSpec.MODE_PARENT_EXPAND);
        when(retrievalRuleResolver.validate(anyString())).thenReturn(spec);
        FullTextHit child1 = FullTextHit.builder().chunkId("c1").content("子片一").documentId(9L)
                .titlePath("旧路径").parentChunkId("p1").build();
        FullTextHit child2 = FullTextHit.builder().chunkId("c2").content("子片二").documentId(9L)
                .titlePath("旧路径").parentChunkId("p1").build();
        FullTextHit standalone = FullTextHit.builder().chunkId("c3").content("独立片").documentId(9L)
                .titlePath(null).parentChunkId(null).build();
        when(milvusIndexPort.searchFullText(eq("kb_9_v9"), any(FullTextQuery.class)))
                .thenReturn(List.of(child1, child2, standalone));
        IndexRow parent = new IndexRow();
        parent.setChunkId("p1");
        parent.setContent("整段全文");
        parent.setTitlePath("第一章");
        parent.setDocumentId(99L);
        parent.setParentChunkId(null);
        parent.setSourceElementIds("[\"e1\"]");
        when(milvusIndexPort.queryChunkByIds("kb_9_v9", List.of("p1"))).thenReturn(List.of(parent));

        SearchVO vo = service.search(9L, request());

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
        verify(retrievalRunDbService, never()).save(any(WbRetrievalRun.class));
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
        when(indexSetDbService.getByKb(9L)).thenReturn(set);
        WbIndexVersion version = versionRow(30L, "v9", IndexVersionStatus.ONLINE.name(), comboAll());
        when(indexVersionDbService.getById(30L)).thenReturn(version);
        RetrievalRuleSpec spec = new RetrievalRuleSpec();
        spec.setChannel(RetrievalRuleSpec.CHANNEL_FULLTEXT);
        when(retrievalRuleResolver.validate(anyString())).thenReturn(spec);
        FullTextHit hit = FullTextHit.builder().chunkId("c1").content("全文-c1").documentId(9L)
                .parentChunkId(null).build();
        when(milvusIndexPort.searchFullText(eq("kb_9_v9"), any(FullTextQuery.class)))
                .thenReturn(List.of(hit));

        SearchVO vo = service.search(9L, request());

        assertEquals(1, vo.getHits().size());
        assertEquals("全文-c1", vo.getHits().get(0).getContent());
        assertNull(vo.getHits().get(0).getScore());
        verify(strategyVersionDbService, never()).getByTypeAndNameAndVersion(anyString(), anyString(), anyString());
        verify(milvusIndexPort, never()).searchVector(anyString(), any(VectorQuery.class));
    }

    /**
     * 生产检索的响应外壳与命中字段逐列搬运。
     *
     * <p>守护 {@code search}/{@code toVO}：查询回显、耗时、命中定位字段漏搬都会让测试台对不上标注。
     */
    @Test
    void productionSearchShouldFillResponseEnvelope() {
        stubProduction(9L, comboAll(), null);
        when(retrievalRuleResolver.validate(anyString())).thenReturn(fullTextSpec());
        FullTextHit hit = FullTextHit.builder().chunkId("c1").content("正文").documentId(9L)
                .titlePath("第一章").contentType("PARAGRAPH").parentChunkId("p1")
                .sourceElementIds("[\"e1\"]").build();
        when(milvusIndexPort.searchFullText(eq("kb_9_v9"), any(FullTextQuery.class))).thenReturn(List.of(hit));

        SearchVO vo = service.search(9L, request());

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
        stubProduction(9L, comboAll(), null);
        when(runSettings.recordProduction()).thenReturn(true);
        when(retrievalRuleResolver.validate(anyString())).thenReturn(fullTextSpec());
        when(milvusIndexPort.searchFullText(eq("kb_9_v9"), any(FullTextQuery.class)))
                .thenReturn(List.of(fullTextHit("c1")));
        when(retrievalRunDbService.save(any(WbRetrievalRun.class))).thenAnswer(inv -> {
            inv.<WbRetrievalRun>getArgument(0).setId(900L);
            return true;
        });

        SearchVO vo = service.search(9L, request());

        ArgumentCaptor<WbRetrievalRun> captor = ArgumentCaptor.forClass(WbRetrievalRun.class);
        verify(retrievalRunDbService).save(captor.capture());
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
     * 对比回放：runIds 为空或缺失按 40001 拒绝。
     *
     * <p>守护 {@code compareRuns} 的入参防线：空列表放行会返回空结果、掩盖调用错误。
     */
    @Test
    void compareRunsShouldRejectEmptyIds() {
        KnowledgeException empty = assertThrows(KnowledgeException.class,
                () -> service.compareRuns(1L, List.of()));
        assertEquals(ErrorCode.PARAM_INVALID, empty.getErrorCode());

        KnowledgeException nullIds = assertThrows(KnowledgeException.class,
                () -> service.compareRuns(1L, null));
        assertEquals(ErrorCode.PARAM_INVALID, nullIds.getErrorCode());
    }

    /**
     * 对比回放：耗时缺失按 0 下发，规则与版本号照搬。
     *
     * <p>守护 {@code compareRuns} 的空耗时兜底：直接取 intValue 会空指针。
     */
    @Test
    void compareRunsShouldMapNullElapsedAsZero() {
        WbRetrievalRun run = new WbRetrievalRun();
        run.setId(2L);
        run.setKbId(1L);
        run.setVersionNo("v2");
        run.setRuleNameVersion("baseline-v0");
        run.setQuery("保证金");
        run.setElapsedMs(null);
        run.setResultSnapshot("[]");
        when(retrievalRunDbService.listByIdsOrdered(List.of(2L))).thenReturn(List.of(run));

        SearchVO vo = service.compareRuns(1L, List.of(2L)).getFirst();

        assertEquals("baseline-v0", vo.getRuleNameVersion());
        assertEquals("v2", vo.getVersionNo());
        assertEquals(0L, vo.getElapsedMs());
        assertEquals(2L, vo.getRunId());
    }

    /**
     * 测试台：版本不存在按 40441、非就绪/在线按 40443。
     *
     * <p>守护 {@code resolveVersion} 的测试台分支：构建中的候选被检索即读到半成品。
     */
    @Test
    void benchSearchShouldRequireExistingReadyVersion() {
        SearchDTO missingRequest = request();
        missingRequest.setVersionId(99L);
        missingRequest.setRuleId(500L);
        when(indexVersionDbService.getById(99L)).thenReturn(null);
        KnowledgeException missing = assertThrows(KnowledgeException.class,
                () -> service.search(1L, missingRequest));
        assertEquals(ErrorCode.INDEX_VERSION_NOT_FOUND, missing.getErrorCode());

        SearchDTO buildingRequest = request();
        buildingRequest.setVersionId(98L);
        buildingRequest.setRuleId(500L);
        when(indexVersionDbService.getById(98L))
                .thenReturn(versionRow(98L, "v8", IndexVersionStatus.BUILDING.name(), comboAll()));
        KnowledgeException notReady = assertThrows(KnowledgeException.class,
                () -> service.search(1L, buildingRequest));
        assertEquals(ErrorCode.INDEX_BUILDING_CONFLICT, notReady.getErrorCode());
    }

    /**
     * 规则解析：显式规则不存在或类型不是 RETRIEVAL 都按 40442 拒绝。
     *
     * <p>守护 {@code resolveRuleRow} 的显式分支：拿切片策略当检索规则会跑出无意义结果。
     */
    @Test
    void resolveRuleRowShouldRejectUnknownOrWrongTypeRule() {
        SearchDTO request = request();
        request.setVersionId(30L);
        request.setRuleId(500L);
        when(indexVersionDbService.getById(30L))
                .thenReturn(versionRow(30L, "v9", IndexVersionStatus.READY.name(), comboAll()));
        when(strategyVersionDbService.getById(500L)).thenReturn(null);
        KnowledgeException missing = assertThrows(KnowledgeException.class,
                () -> service.search(1L, request));
        assertEquals(ErrorCode.RETRIEVAL_RULE_NOT_FOUND, missing.getErrorCode());

        WbPipelineStrategyVersion wrongType = ruleRow();
        wrongType.setType("CHUNK");
        when(strategyVersionDbService.getById(500L)).thenReturn(wrongType);
        KnowledgeException mismatch = assertThrows(KnowledgeException.class,
                () -> service.search(1L, request));
        assertEquals(ErrorCode.RETRIEVAL_RULE_NOT_FOUND, mismatch.getErrorCode());
    }

    /**
     * 组合快照缺环节策略维度：查询向量化中止，向量通道降级为空、全文照跑。
     *
     * <p>守护 {@code embedQuery} 的快照不全分支：拿空维度去查 EMBED 策略会拼出错误策略名。
     */
    @Test
    void hybridShouldDegradeVectorWhenComboIncomplete() {
        stubProduction(9L, new ComboSnapshot(), 500L);
        when(strategyVersionDbService.getById(500L)).thenReturn(ruleRow());
        when(retrievalRuleResolver.validate(anyString())).thenReturn(hybridSpec());
        when(milvusIndexPort.searchFullText(eq("kb_9_v9"), any(FullTextQuery.class)))
                .thenReturn(List.of(fullTextHit("c1")));

        SearchVO vo = service.search(9L, request());

        assertEquals(1, vo.getHits().size());
        assertEquals("c1", vo.getHits().getFirst().getChunkId());
        verify(milvusIndexPort, never()).searchVector(anyString(), any());
    }

    /**
     * EMBED 策略版本行不存在：查询向量化中止、向量通道降级。
     *
     * <p>守护 {@code embedQuery} 的策略行缺失分支。
     */
    @Test
    void hybridShouldDegradeVectorWhenEmbedRowMissing() {
        stubProduction(9L, comboAll(), 500L);
        when(strategyVersionDbService.getById(500L)).thenReturn(ruleRow());
        when(retrievalRuleResolver.validate(anyString())).thenReturn(hybridSpec());
        when(strategyVersionDbService.getByTypeAndNameAndVersion("EMBED", "embed-default", "v1")).thenReturn(null);
        when(milvusIndexPort.searchFullText(eq("kb_9_v9"), any(FullTextQuery.class)))
                .thenReturn(List.of(fullTextHit("c1")));

        SearchVO vo = service.search(9L, request());

        assertEquals(1, vo.getHits().size());
        verify(milvusIndexPort, never()).searchVector(anyString(), any());
    }

    /**
     * 策略可解析但模型调用层未接入：输入拼好后中止，不得用空向量去检索。
     *
     * <p>守护 {@code embedQuery} 的模型缺口分支：返回空表而非 null 会触发一次空向量检索。
     */
    @Test
    void hybridShouldStopAtModelGapAfterBuildingInput() {
        stubProduction(9L, comboAll(), 500L);
        when(strategyVersionDbService.getById(500L)).thenReturn(ruleRow());
        when(retrievalRuleResolver.validate(anyString())).thenReturn(hybridSpec());
        WbPipelineStrategyVersion embedRow = new WbPipelineStrategyVersion();
        embedRow.setConfigSnapshot("{\"model\":\"text-embedding-v4\"}");
        when(strategyVersionDbService.getByTypeAndNameAndVersion("EMBED", "embed-default", "v1")).thenReturn(embedRow);
        EmbedStrategy strategy = new EmbedStrategy();
        strategy.setModel("text-embedding-v4");
        strategy.setQueryTemplate(" ");
        when(embedStrategyParser.parse(embedRow.getConfigSnapshot())).thenReturn(strategy);
        when(milvusIndexPort.searchFullText(eq("kb_9_v9"), any(FullTextQuery.class)))
                .thenReturn(List.of(fullTextHit("c1")));

        SearchVO vo = service.search(9L, request());

        assertEquals(1, vo.getHits().size());
        verify(milvusIndexPort, never()).searchVector(anyString(), any());
    }

    /**
     * 混合通道：同 chunk 的重复全文命中按一条返回（按 chunkId 归并）。
     *
     * <p>守护 {@code orderHits} 的两处归并：归并写错会返回重复命中。
     */
    @Test
    void hybridShouldDedupeDuplicateFullTextHits() {
        stubProduction(9L, comboAll(), 500L);
        when(strategyVersionDbService.getById(500L)).thenReturn(ruleRow());
        when(retrievalRuleResolver.validate(anyString())).thenReturn(hybridSpec());
        FullTextHit dup1 = FullTextHit.builder().chunkId("c1").content("一").documentId(9L).build();
        FullTextHit dup2 = FullTextHit.builder().chunkId("c1").content("二").documentId(9L).build();
        when(milvusIndexPort.searchFullText(eq("kb_9_v9"), any(FullTextQuery.class)))
                .thenReturn(List.of(dup1, dup2));

        SearchVO vo = service.search(9L, request());

        assertEquals(1, vo.getHits().size());
        assertEquals("一", vo.getHits().getFirst().getContent());
        verify(milvusIndexPort, never()).searchVector(anyString(), any());
    }

    /**
     * 父片展开：命中都没有父片时原样返回；父片行重复时按一条归并。
     *
     * <p>守护 {@code expandParent} 的空父片短路与父片行归并：短路写错会把命中清空。
     */
    @Test
    void expandParentShouldShortCircuitWithoutParents() {
        stubProduction(9L, comboAll(), 500L);
        when(strategyVersionDbService.getById(500L)).thenReturn(ruleRow());
        RetrievalRuleSpec spec = fullTextSpec();
        spec.getPostprocess().setMode(RetrievalRuleSpec.MODE_PARENT_EXPAND);
        when(retrievalRuleResolver.validate(anyString())).thenReturn(spec);
        when(milvusIndexPort.searchFullText(eq("kb_9_v9"), any(FullTextQuery.class)))
                .thenReturn(List.of(FullTextHit.builder().chunkId("c1").content("无父片").documentId(9L).build()));

        SearchVO vo = service.search(9L, request());

        assertEquals(1, vo.getHits().size());
        assertEquals("无父片", vo.getHits().getFirst().getContent());
        verify(milvusIndexPort, never()).queryChunkByIds(anyString(), any());
    }

    /**
     * 父片展开：父片行重复（同 chunkId）时按一条归并，命中仍替换为父片全文。
     *
     * <p>守护 {@code expandParent} 的父片行归并：归并写错会取到空父片、展开失效。
     */
    @Test
    void expandParentShouldMergeDuplicateParentRows() {
        stubProduction(9L, comboAll(), 500L);
        when(strategyVersionDbService.getById(500L)).thenReturn(ruleRow());
        RetrievalRuleSpec spec = fullTextSpec();
        spec.getPostprocess().setMode(RetrievalRuleSpec.MODE_PARENT_EXPAND);
        when(retrievalRuleResolver.validate(anyString())).thenReturn(spec);
        when(milvusIndexPort.searchFullText(eq("kb_9_v9"), any(FullTextQuery.class)))
                .thenReturn(List.of(FullTextHit.builder().chunkId("c1").content("子片").documentId(9L)
                        .parentChunkId("p1").build()));
        IndexRow parent = new IndexRow();
        parent.setChunkId("p1");
        parent.setContent("父片全文");
        parent.setDocumentId(9L);
        IndexRow duplicate = new IndexRow();
        duplicate.setChunkId("p1");
        duplicate.setContent("父片全文副本");
        duplicate.setDocumentId(9L);
        when(milvusIndexPort.queryChunkByIds("kb_9_v9", List.of("p1")))
                .thenReturn(List.of(parent, duplicate));

        SearchVO vo = service.search(9L, request());

        assertEquals(1, vo.getHits().size());
        assertEquals("p1", vo.getHits().getFirst().getChunkId());
        assertEquals("父片全文", vo.getHits().getFirst().getContent());
        assertTrue(vo.getHits().getFirst().getIsParent());
    }

    /**
     * 版本行带默认规则时：运行记录要写规则 ID 与 name-version。
     *
     * <p>守护 {@code recordRun} 的规则列：漏写 ruleId 会让对比回放找不到规则来源。
     */
    @Test
    void productionSearchWithVersionDefaultRuleShouldRecordRuleId() {
        stubProduction(9L, comboAll(), 500L);
        when(strategyVersionDbService.getById(500L)).thenReturn(ruleRow());
        when(runSettings.recordProduction()).thenReturn(true);
        when(retrievalRuleResolver.validate(anyString())).thenReturn(fullTextSpec());
        when(milvusIndexPort.searchFullText(eq("kb_9_v9"), any(FullTextQuery.class)))
                .thenReturn(List.of(fullTextHit("c1")));
        when(retrievalRunDbService.save(any(WbRetrievalRun.class))).thenAnswer(inv -> true);

        service.search(9L, request());

        ArgumentCaptor<WbRetrievalRun> captor = ArgumentCaptor.forClass(WbRetrievalRun.class);
        verify(retrievalRunDbService).save(captor.capture());
        assertEquals(500L, captor.getValue().getRuleId());
        assertEquals("hybrid-rrf-k60-top10-v1", captor.getValue().getRuleNameVersion());
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
        when(indexSetDbService.getByKb(9L)).thenReturn(set);
        when(indexVersionDbService.getById(30L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.search(9L, request()));

        assertEquals(ErrorCode.INDEX_NOT_PUBLISHED, e.getErrorCode());
    }

    /** 生产版本夹具：在线版本 + 集合指针（版本号 v9、版本行 ID 30） */
    private WbIndexVersion stubProduction(Long kbId, ComboSnapshot combo, Long defaultRuleId) {
        WbIndexSet set = new WbIndexSet();
        set.setId(5L);
        set.setCurrentPublishedVersionId(30L);
        when(indexSetDbService.getByKb(kbId)).thenReturn(set);
        WbIndexVersion version = versionRow(30L, "v9", IndexVersionStatus.ONLINE.name(), combo);
        version.setDefaultRuleId(defaultRuleId);
        when(indexVersionDbService.getById(30L)).thenReturn(version);
        return version;
    }

    /** 纯全文规则 */
    private RetrievalRuleSpec fullTextSpec() {
        RetrievalRuleSpec spec = new RetrievalRuleSpec();
        spec.setChannel(RetrievalRuleSpec.CHANNEL_FULLTEXT);
        return spec;
    }

    /** 混合通道规则 */
    private RetrievalRuleSpec hybridSpec() {
        RetrievalRuleSpec spec = new RetrievalRuleSpec();
        spec.setChannel(RetrievalRuleSpec.CHANNEL_HYBRID);
        return spec;
    }
}