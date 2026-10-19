package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.biz.mapper.WbPipelineProductMapper;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段产物数据访问单测：三种查询的过滤条件、排序与单条上限。
 */
@ExtendWith(MockitoExtension.class)
class WbPipelineProductDbServiceImplTest {

    @Mock
    private WbPipelineProductMapper mapper;

    private WbPipelineProductDbServiceImpl impl;

    /**
     * 每个用例重建实现并初始化实体列缓存。
     */
    @BeforeEach
    void setUp() {
        impl = new WbPipelineProductDbServiceImpl();
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                WbPipelineProduct.class);
    }

    /**
     * 按任务取产物：任务过滤 + 最新一条（降序 + LIMIT 1）。
     *
     * <p>守护 {@code getByTaskId}：漏任务条件会取到别的任务、漏排序会取到旧产物。
     */
    @Test
    void getByTaskIdShouldTakeLatestOfTask() {
        when(mapper.selectOne(any(), eq(false))).thenReturn(null);

        assertNull(impl.getByTaskId(9L));

        verify(mapper).selectOne(argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            String segment = w.getSqlSegment();
            return segment.contains("task_id") && segment.contains("DESC") && segment.contains("LIMIT 1")
                    && w.getParamNameValuePairs().containsValue(9L);
        }), eq(false));
    }

    /**
     * 按文件与环节取产物：两个等值条件 + 最新一条。
     *
     * <p>守护 {@code getByFileResultIdAndStage}：少环节条件会串到别的环节的产物。
     */
    @Test
    void getByFileResultIdAndStageShouldMatchPair() {
        WbPipelineProduct row = new WbPipelineProduct();
        row.setId(5L);
        when(mapper.selectOne(any(), eq(false))).thenReturn(row);

        assertEquals(5L, impl.getByFileResultIdAndStage(7L, "CHUNK").getId());

        verify(mapper).selectOne(argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            String segment = w.getSqlSegment();
            return segment.contains("file_result_id") && segment.contains("stage")
                    && segment.contains("LIMIT 1")
                    && w.getParamNameValuePairs().containsValue("CHUNK");
        }), eq(false));
    }

    /**
     * 按文件取全部产物：按 ID 升序，不带 LIMIT。
     *
     * <p>守护 {@code listByFileResultId} 的升序口径：降序会让时间线倒着走。
     */
    @Test
    void listByFileResultIdShouldOrderAsc() {
        when(mapper.selectList(any())).thenReturn(List.of(new WbPipelineProduct()));

        assertEquals(1, impl.listByFileResultId(7L).size());

        verify(mapper).selectList(argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            String segment = w.getSqlSegment();
            return segment.contains("file_result_id") && segment.contains("ASC") && !segment.contains("LIMIT");
        }));
    }
}
