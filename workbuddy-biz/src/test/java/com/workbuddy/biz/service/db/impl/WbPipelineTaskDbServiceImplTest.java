package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.biz.mapper.WbPipelineTaskMapper;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void finishShouldRejectTargetOutsideRunningAllowedSetBeforeAnySql() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> impl.finish(9L, PipelineTaskStatus.RUNNING, null, null));

        assertTrue(e.getMessage().contains("非法的任务状态迁移"), e.getMessage());
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void finishShouldReturnZeroWhenConditionalUpdateMatchesNoRow() {
        when(mapper.update(isNull(), any())).thenReturn(0);

        assertEquals(0, impl.finish(9L, PipelineTaskStatus.SUCCESS, null, null));

        verify(mapper).update(isNull(), any());
    }

    @Test
    void failQueuedShouldReturnZeroWhenClaimWon() {
        when(mapper.update(isNull(), any())).thenReturn(0);

        assertEquals(0, impl.failQueued(9L, "TASK_ENQUEUE_FAILED", "入队失败"));
    }
}
