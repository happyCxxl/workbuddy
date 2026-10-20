package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbRetrievalRunDbService;
import com.workbuddy.biz.service.support.RetrievalRuleResolver;
import com.workbuddy.biz.service.support.RetrievalRunSettings;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbRetrievalRun;
import com.workbuddy.common.dto.request.retrieval.SearchDTO;
import com.workbuddy.common.enums.index.IndexVersionStatus;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 检索编排测试夹具：装配被测服务（边界替身）、索引版本/规则行/命中数据与常见桩。
 *
 * <p>生产检索、测试台与运行记录三组用例共用；各测试类不再重复声明依赖。
 */
final class SearchTestFixture {

    final WbIndexSetDbService indexSetDbService = mock(WbIndexSetDbService.class);
    final WbIndexVersionDbService indexVersionDbService = mock(WbIndexVersionDbService.class);
    final WbKnowledgeBaseDbService knowledgeBaseDbService = mock(WbKnowledgeBaseDbService.class);
    final WbPipelineStrategyVersionDbService strategyVersionDbService =
            mock(WbPipelineStrategyVersionDbService.class);
    final MilvusIndexPort milvusIndexPort = mock(MilvusIndexPort.class);
    final EmbedStrategyParser embedStrategyParser = mock(EmbedStrategyParser.class);
    final RetrievalRuleResolver retrievalRuleResolver = mock(RetrievalRuleResolver.class);
    final WbRetrievalRunDbService retrievalRunDbService = mock(WbRetrievalRunDbService.class);
    final RetrievalRunSettings runSettings = mock(RetrievalRunSettings.class);

    private final SearchServiceImpl service;

    SearchTestFixture() {
        service = new SearchServiceImpl(indexSetDbService, indexVersionDbService, knowledgeBaseDbService,
                strategyVersionDbService, milvusIndexPort, embedStrategyParser,
                retrievalRuleResolver, retrievalRunDbService, runSettings);
    }

    /** 被测服务 */
    SearchServiceImpl service() {
        return service;
    }

    /** 索引版本行夹具（组合快照按对象序列化） */
    WbIndexVersion versionRow(Long id, String versionNo, String status, ComboSnapshot combo) {
        WbIndexVersion version = new WbIndexVersion();
        version.setId(id);
        version.setVersionNo(versionNo);
        version.setStatus(status);
        version.setComboSnapshot(JsonUtil.toJsonStr(combo));
        return version;
    }

    /** 三环节组合快照（预处理 / 切片 / 向量化默认策略） */
    ComboSnapshot comboAll() {
        return ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
    }

    /** 检索规则版本行夹具（RETRIEVAL，配置 HYBRID） */
    WbPipelineStrategyVersion ruleRow() {
        WbPipelineStrategyVersion row = new WbPipelineStrategyVersion();
        row.setId(500L);
        row.setType("RETRIEVAL");
        row.setName("hybrid-rrf-k60-top10");
        row.setVersion("v1");
        row.setConfigSnapshot("{\"channel\":\"HYBRID\"}");
        return row;
    }

    /** 检索请求夹具（查询词"保证金"） */
    SearchDTO request() {
        SearchDTO request = new SearchDTO();
        request.setQuery("保证金");
        return request;
    }

    /** 全文命中夹具 */
    FullTextHit fullTextHit(String chunkId) {
        return FullTextHit.builder().chunkId(chunkId).content("全文-" + chunkId)
                .documentId(10L).parentChunkId(null).build();
    }

    /** 向量命中夹具 */
    VectorHit vectorHit(String chunkId, String parentChunkId) {
        return VectorHit.builder().chunkId(chunkId).content("向量-" + chunkId)
                .documentId(10L).parentChunkId(parentChunkId).score(0.9F).build();
    }

    /** 生产版本夹具：在线版本 + 集合指针（版本号 v9、版本行 ID 30） */
    WbIndexVersion stubProduction(Long kbId, ComboSnapshot combo, Long defaultRuleId) {
        WbIndexSet set = new WbIndexSet();
        set.setId(5L);
        set.setCurrentPublishedVersionId(30L);
        when(indexSetDbService.getByKb(kbId)).thenReturn(set);
        WbIndexVersion version = versionRow(30L, "v9", IndexVersionStatus.ONLINE.name(), combo);
        version.setDefaultRuleId(defaultRuleId);
        when(indexVersionDbService.getById(30L)).thenReturn(version);
        return version;
    }

    /** 集合指针夹具：按知识库返回指定 id 与在线版本指针 */
    void stubPublishedSet(Long kbId, Long setId, Long versionId) {
        WbIndexSet set = new WbIndexSet();
        set.setId(setId);
        set.setKnowledgeBaseId(kbId);
        set.setCurrentPublishedVersionId(versionId);
        when(indexSetDbService.getByKb(kbId)).thenReturn(set);
    }

    /** 在线版本行桩：返回指定版本号的在线版本（三环节组合快照） */
    WbIndexVersion stubOnlineVersion(Long versionId, String versionNo) {
        WbIndexVersion version = versionRow(versionId, versionNo, IndexVersionStatus.ONLINE.name(), comboAll());
        when(indexVersionDbService.getById(versionId)).thenReturn(version);
        return version;
    }

    /** 生产混合通道桩：集合指向 + 版本默认规则行 + 混合规则解析 */
    void stubHybridProduction(ComboSnapshot combo, Long ruleId) {
        stubHybridRule(combo, ruleId);
        when(retrievalRuleResolver.validate(anyString())).thenReturn(hybridSpec());
    }

    /** 生产混合通道桩（不含规则解析）：集合指向 + 版本默认规则行 */
    void stubHybridRule(ComboSnapshot combo, Long ruleId) {
        stubProduction(9L, combo, ruleId);
        when(strategyVersionDbService.getById(ruleId)).thenReturn(ruleRow());
    }

    /** 纯全文规则 */
    RetrievalRuleSpec fullTextSpec() {
        RetrievalRuleSpec spec = new RetrievalRuleSpec();
        spec.setChannel(RetrievalRuleSpec.CHANNEL_FULLTEXT);
        return spec;
    }

    /** 混合通道规则 */
    RetrievalRuleSpec hybridSpec() {
        RetrievalRuleSpec spec = new RetrievalRuleSpec();
        spec.setChannel(RetrievalRuleSpec.CHANNEL_HYBRID);
        return spec;
    }

    /** EMBED 策略版本行桩：按默认回退名取到指定配置快照 */
    WbPipelineStrategyVersion stubEmbedRow(String configSnapshot) {
        WbPipelineStrategyVersion embedRow = new WbPipelineStrategyVersion();
        embedRow.setConfigSnapshot(configSnapshot);
        when(strategyVersionDbService.getByTypeAndNameAndVersion("EMBED", "embed-default", "v1"))
                .thenReturn(embedRow);
        return embedRow;
    }

    /** 查询向量化策略解析桩（不设超时） */
    void stubParsedEmbedStrategy(String configSnapshot, String model, String queryTemplate) {
        EmbedStrategy strategy = new EmbedStrategy();
        strategy.setModel(model);
        strategy.setQueryTemplate(queryTemplate);
        when(embedStrategyParser.parse(configSnapshot)).thenReturn(strategy);
    }

    /** 查询向量化策略解析桩（指定超时） */
    void stubParsedEmbedStrategy(String configSnapshot, String model, String queryTemplate, int timeoutMs) {
        EmbedStrategy strategy = new EmbedStrategy();
        strategy.setModel(model);
        strategy.setQueryTemplate(queryTemplate);
        strategy.setTimeoutMs(timeoutMs);
        when(embedStrategyParser.parse(configSnapshot)).thenReturn(strategy);
    }

    /** 全文检索桩：指定索引集合与命中序列 */
    void stubFullText(String indexName, FullTextHit... hits) {
        when(milvusIndexPort.searchFullText(eq(indexName), any(FullTextQuery.class)))
                .thenReturn(List.of(hits));
    }

    /** 向量检索桩：指定索引集合与命中序列 */
    void stubVector(String indexName, VectorHit... hits) {
        when(milvusIndexPort.searchVector(eq(indexName), any(VectorQuery.class)))
                .thenReturn(List.of(hits));
    }

    /** 父片回查桩：指定索引集合、父片 id 与父片行 */
    void stubParents(String indexName, List<String> parentIds, IndexRow... rows) {
        when(milvusIndexPort.queryChunkByIds(eq(indexName), eq(parentIds))).thenReturn(List.of(rows));
    }

    /** 运行记录落库桩：回填指定记录号 */
    void stubRunSaveAssignsId(long runId) {
        when(retrievalRunDbService.save(any(WbRetrievalRun.class))).thenAnswer(inv -> {
            inv.<WbRetrievalRun>getArgument(0).setId(runId);
            return true;
        });
    }

    /** 知识库行夹具 */
    WbKnowledgeBase knowledgeBase(Long id) {
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(id);
        return kb;
    }
}
