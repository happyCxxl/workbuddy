package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.mapper.WbSubmitLogMapper;
import com.workbuddy.common.domain.entity.WbSubmitLog;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提交日志数据访问单测：幂等键单条查询、知识库分页的选填条件、跨库提交人分页。
 */
@ExtendWith(MockitoExtension.class)
class WbSubmitLogDbServiceImplTest {

    @Mock
    private WbSubmitLogMapper mapper;

    private WbSubmitLogDbServiceImpl impl;

    /**
     * 每个用例重建实现并初始化实体列缓存。
     */
    @BeforeEach
    void setUp() {
        impl = new WbSubmitLogDbServiceImpl();
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                WbSubmitLog.class);
    }

    /**
     * 幂等键查询：只取一条，命中返回日志、未命中返回 null。
     *
     * <p>守护 {@code getByRequestId} 的等值条件与单条上限：漏 LIMIT 会因多条命中抛异常。
     */
    @Test
    void getByRequestIdShouldQuerySingleRow() {
        when(mapper.selectOne(any(), eq(false))).thenReturn(null);

        assertNull(impl.getByRequestId("req-1"));
        verify(mapper).selectOne(argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            String segment = w.getSqlSegment();
            return segment.contains("request_id") && segment.contains("LIMIT 1")
                    && w.getParamNameValuePairs().containsValue("req-1");
        }), eq(false));
    }

    /**
     * 知识库分页：状态与文件名是选填条件，为空时不得进 WHERE。
     *
     * <p>守护 {@code pageByKb} 的条件开关：空串当条件会查出零行。
     */
    @Test
    void pageByKbShouldIgnoreBlankFilters() {
        Page<WbSubmitLog> expected = new Page<>(1, 10);
        Mockito.doReturn(expected).when(mapper).selectPage(any(), any());

        assertSame(expected, impl.pageByKb(1, 10, 7L, " ", null));
        verify(mapper).selectPage(any(), argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            String segment = w.getSqlSegment();
            return segment.contains("knowledge_base_id")
                    && !segment.contains("status")
                    && !segment.contains("file_name")
                    && segment.contains("DESC");
        }));
    }

    /**
     * 知识库分页：状态与文件名非空时按等值与模糊匹配进 WHERE。
     *
     * <p>守护 {@code pageByKb} 的过滤条件：文件名写成等值即查不到。
     */
    @Test
    void pageByKbShouldApplyFiltersWhenPresent() {
        Page<WbSubmitLog> expected = new Page<>(2, 5);
        Mockito.doReturn(expected).when(mapper).selectPage(any(), any());

        assertSame(expected, impl.pageByKb(2, 5, 7L, "SUCCESS", "报告"));

        verify(mapper).selectPage(any(), argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            String segment = w.getSqlSegment();
            return segment.contains("status") && segment.contains("LIKE")
                    && w.getParamNameValuePairs().containsValue("SUCCESS")
                    && w.getParamNameValuePairs().containsValue("%报告%");
        }));
    }

    /**
     * 提交人分页：只按用户隔离，不掺知识库条件。
     *
     * <p>守护 {@code pageRecentByUser} 的跨库口径：带 knowledge_base_id 条件即查成空页。
     */
    @Test
    void pageRecentByUserShouldFilterByUserOnly() {
        Page<WbSubmitLog> expected = new Page<>(1, 20);
        Mockito.doReturn(expected).when(mapper).selectPage(any(), any());

        assertSame(expected, impl.pageRecentByUser(1, 20, 42L));

        verify(mapper).selectPage(any(), argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            String segment = w.getSqlSegment();
            assertTrue(segment.contains("user_id"), () -> "实际 WHERE: " + segment);
            return !segment.contains("knowledge_base_id")
                    && w.getParamNameValuePairs().containsValue(42L)
                    && segment.contains("DESC");
        }));
    }

    /**
     * 按请求号命中时返回真实行。
     *
     * <p>守护 {@code getByRequestId} 的返回口径：恒 null 会让幂等判定失效。
     */
    @Test
    void getByRequestIdShouldReturnHitRow() {
        WbSubmitLog hit = new WbSubmitLog();
        hit.setId(11L);
        when(mapper.selectOne(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(hit);

        assertEquals(11L, impl.getByRequestId("req-1").getId().longValue());
    }
}
