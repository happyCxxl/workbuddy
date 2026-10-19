package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.biz.mapper.WbFileResultMapper;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbFileResult;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件结果数据访问单测。
 *
 * <p>守护 {@code pageByKb}/{@code listByKb}/{@code countByKb}/{@code countGroupByKb}/
 * {@code countByKbIds} 的可见范围口径：全部按 knowledge_base_id 过滤，分组统计的
 * 别名与换算不漂移。
 */
@ExtendWith(MockitoExtension.class)
class WbFileResultDbServiceImplTest {

    @Mock
    private WbFileResultMapper mapper;

    private WbFileResultDbServiceImpl impl;

    /**
     * 注入替身 mapper 并初始化实体列缓存（LambdaWrapper 解析列名依赖 TableInfo）。
     */
    @BeforeEach
    void setUp() {
        impl = new WbFileResultDbServiceImpl();
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                WbFileResult.class);
    }

    /**
     * 分页按知识库过滤且按 ID 降序。
     *
     * <p>守护 {@code pageByKb}：丢掉过滤或排序方向写反即失败。
     */
    @Test
    @SuppressWarnings("unchecked")
    void pageByKbShouldFilterAndOrderDesc() {
        when(mapper.selectPage(any(), any(Wrapper.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var page = impl.pageByKb(1L, 20L, 9L);

        assertEquals(1L, page.getCurrent());
        ArgumentCaptor<Wrapper<WbFileResult>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(any(), captor.capture());
        String segment = ((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WbFileResult>)
                captor.getValue()).getTargetSql();
        assertTrue(segment.contains("knowledge_base_id"), () -> "实际 WHERE: " + segment);
        assertTrue(segment.contains("DESC"), () -> "实际排序: " + segment);
    }

    /**
     * 列表按知识库过滤且按 ID 升序。
     *
     * <p>守护 {@code listByKb}。
     */
    @Test
    @SuppressWarnings("unchecked")
    void listByKbShouldFilterAndOrderAsc() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(new WbFileResult()));

        List<WbFileResult> rows = impl.listByKb(9L);

        assertEquals(1, rows.size());
        ArgumentCaptor<Wrapper<WbFileResult>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        String segment = ((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WbFileResult>)
                captor.getValue()).getTargetSql();
        assertTrue(segment.contains("knowledge_base_id"), () -> "实际 WHERE: " + segment);
        assertTrue(segment.contains("ASC"), () -> "实际排序: " + segment);
    }

    /**
     * 单库计数按知识库过滤。
     *
     * <p>守护 {@code countByKb}：计数改全局即失败。
     */
    @Test
    void countByKbShouldFilterByKnowledgeBase() {
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(7L);

        assertEquals(7L, impl.countByKb(9L));

        ArgumentCaptor<Wrapper<WbFileResult>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectCount(captor.capture());
        String segment = ((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WbFileResult>)
                captor.getValue()).getTargetSql();
        assertTrue(segment.contains("knowledge_base_id"), () -> "实际 WHERE: " + segment);
    }

    /**
     * 分组统计：空入参短路；行值按 Number 换算成 Long 映射。
     *
     * <p>守护 {@code countGroupByKb} 的空入参兜底与换算。
     */
    @Test
    @SuppressWarnings("unchecked")
    void countGroupByKbShouldShortCircuitAndConvertRows() {
        assertEquals(Map.of(), impl.countGroupByKb(List.of()));
        assertEquals(Map.of(), impl.countGroupByKb(null));

        when(mapper.selectMaps(any(Wrapper.class))).thenReturn(List.of(
                Map.of("kbId", 1, "docCount", 5L),
                Map.of("kbId", 2, "docCount", 3L),
                new java.util.HashMap<>(Map.of("docCount", 9L))));

        Map<Long, Long> result = impl.countGroupByKb(List.of(1L, 2L));

        assertEquals(2, result.size());
        assertEquals(5L, result.get(1L));
        assertEquals(3L, result.get(2L));
    }

    /**
     * 多库计数：空入参短路，非空走 IN。
     *
     * <p>守护 {@code countByKbIds} 的空入参兜底。
     */
    @Test
    @SuppressWarnings("unchecked")
    void countByKbIdsShouldShortCircuitOnEmpty() {
        assertEquals(0L, impl.countByKbIds(List.of()));
        assertEquals(0L, impl.countByKbIds(null));

        when(mapper.selectCount(any(Wrapper.class))).thenReturn(12L);
        assertEquals(12L, impl.countByKbIds(List.of(1L, 2L)));
        ArgumentCaptor<Wrapper<WbFileResult>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectCount(captor.capture());
        String segment = ((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WbFileResult>)
                captor.getValue()).getTargetSql();
        assertTrue(segment.contains("IN"), () -> "实际 WHERE: " + segment);
    }
}
