package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.biz.mapper.WbChunkSetMapper;
import com.workbuddy.common.domain.entity.WbChunkSet;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 切片集合数据访问单测。
 *
 * <p>守护 {@code getByArtifactId}/{@code getLatestByFileResultId}/{@code listByFileResultId}/
 * {@code listByFileResultIds} 的口径：产物键定位、最新一条、按文件列举、批量短路。
 */
@ExtendWith(MockitoExtension.class)
class WbChunkSetDbServiceImplTest {

    @Mock
    private WbChunkSetMapper mapper;

    private WbChunkSetDbServiceImpl impl;

    /**
     * 注入替身 mapper 并初始化实体列缓存。
     */
    @BeforeEach
    void setUp() {
        impl = new WbChunkSetDbServiceImpl();
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                WbChunkSet.class);
    }

    /**
     * 产物键定位切片集合。
     *
     * <p>守护 {@code getByArtifactId}：键过滤缺失即失败。
     */
    @Test
    void getByArtifactIdShouldFilterByArtifact() {
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(null);

        assertNull(impl.getByArtifactId("chunk-artifact"));
        ArgumentCaptor<Wrapper<WbChunkSet>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectOne(captor.capture(), anyBoolean());
        LambdaQueryWrapper<WbChunkSet> wrapper = (LambdaQueryWrapper<WbChunkSet>) captor.getValue();
        assertTrue(wrapper.getTargetSql().contains("artifact_id"), () -> wrapper.getTargetSql());
        assertTrue(wrapper.getTargetSql().contains("LIMIT 1"), () -> wrapper.getTargetSql());
    }

    /**
     * 最新切片集合按 ID 降序取一。
     *
     * <p>守护 {@code getLatestByFileResultId}：排序写反即失败。
     */
    @Test
    void getLatestShouldOrderDescAndLimitOne() {
        WbChunkSet row = new WbChunkSet();
        row.setId(2L);
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(row);

        assertEquals(2L, impl.getLatestByFileResultId(9L).getId());
        ArgumentCaptor<Wrapper<WbChunkSet>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectOne(captor.capture(), anyBoolean());
        LambdaQueryWrapper<WbChunkSet> wrapper = (LambdaQueryWrapper<WbChunkSet>) captor.getValue();
        assertTrue(wrapper.getTargetSql().contains("DESC"), () -> wrapper.getTargetSql());
    }

    /**
     * 按文件列举按 ID 升序。
     *
     * <p>守护 {@code listByFileResultId}。
     */
    @Test
    void listByFileResultIdShouldFilterAndOrderAsc() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(new WbChunkSet()));

        assertEquals(1, impl.listByFileResultId(9L).size());
        ArgumentCaptor<Wrapper<WbChunkSet>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        LambdaQueryWrapper<WbChunkSet> wrapper = (LambdaQueryWrapper<WbChunkSet>) captor.getValue();
        assertTrue(wrapper.getTargetSql().contains("file_result_id"), () -> wrapper.getTargetSql());
        assertTrue(wrapper.getTargetSql().contains("ASC"), () -> wrapper.getTargetSql());
    }

    /**
     * 批量查询：空入参短路，非空走 IN。
     *
     * <p>守护 {@code listByFileResultIds} 的空入参兜底。
     */
    @Test
    void listByFileResultIdsShouldShortCircuitOnEmpty() {
        assertTrue(impl.listByFileResultIds(List.of()).isEmpty());
        verify(mapper, org.mockito.Mockito.never()).selectList(any(Wrapper.class));

        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(new WbChunkSet()));
        assertEquals(1, impl.listByFileResultIds(List.of(1L, 2L)).size());
        ArgumentCaptor<Wrapper<WbChunkSet>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        LambdaQueryWrapper<WbChunkSet> wrapper = (LambdaQueryWrapper<WbChunkSet>) captor.getValue();
        assertTrue(wrapper.getTargetSql().contains("IN"), () -> wrapper.getTargetSql());
    }
}
