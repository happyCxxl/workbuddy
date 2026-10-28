package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.biz.mapper.WbEmbeddingSetMapper;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
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
 * 向量集合数据访问单测。
 *
 * <p>守护 {@code getLatestByFileResultId}/{@code listHistoryByFileResultIdAndStrategyVersion}/
 * {@code getByArtifactId}/{@code listByFileResultIds} 的口径：最新一条按 ID 降序取一、
 * 复用回溯按「文件 + 策略版本 + ACTIVE」过滤并限条、产物键定位。
 */
@ExtendWith(MockitoExtension.class)
class WbEmbeddingSetDbServiceImplTest {

    @Mock
    private WbEmbeddingSetMapper mapper;

    private WbEmbeddingSetDbServiceImpl impl;

    /**
     * 注入替身 mapper 并初始化实体列缓存。
     */
    @BeforeEach
    void setUp() {
        impl = new WbEmbeddingSetDbServiceImpl();
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                WbEmbeddingSet.class);
    }

    /**
     * 最新向量集合：按文件过滤、按 ID 降序取一条。
     *
     * <p>守护 {@code getLatestByFileResultId}：排序写反即失败（拿到旧集合）。
     */
    @Test
    void getLatestShouldTakeLatestByFileResult() {
        WbEmbeddingSet row = new WbEmbeddingSet();
        row.setId(2L);
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(row);

        WbEmbeddingSet result = impl.getLatestByFileResultId(9L);

        assertEquals(2L, result.getId());
        ArgumentCaptor<Wrapper<WbEmbeddingSet>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectOne(captor.capture(), anyBoolean());
        LambdaQueryWrapper<WbEmbeddingSet> wrapper = (LambdaQueryWrapper<WbEmbeddingSet>) captor.getValue();
        assertTrue(wrapper.getTargetSql().contains("file_result_id"), () -> wrapper.getTargetSql());
        assertTrue(wrapper.getTargetSql().contains("DESC"), () -> wrapper.getTargetSql());
    }

    /**
     * 产物键定位集合。
     *
     * <p>守护 {@code getByArtifactId}：键过滤缺失即失败（按 artifactId 查错集合）。
     */
    @Test
    void getByArtifactIdShouldFilterByArtifact() {
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(null);

        assertNull(impl.getByArtifactId("embed-artifact"));
        ArgumentCaptor<Wrapper<WbEmbeddingSet>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectOne(captor.capture(), anyBoolean());
        LambdaQueryWrapper<WbEmbeddingSet> wrapper = (LambdaQueryWrapper<WbEmbeddingSet>) captor.getValue();
        assertTrue(wrapper.getTargetSql().contains("artifact_id"), () -> wrapper.getTargetSql());
        assertTrue(wrapper.getTargetSql().contains("LIMIT 1"), () -> wrapper.getTargetSql());
    }

    /**
     * 复用回溯查询：文件 + 策略版本 + ACTIVE 三条件，且限条。
     *
     * <p>守护 {@code listHistoryByFileResultIdAndStrategyVersion}：漏 ACTIVE 过滤即失败
     * （回收/失败的旧集合被拿去复用）。
     */
    @Test
    void listHistoryShouldFilterFileVersionAndActive() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(new WbEmbeddingSet()));

        List<WbEmbeddingSet> rows = impl.listHistoryByFileResultIdAndStrategyVersion(9L, "embed-v1", 5);

        assertEquals(1, rows.size());
        ArgumentCaptor<Wrapper<WbEmbeddingSet>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        LambdaQueryWrapper<WbEmbeddingSet> wrapper = (LambdaQueryWrapper<WbEmbeddingSet>) captor.getValue();
        String segment = wrapper.getTargetSql();
        assertTrue(segment.contains("file_result_id") && segment.contains("strategy_version"),
                () -> segment);
        // ACTIVE 走占位符，从参数对断言
        wrapper.getParamNameValuePairs();
        assertTrue(wrapper.getParamNameValuePairs().containsValue("ACTIVE"),
                () -> wrapper.getParamNameValuePairs().toString());
        assertTrue(segment.contains("LIMIT 5"), () -> segment);
    }

    /**
     * 批量查询：空入参短路。
     *
     * <p>守护 {@code listByFileResultIds} 的空入参兜底。
     */
    @Test
    void listByFileResultIdsShouldShortCircuitOnEmpty() {
        assertTrue(impl.listByFileResultIds(List.of()).isEmpty());
        verify(mapper, org.mockito.Mockito.never()).selectList(any(Wrapper.class));
    }

    /**
     * 按产物键命中时返回该行；按文件批量取时返回真实列表。
     *
     * <p>守护 {@code getByArtifactId}/{@code listByFileResultIds} 的返回口径。
     */
    @Test
    void artifactAndBatchQueriesShouldReturnRows() {
        WbEmbeddingSet hit = new WbEmbeddingSet();
        hit.setId(8L);
        when(mapper.selectOne(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(hit);
        assertEquals(8L, impl.getByArtifactId("embed-artifact").getId());

        when(mapper.selectList(any())).thenReturn(List.of(hit));
        assertEquals(1, impl.listByFileResultIds(List.of(1L)).size());
    }

    /**
     * 按文件取集合历史：命中返回真实列表（不是恒空）。
     *
     * <p>守护 {@code listByFileResultId} 的返回口径：恒空会让血缘丢向量段。
     */
    @Test
    void listByFileResultIdShouldReturnRows() {
        WbEmbeddingSet row = new WbEmbeddingSet();
        row.setId(4L);
        when(mapper.selectList(any())).thenReturn(List.of(row));

        assertEquals(1, impl.listByFileResultId(10L).size());
    }
}
