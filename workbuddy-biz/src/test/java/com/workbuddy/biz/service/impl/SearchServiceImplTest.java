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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void benchWithPartialIdsShouldReject() {
        SearchDTO request = request();
        request.setVersionId(30L);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.search(1L, request));

        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    @Test
    void productionWithoutPublishedShouldThrow40446() {
        when(indexSetDbService.getByKb(1L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.search(1L, request()));

        assertEquals(ErrorCode.INDEX_NOT_PUBLISHED, e.getErrorCode());
    }

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
        assertEquals("c1", vos.getFirst().getHits().getFirst().getChunkId());
        assertEquals("快照内容", vos.getFirst().getHits().getFirst().getContent());
        assertEquals(1L, vos.getFirst().getRunId());
        verify(milvusIndexPort, never()).searchVector(anyString(), any());
        verify(milvusIndexPort, never()).searchFullText(anyString(), any());
    }

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
}