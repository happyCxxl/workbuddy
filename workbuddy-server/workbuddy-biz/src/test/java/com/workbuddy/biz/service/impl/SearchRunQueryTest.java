package com.workbuddy.biz.service.impl;

import com.workbuddy.common.domain.entity.WbRetrievalRun;
import com.workbuddy.common.dto.response.retrieval.RetrievalRunVO;
import com.workbuddy.common.dto.response.retrieval.SearchVO;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索运行记录用例：对比回放读快照不重跑检索 / 跨库记录拒绝 / 入参防线 / 空耗时兜底 / 列表映射。
 *
 * <p>生产检索与测试台用例分别在 {@link SearchProductionTest}、{@link SearchBenchmarkTest}；
 * 装配与夹具数据见 {@link SearchTestFixture}。
 */
@ExtendWith(MockitoExtension.class)
class SearchRunQueryTest {

    private SearchTestFixture fixture;

    private SearchServiceImpl service;

    @BeforeEach
    void setUp() {
        fixture = new SearchTestFixture();
        service = fixture.service();
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
        when(fixture.retrievalRunDbService.listByIdsOrdered(List.of(1L))).thenReturn(List.of(run));

        List<SearchVO> vos = service.compareRuns(1L, List.of(1L));

        assertEquals(1, vos.size());
        assertEquals("保证金", vos.getFirst().getQuery());
        assertEquals("hybrid-rrf-k60-top10-v1", vos.getFirst().getRuleNameVersion());
        assertEquals("v2", vos.getFirst().getVersionNo());
        assertEquals(12L, vos.getFirst().getElapsedMs());
        assertEquals("c1", vos.getFirst().getHits().getFirst().getChunkId());
        assertEquals("快照内容", vos.getFirst().getHits().getFirst().getContent());
        assertEquals(1L, vos.getFirst().getRunId());
        verify(fixture.milvusIndexPort, never()).searchVector(anyString(), any());
        verify(fixture.milvusIndexPort, never()).searchFullText(anyString(), any());
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
        when(fixture.retrievalRunDbService.listByIdsOrdered(List.of(1L))).thenReturn(List.of(run));

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
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
        run.setCreateTime(LocalDateTime.of(2026, 10, 9, 10, 0));
        when(fixture.retrievalRunDbService.listByKb(9L, 5)).thenReturn(List.of(run));

        List<RetrievalRunVO> runs = service.listRuns(9L, 5);

        assertEquals(1, runs.size());
        assertEquals(5L, runs.get(0).getId());
        assertEquals(30L, runs.get(0).getVersionId());
        assertEquals("v9", runs.get(0).getVersionNo());
        assertEquals(500L, runs.get(0).getRuleId());
        assertEquals("hybrid-rrf-k60-top10-v1", runs.get(0).getRuleNameVersion());
        assertEquals("保证金", runs.get(0).getQuery());
        assertEquals(88L, runs.get(0).getElapsedMs().longValue());
        assertEquals(LocalDateTime.of(2026, 10, 9, 10, 0), runs.get(0).getCreateTime());
    }

    /**
     * 对比回放：runIds 为空或缺失按 40001 拒绝。
     *
     * <p>守护 {@code compareRuns} 的入参防线：空列表放行会返回空结果、掩盖调用错误。
     */
    @Test
    void compareRunsShouldRejectEmptyIds() {
        WorkbuddyException empty = assertThrows(WorkbuddyException.class,
                () -> service.compareRuns(1L, List.of()));
        assertEquals(ErrorCode.PARAM_INVALID, empty.getErrorCode());

        WorkbuddyException nullIds = assertThrows(WorkbuddyException.class,
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
        when(fixture.retrievalRunDbService.listByIdsOrdered(List.of(2L))).thenReturn(List.of(run));

        SearchVO vo = service.compareRuns(1L, List.of(2L)).getFirst();

        assertEquals("baseline-v0", vo.getRuleNameVersion());
        assertEquals("v2", vo.getVersionNo());
        assertEquals(0L, vo.getElapsedMs());
        assertEquals(2L, vo.getRunId());
    }
}
