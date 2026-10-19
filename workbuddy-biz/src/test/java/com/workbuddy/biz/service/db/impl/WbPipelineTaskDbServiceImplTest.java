package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.biz.mapper.WbPipelineTaskMapper;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务数据访问单测：过期 QUEUED / 孤儿 RUNNING 查询条件、终态回写的迁移守卫与回写条件。
 */
@ExtendWith(MockitoExtension.class)
class WbPipelineTaskDbServiceImplTest {

    @Mock
    private WbPipelineTaskMapper mapper;

    private WbPipelineTaskDbServiceImpl impl;

    @BeforeEach
    void setUp() {
        impl = new WbPipelineTaskDbServiceImpl();
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);
        // 初始化实体列缓存（LambdaWrapper 解析列名依赖 TableInfo）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                WbPipelineTask.class);
    }

    /**
     * 补投候选：排队态 + 创建时间早于阈值。
     *
     * <p>守护 {@code listStaleQueued}：条件写错即补错任务。
     */
    @Test
    void listStaleQueuedShouldFilterQueuedBeforeThreshold() {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(1L);
        when(mapper.selectList(any())).thenReturn(List.of(task));
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);

        List<WbPipelineTask> result = impl.listStaleQueued(threshold);

        assertEquals(1, result.size());
        verify(mapper).selectList(argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            assertTrue(w.getSqlSegment().contains("status"));
            assertTrue(w.getSqlSegment().contains("create_time"));
            assertTrue(w.getParamNameValuePairs().containsValue("QUEUED"));
            return true;
        }));
    }

    /**
     * 孤儿运行：运行态 + 开始时间早于阈值。
     *
     * <p>守护 {@code listStaleRunning}：条件写错即误判孤儿。
     */
    @Test
    void listStaleRunningShouldFilterRunningBeforeThreshold() {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(2L);
        when(mapper.selectList(any())).thenReturn(List.of(task));
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);

        List<WbPipelineTask> result = impl.listStaleRunning(threshold);

        assertEquals(1, result.size());
        verify(mapper).selectList(argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            assertTrue(w.getSqlSegment().contains("status"));
            assertTrue(w.getSqlSegment().contains("started_at"));
            assertTrue(w.getParamNameValuePairs().containsValue("RUNNING"));
            return true;
        }));
    }

    /**
     * 终态回写：RUNNING 条件更新 + 结束时间与错误列。
     *
     * <p>守护 {@code finish}：条件或 SET 列缺失即并发写错。
     */
    @Test
    void finishShouldConditionallyUpdateRunningToTerminal() {
        when(mapper.update(isNull(), any())).thenReturn(1);

        int rows = impl.finish(9L, PipelineTaskStatus.FAILED, "EXECUTOR_TIMEOUT", "超时");

        assertEquals(1, rows);
        verify(mapper).update(isNull(), argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            assertTrue(w.getSqlSegment().contains("status"));
            assertTrue(w.getSqlSet().contains("status"));
            assertTrue(w.getSqlSet().contains("error_code"));
            assertTrue(w.getSqlSet().contains("error_msg"));
            assertTrue(w.getSqlSet().contains("finished_at"));
            assertTrue(w.getParamNameValuePairs().containsValue("RUNNING"));
            assertTrue(w.getParamNameValuePairs().containsValue("FAILED"));
            assertTrue(w.getParamNameValuePairs().containsValue("EXECUTOR_TIMEOUT"));
            assertTrue(w.getParamNameValuePairs().containsValue("超时"));
            assertTrue(w.getParamNameValuePairs().values().stream().anyMatch(LocalDateTime.class::isInstance));
            return true;
        }));
    }

    /**
     * 终态回写：非法目标状态在 SQL 前拒绝。
     *
     * <p>守护 {@code finish}：非法迁移落库即失败。
     */
    @Test
    void finishShouldRejectTargetOutsideRunningAllowedSetBeforeAnySql() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> impl.finish(9L, PipelineTaskStatus.RUNNING, null, null));

        assertTrue(e.getMessage().contains("非法的任务状态迁移"), e.getMessage());
        verify(mapper, never()).update(any(), any());
    }

    /**
     * 终态回写：条件未命中返回 0。
     *
     * <p>守护 {@code finish}：未命中也报成功即失败。
     */
    @Test
    void finishShouldReturnZeroWhenConditionalUpdateMatchesNoRow() {
        when(mapper.update(isNull(), any())).thenReturn(0);

        assertEquals(0, impl.finish(9L, PipelineTaskStatus.SUCCESS, null, null));

        verify(mapper).update(isNull(), any());
    }

    /**
     * 排队置失败：已被抢走时返回 0。
     *
     * <p>守护 {@code failQueued}：未命中报成功即失败。
     */
    @Test
    void failQueuedShouldReturnZeroWhenClaimWon() {
        when(mapper.update(isNull(), any())).thenReturn(0);

        assertEquals(0, impl.failQueued(9L, "TASK_ENQUEUE_FAILED", "入队失败"));
    }

    /**
     * 排队态置失败：命中排队行时返回真实影响行数（不是恒 0）。
     *
     * <p>守护 {@code failQueued}/{@code finishFrom} 的返回值：恒 0 会让调用方误判"状态已被别人改过"。
     */
    @Test
    void failQueuedShouldReturnAffectedRows() {
        when(mapper.update(isNull(), any())).thenReturn(1);

        assertEquals(1, impl.failQueued(9L, "TASK_ENQUEUE_FAILED", "入队失败"));

        verify(mapper).update(isNull(), argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            // WHERE 参数惰性物化：先取 SQL 片段再断言参数集合
            w.getSqlSegment();
            String set = w.getSqlSet();
            return set.contains("status") && set.contains("error_code") && set.contains("error_msg")
                    && set.contains("finished_at")
                    && w.getParamNameValuePairs().containsValue("QUEUED")
                    && w.getParamNameValuePairs().containsValue("FAILED");
        }));
    }

    /**
     * 执行键查询：四元组等值条件 + 最近一条。
     *
     * <p>守护 {@code getByExecKey} 的幂等键口径：少一个等值条件即失败（同键重复投递查错任务）。
     */
    @Test
    void getByExecKeyShouldMatchAllFourKeysAndTakeLatest() {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(1L);
        when(mapper.selectOne(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(task);

        WbPipelineTask result = impl.getByExecKey(7L, "PARSE", 3L, 4L);

        assertEquals(1L, result.getId());
        verify(mapper, org.mockito.Mockito.atLeastOnce()).selectOne(argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            String segment = w.getSqlSegment();
            return segment.contains("file_result_id") && segment.contains("stage")
                    && segment.contains("upstream_product_id") && segment.contains("strategy_version_id")
                    && segment.contains("LIMIT 1");
        }), org.mockito.ArgumentMatchers.eq(false));
    }

    /**
     * 按文件与环节取最近一条。
     *
     * <p>守护 {@code getByFileResultIdAndStage} 的等值条件与 LIMIT。
     */
    @Test
    void getByFileResultIdAndStageShouldMatchPairAndTakeLatest() {
        when(mapper.selectOne(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(null);

        assertNull(impl.getByFileResultIdAndStage(7L, "CHUNK"));
        // 命中时必须返回该行（恒返回 null 会让"最新任务"永远查不到）
        WbPipelineTask hit = new WbPipelineTask();
        hit.setId(5L);
        when(mapper.selectOne(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(hit);
        assertEquals(5L, impl.getByFileResultIdAndStage(7L, "CHUNK").getId());
        // 同一查询执行两次（先空后有）：按至少一次验证条件
        verify(mapper, org.mockito.Mockito.atLeastOnce()).selectOne(argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            String segment = w.getSqlSegment();
            return segment.contains("file_result_id") && segment.contains("stage")
                    && segment.contains("LIMIT 1");
        }), org.mockito.ArgumentMatchers.eq(false));
    }

    /**
     * 批量同环节查询：空入参短路不落 SQL，非空走 IN。
     *
     * <p>守护 {@code listByFileResultIdsAndStage} 的空入参兜底。
     */
    @Test
    void listByFileResultIdsAndStageShouldShortCircuitOnEmpty() {
        assertEquals(List.of(), impl.listByFileResultIdsAndStage(List.of(), "PARSE"));
        assertEquals(List.of(), impl.listByFileResultIdsAndStage(null, "PARSE"));
        verify(mapper, never()).selectList(any());
    }

    /**
     * 按状态集合查询只带三个字段（轻量口径）。
     *
     * <p>守护 {@code listByStatuses} 的投影与 IN 条件。
     */
    @Test
    void listByStatusesShouldSelectIdAndFileResultAndStatus() {
        when(mapper.selectList(any())).thenReturn(List.of(new WbPipelineTask()));

        List<WbPipelineTask> result = impl.listByStatuses(
                List.of(PipelineTaskStatus.QUEUED, PipelineTaskStatus.RUNNING));

        assertEquals(1, result.size());
        verify(mapper).selectList(argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            String select = w.getSqlSelect();
            return select.contains("id") && select.contains("file_result_id") && select.contains("status")
                    && w.getSqlSegment().contains("IN");
        }));
    }

    /**
     * 按环节取排队任务：空环节短路，非空带 IN 与 LIMIT。
     *
     * <p>守护 {@code listQueuedByStages} 的空入参兜底与条数上限。
     */
    @Test
    void listQueuedByStagesShouldShortCircuitAndLimit() {
        assertEquals(List.of(), impl.listQueuedByStages(List.of(), 10));
        assertEquals(List.of(), impl.listQueuedByStages(null, 10));
        when(mapper.selectList(any())).thenReturn(List.of(new WbPipelineTask()));

        impl.listQueuedByStages(List.of(PipelineStage.PARSE, PipelineStage.CHUNK), 10);

        verify(mapper).selectList(argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            return w.getSqlSegment().contains("IN") && w.getSqlSegment().contains("LIMIT 10")
                    && w.getParamNameValuePairs().containsValue("QUEUED");
        }));
    }

    /**
     * 抢占任务：条件更新 QUEUED→RUNNING 并写开始时间。
     *
     * <p>守护 {@code claim} 的迁移守卫与回写列。
     */
    @Test
    @SuppressWarnings("unchecked")
    void claimShouldConditionallyMoveQueuedToRunning() {
        when(mapper.update(isNull(), any())).thenReturn(1);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<WbPipelineTask>> wrapperCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);

        assertEquals(1, impl.claim(9L));

        verify(mapper).update(isNull(), wrapperCaptor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) wrapperCaptor.getValue();
        // MP 的 where 条件参数惰性求值：先取 SQL 片段让占位符物化，再断言参数值
        String segment = wrapper.getSqlSegment();
        assertTrue(segment.contains("id =") && segment.contains("status ="),
                () -> "实际 WHERE: " + segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("QUEUED"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("RUNNING"));
        assertTrue(wrapper.getSqlSet().contains("started_at"));
    }

    /**
     * 回写产物 ID。
     *
     * <p>守护 {@code updateProductId} 的回写列。
     */
    @Test
    void updateProductIdShouldSetProductId() {
        impl.updateProductId(9L, 77L);

        verify(mapper).update(isNull(), argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            return w.getSqlSet().contains("product_id")
                    && w.getParamNameValuePairs().containsValue(77L);
        }));

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<WbPipelineTask>> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> setWrapper =
                (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) captor.getValue();
        setWrapper.getSqlSegment();
        assertTrue(setWrapper.getSqlSet().contains("product_id"));
        assertTrue(setWrapper.getParamNameValuePairs().containsValue(77L));
    }

    /**
     * 按文件取全部任务（时间线口径，升序）。
     *
     * <p>守护 {@code listByFileResultId} 的等值条件与升序。
     */
    @Test
    void listByFileResultIdShouldFilterByFileAndOrderAsc() {
        when(mapper.selectList(any())).thenReturn(List.of(new WbPipelineTask(), new WbPipelineTask()));

        assertEquals(2, impl.listByFileResultId(7L).size());

        verify(mapper).selectList(argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            return w.getSqlSegment().contains("file_result_id")
                    && w.getSqlSegment().contains("ASC");
        }));
    }

    /**
     * 按环节取排队任务：非空入参返回真实列表（不是恒空）。
     *
     * <p>守护 {@code listQueuedByStages} 的返回口径：恒空会让队列补投永远认为无任务。
     */
    @Test
    void listQueuedByStagesShouldReturnRowsForStages() {
        WbPipelineTask row = new WbPipelineTask();
        row.setId(9L);
        when(mapper.selectList(any())).thenReturn(List.of(row));

        var rows = impl.listQueuedByStages(List.of(com.workbuddy.common.enums.task.PipelineStage.CHUNK), 10);

        assertEquals(1, rows.size());
        assertEquals(9L, rows.getFirst().getId());
    }

    /**
     * 按文件集合与环节取任务：非空入参返回真实列表。
     *
     * <p>守护 {@code listByFileResultIdsAndStage} 的返回口径：恒空会让批量详情丢任务。
     */
    @Test
    void listByFileResultIdsAndStageShouldReturnRows() {
        WbPipelineTask row = new WbPipelineTask();
        row.setId(12L);
        when(mapper.selectList(any())).thenReturn(List.of(row));

        var rows = impl.listByFileResultIdsAndStage(List.of(10L, 11L), "CHUNK");

        assertEquals(1, rows.size());
        assertEquals(12L, rows.getFirst().getId());
    }
}
