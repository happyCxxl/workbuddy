package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.biz.mapper.WbChunkMapper;
import com.workbuddy.common.dto.request.stage.ChunkContentFilterDTO;
import com.workbuddy.common.domain.entity.WbChunk;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 切片数据访问单测。
 *
 * <p>守护 {@code listByChunkSetId}/{@code pageByChunkSetId} 的排序口径
 * （order_no → id）与过滤条件下推（类型、兜底、有无父片）。
 */
@ExtendWith(MockitoExtension.class)
class WbChunkDbServiceImplTest {

    @Mock
    private WbChunkMapper mapper;

    private WbChunkDbServiceImpl impl;

    /**
     * 注入替身 mapper 并初始化实体列缓存。
     */
    @BeforeEach
    void setUp() {
        impl = new WbChunkDbServiceImpl();
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                WbChunk.class);
    }

    /**
     * 列表按集合过滤且按 order_no → id 排序。
     *
     * <p>守护 {@code listByChunkSetId}：丢排序即失败（内容顺序失真）。
     */
    @Test
    void listByChunkSetIdShouldOrderByOrderNoThenId() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(new WbChunk()));

        List<WbChunk> rows = impl.listByChunkSetId(3L);

        assertEquals(1, rows.size());
        String segment = captureSelectListSql();
        assertTrue(segment.contains("chunk_set_id"), () -> "实际 WHERE: " + segment);
        assertTrue(segment.contains("order_no") && segment.contains("ASC"), () -> "实际排序: " + segment);
    }

    /**
     * 无过滤条件时只按集合查。
     *
     * <p>守护 {@code applyFilter} 的空过滤兜底。
     */
    @Test
    void pageShouldNotFilterWhenFilterAbsent() {
        when(mapper.selectPage(any(), any(Wrapper.class))).thenAnswer(invocation -> invocation.getArgument(0));

        impl.pageByChunkSetId(3L, null, 1L, 20L);

        String segment = captureSelectPageSql();
        assertTrue(segment.contains("chunk_set_id"), () -> "实际 WHERE: " + segment);
        assertFalse(segment.contains("content_type"), () -> "不应出现类型过滤: " + segment);
    }

    /**
     * 类型过滤与兜底三态（eq / ne / 不加）各下推正确。
     *
     * <p>守护 {@code applyFilter} 的兜底分支：fallback 真假写串即失败。
     */
    @Test
    void pageShouldPushContentTypeAndFallbackFilter() {
        when(mapper.selectPage(any(), any(Wrapper.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ArgumentCaptor<Wrapper<WbChunk>> captor = ArgumentCaptor.forClass(Wrapper.class);

        impl.pageByChunkSetId(3L, new ChunkContentFilterDTO("PARAGRAPH", null, null), 1L, 20L);
        impl.pageByChunkSetId(3L, new ChunkContentFilterDTO(null, true, null), 1L, 20L);
        impl.pageByChunkSetId(3L, new ChunkContentFilterDTO(null, false, null), 1L, 20L);
        verify(mapper, org.mockito.Mockito.times(3)).selectPage(any(), captor.capture());

        List<LambdaQueryWrapper<WbChunk>> wrappers = captor.getAllValues().stream()
                .map(wrapper -> (LambdaQueryWrapper<WbChunk>) wrapper)
                .toList();
        // 类型值走占位符，触发求值后从参数对断言
        wrappers.get(0).getTargetSql();
        assertTrue(wrappers.get(0).getParamNameValuePairs().containsValue("PARAGRAPH"),
                () -> "类型值缺失: " + wrappers.get(0).getParamNameValuePairs());
        wrappers.get(1).getTargetSql();
        assertTrue(wrappers.get(1).getParamNameValuePairs().containsValue("FALLBACK"),
                () -> "兜底真值缺失: " + wrappers.get(1).getParamNameValuePairs());
        assertTrue(wrappers.get(2).getTargetSql().contains("<>"), "兜底假应为不等于过滤");
    }

    /**
     * 有无父片三态：真=有父片（非空非空串），假=无父片（NULL 或空串）。
     *
     * <p>守护 {@code applyFilter} 的 parent 分支：把空串当有父片即失败。
     */
    @Test
    void pageShouldPushParentFilter() {
        when(mapper.selectPage(any(), any(Wrapper.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ArgumentCaptor<Wrapper<WbChunk>> captor = ArgumentCaptor.forClass(Wrapper.class);

        impl.pageByChunkSetId(3L, new ChunkContentFilterDTO(null, null, true), 1L, 20L);
        impl.pageByChunkSetId(3L, new ChunkContentFilterDTO(null, null, false), 1L, 20L);
        verify(mapper, org.mockito.Mockito.times(2)).selectPage(any(), captor.capture());

        List<LambdaQueryWrapper<WbChunk>> wrappers = captor.getAllValues().stream()
                .map(wrapper -> (LambdaQueryWrapper<WbChunk>) wrapper)
                .toList();
        String hasParent = wrappers.get(0).getTargetSql();
        assertTrue(hasParent.contains("IS NOT NULL") && hasParent.contains("<>"), () -> "实际: " + hasParent);
        String noParent = wrappers.get(1).getTargetSql();
        assertTrue(noParent.contains("IS NULL") && noParent.contains("OR"), () -> "实际: " + noParent);
    }

    /**
     * 捕获 selectList 的 WHERE 片段。
     *
     * @return SQL 片段
     */
    @SuppressWarnings("unchecked")
    private String captureSelectListSql() {
        ArgumentCaptor<Wrapper<WbChunk>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        return ((LambdaQueryWrapper<WbChunk>) captor.getValue()).getTargetSql();
    }

    /**
     * 捕获 selectPage 的 WHERE 片段。
     *
     * @return SQL 片段
     */
    @SuppressWarnings("unchecked")
    private String captureSelectPageSql() {
        ArgumentCaptor<Wrapper<WbChunk>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(any(), captor.capture());
        return ((LambdaQueryWrapper<WbChunk>) captor.getValue()).getTargetSql();
    }

    /**
     * 捕获 selectPage 的 wrapper。
     *
     * @return Lambda wrapper
     */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<WbChunk> captureSelectPageWrapper() {
        ArgumentCaptor<Wrapper<WbChunk>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(any(), captor.capture());
        return (LambdaQueryWrapper<WbChunk>) captor.getValue();
    }
}
