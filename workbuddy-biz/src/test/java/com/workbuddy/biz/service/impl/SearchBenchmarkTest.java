package com.workbuddy.biz.service.impl;

import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbRetrievalRun;
import com.workbuddy.common.dto.request.retrieval.SearchDTO;
import com.workbuddy.common.dto.response.retrieval.SearchHitVO;
import com.workbuddy.common.dto.response.retrieval.SearchVO;
import com.workbuddy.common.enums.index.IndexVersionStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.worker.indexing.IndexRow;
import com.workbuddy.worker.retrieval.RetrievalRuleSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索测试台用例：显式版本 + 显式规则（含冻结集）/ HYBRID RRF 融合 / 父片展开 /
 * 入参成对校验 / 版本就绪校验 / 规则类型校验 / 预留能力锁定。
 *
 * <p>生产检索与运行记录用例分别在 {@link SearchProductionTest}、{@link SearchRunQueryTest}；
 * 装配与夹具数据见 {@link SearchTestFixture}。
 */
@ExtendWith(MockitoExtension.class)
class SearchBenchmarkTest {

    private SearchTestFixture fixture;

    private SearchServiceImpl service;

    @BeforeEach
    void setUp() {
        fixture = new SearchTestFixture();
        service = fixture.service();
    }

    /**
     * 测试台：显式版本 + 显式规则，HYBRID 按 RRF 融合并落运行记录（模型接入后恢复）。
     *
     * <p>守护 {@code search}：融合序或记录字段错即失败。
     */
    @Test
    @Disabled("模型调用层待接入：向量通道恒降级")
    void benchSearchShouldUseExplicitVersionAndRuleAndRecordRun() {
        SearchDTO request = fixture.request();
        request.setVersionId(30L);
        request.setRuleId(500L);
        WbIndexVersion version = fixture.versionRow(30L, "v9", IndexVersionStatus.READY.name(),
                fixture.comboAll());
        when(fixture.indexVersionDbService.getById(30L)).thenReturn(version);
        WbPipelineStrategyVersion rule = fixture.ruleRow();
        when(fixture.strategyVersionDbService.getById(500L)).thenReturn(rule);
        when(fixture.retrievalRuleResolver.validate(rule.getConfigSnapshot())).thenReturn(fixture.hybridSpec());
        // 查询向量化：跟随集合 EMBED 策略
        WbPipelineStrategyVersion embedRow = fixture.stubEmbedRow("{\"model\":\"text-embedding-v4\"}");
        fixture.stubParsedEmbedStrategy(embedRow.getConfigSnapshot(), "text-embedding-v4", "{query}", 5000);
        fixture.stubFullText("kb_1_v9", fixture.fullTextHit("c1"), fixture.fullTextHit("c2"));
        fixture.stubVector("kb_1_v9", fixture.vectorHit("c2", null), fixture.vectorHit("c3", null));
        fixture.stubRunSaveAssignsId(900L);

        SearchVO vo = service.search(1L, request);

        // 融合序：c2(1/61+1/62) > c1(1/61) > c3(1/62)
        assertEquals(List.of("c2", "c1", "c3"), vo.getHits().stream().map(SearchHitVO::getChunkId).toList());
        assertEquals(1.0 / 61 + 1.0 / 62, vo.getHits().getFirst().getScore(), 1e-9);
        assertEquals(900L, vo.getRunId());
        assertEquals("v9", vo.getVersionNo());
        assertEquals("hybrid-rrf-k60-top10-v1", vo.getRuleNameVersion());
        verify(fixture.retrievalRunDbService).save(any(WbRetrievalRun.class));
    }

    /**
     * 父片展开：子片命中 → 取集合内父片全文替换并标 isParent（模型接入后恢复）。
     *
     * <p>守护 {@code expandParent}：未替换父片全文即失败。
     */
    @Test
    @Disabled("模型调用层待接入：向量通道恒降级")
    void parentExpandShouldFetchParentFromCollection() {
        SearchDTO request = fixture.request();
        request.setRuleId(500L);
        WbIndexVersion version = fixture.versionRow(30L, "v9", IndexVersionStatus.READY.name(),
                fixture.comboAll());
        when(fixture.indexVersionDbService.getById(30L)).thenReturn(version);
        WbPipelineStrategyVersion rule = fixture.ruleRow();
        when(fixture.strategyVersionDbService.getById(500L)).thenReturn(rule);
        RetrievalRuleSpec spec = new RetrievalRuleSpec();
        spec.setChannel(RetrievalRuleSpec.CHANNEL_VECTOR);
        spec.getPostprocess().setMode(RetrievalRuleSpec.MODE_PARENT_EXPAND);
        when(fixture.retrievalRuleResolver.validate(rule.getConfigSnapshot())).thenReturn(spec);
        WbPipelineStrategyVersion embedRow = fixture.stubEmbedRow("{\"model\":\"text-embedding-v4\"}");
        fixture.stubParsedEmbedStrategy(embedRow.getConfigSnapshot(), "text-embedding-v4", "{query}", 5000);
        fixture.stubVector("kb_1_v9", fixture.vectorHit("c-12", "c-1"));
        IndexRow parent = new IndexRow();
        parent.setChunkId("c-1");
        parent.setContent("第三节 保证金（父片全文）");
        parent.setDocumentId(10L);
        fixture.stubParents("kb_1_v9", List.of("c-1"), parent);

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
        SearchDTO request = fixture.request();
        request.setVersionId(30L);
        request.setRuleId(500L);
        WbIndexVersion version = fixture.versionRow(30L, "v9", IndexVersionStatus.READY.name(),
                fixture.comboAll());
        when(fixture.indexVersionDbService.getById(30L)).thenReturn(version);
        WbPipelineStrategyVersion rule = fixture.ruleRow();
        when(fixture.strategyVersionDbService.getById(500L)).thenReturn(rule);
        when(fixture.retrievalRuleResolver.validate(rule.getConfigSnapshot())).thenReturn(fixture.hybridSpec());
        fixture.stubEmbedRow("{oops");
        when(fixture.embedStrategyParser.parse("{oops")).thenThrow(
                new IllegalStateException("向量化策略快照为空或已损坏，无法解析, snapshot={oops"));
        fixture.stubFullText("kb_1_v9", fixture.fullTextHit("c1"));
        when(fixture.retrievalRunDbService.save(any(WbRetrievalRun.class))).thenReturn(true);

        SearchVO vo = service.search(1L, request);

        assertEquals(1, vo.getHits().size());
        assertEquals("c1", vo.getHits().getFirst().getChunkId());
        verify(fixture.milvusIndexPort, never()).searchVector(anyString(), any());
    }

    /**
     * 引擎防御：预留能力被规则选中时拒绝执行（注册侧已拦，双保险防历史数据）。
     *
     * <p>守护 {@code search}：预留能力被执行即失败。
     */
    @Test
    void lockedCapabilityRuleShouldBeRejectedAtEngine() {
        SearchDTO request = fixture.request();
        request.setVersionId(30L);
        request.setRuleId(500L);
        WbIndexVersion version = fixture.versionRow(30L, "v9", IndexVersionStatus.READY.name(),
                fixture.comboAll());
        when(fixture.indexVersionDbService.getById(30L)).thenReturn(version);
        WbPipelineStrategyVersion rule = fixture.ruleRow();
        when(fixture.strategyVersionDbService.getById(500L)).thenReturn(rule);
        when(fixture.retrievalRuleResolver.validate(rule.getConfigSnapshot()))
                .thenThrow(new WorkbuddyException(ErrorCode.RETRIEVAL_CAPABILITY_LOCKED, "该检索能力尚未启用: 加权融合"));

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.search(1L, request));

        assertEquals(ErrorCode.RETRIEVAL_CAPABILITY_LOCKED, e.getErrorCode());
        verify(fixture.milvusIndexPort, never()).searchVector(anyString(), any());
        verify(fixture.milvusIndexPort, never()).searchFullText(anyString(), any());
    }

    /**
     * 测试台入参：只给版本或只给规则按 40001 拒绝（两者必须成对）。
     *
     * <p>守护 {@code search}：半套参数放行即失败。
     */
    @Test
    void benchWithPartialIdsShouldReject() {
        SearchDTO request = fixture.request();
        request.setVersionId(30L);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.search(1L, request));

        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    /**
     * 测试台：版本不存在按 40441、非就绪/在线按 40443。
     *
     * <p>守护 {@code resolveVersion} 的测试台分支：构建中的候选被检索即读到半成品。
     */
    @Test
    void benchSearchShouldRequireExistingReadyVersion() {
        SearchDTO missingRequest = fixture.request();
        missingRequest.setVersionId(99L);
        missingRequest.setRuleId(500L);
        when(fixture.indexVersionDbService.getById(99L)).thenReturn(null);
        WorkbuddyException missing = assertThrows(WorkbuddyException.class,
                () -> service.search(1L, missingRequest));
        assertEquals(ErrorCode.INDEX_VERSION_NOT_FOUND, missing.getErrorCode());

        SearchDTO buildingRequest = fixture.request();
        buildingRequest.setVersionId(98L);
        buildingRequest.setRuleId(500L);
        when(fixture.indexVersionDbService.getById(98L))
                .thenReturn(fixture.versionRow(98L, "v8", IndexVersionStatus.BUILDING.name(),
                        fixture.comboAll()));
        WorkbuddyException notReady = assertThrows(WorkbuddyException.class,
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
        SearchDTO request = fixture.request();
        request.setVersionId(30L);
        request.setRuleId(500L);
        when(fixture.indexVersionDbService.getById(30L))
                .thenReturn(fixture.versionRow(30L, "v9", IndexVersionStatus.READY.name(),
                        fixture.comboAll()));
        when(fixture.strategyVersionDbService.getById(500L)).thenReturn(null);
        WorkbuddyException missing = assertThrows(WorkbuddyException.class,
                () -> service.search(1L, request));
        assertEquals(ErrorCode.RETRIEVAL_RULE_NOT_FOUND, missing.getErrorCode());

        WbPipelineStrategyVersion wrongType = fixture.ruleRow();
        wrongType.setType("CHUNK");
        when(fixture.strategyVersionDbService.getById(500L)).thenReturn(wrongType);
        WorkbuddyException mismatch = assertThrows(WorkbuddyException.class,
                () -> service.search(1L, request));
        assertEquals(ErrorCode.RETRIEVAL_RULE_NOT_FOUND, mismatch.getErrorCode());
    }
}
