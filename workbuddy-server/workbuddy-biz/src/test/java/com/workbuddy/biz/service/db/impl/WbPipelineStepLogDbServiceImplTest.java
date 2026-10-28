package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.biz.mapper.WbPipelineStepLogMapper;
import com.workbuddy.common.domain.entity.WbPipelineStepLog;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 子步骤记录数据访问单测：单任务查询的升序、批量查询的空入参兜底与 IN 条件。
 */
@ExtendWith(MockitoExtension.class)
class WbPipelineStepLogDbServiceImplTest {

    @Mock
    private WbPipelineStepLogMapper mapper;

    private WbPipelineStepLogDbServiceImpl impl;

    /**
     * 每个用例重建实现并初始化实体列缓存。
     */
    @BeforeEach
    void setUp() {
        impl = new WbPipelineStepLogDbServiceImpl();
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                WbPipelineStepLog.class);
    }

    /**
     * 单任务按步骤号升序。
     *
     * <p>守护 {@code listByTaskId} 的排序：降序会让步骤时间线倒放。
     */
    @Test
    void listByTaskIdShouldOrderAsc() {
        when(mapper.selectList(any())).thenReturn(List.of(new WbPipelineStepLog()));

        assertEquals(1, impl.listByTaskId(9L).size());

        verify(mapper).selectList(argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            String segment = w.getSqlSegment();
            return segment.contains("task_id") && segment.contains("ASC")
                    && w.getParamNameValuePairs().containsValue(9L);
        }));
    }

    /**
     * 批量查询空入参短路，不落空 IN。
     *
     * <p>守护 {@code listByTaskIds} 的空集合兜底。
     */
    @Test
    void listByTaskIdsShouldShortCircuitOnEmpty() {
        assertEquals(List.of(), impl.listByTaskIds(null));
        assertEquals(List.of(), impl.listByTaskIds(List.of()));
        verify(mapper, never()).selectList(any());
    }

    /**
     * 批量查询非空走 IN 且升序。
     *
     * <p>守护 {@code listByTaskIds} 的 IN 条件：写成等值只会取到一个任务的步骤。
     */
    @Test
    void listByTaskIdsShouldUseInClause() {
        when(mapper.selectList(any())).thenReturn(List.of(new WbPipelineStepLog(), new WbPipelineStepLog()));

        assertEquals(2, impl.listByTaskIds(List.of(1L, 2L)).size());

        verify(mapper).selectList(argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            return w.getSqlSegment().contains("IN") && w.getSqlSegment().contains("ASC");
        }));
    }
}
