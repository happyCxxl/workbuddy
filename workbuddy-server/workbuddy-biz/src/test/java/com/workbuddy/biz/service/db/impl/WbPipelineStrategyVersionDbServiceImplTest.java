package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.biz.mapper.WbPipelineStrategyVersionMapper;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 策略版本数据访问服务单测：按类型查询语义（状态过滤/排序/取一）。
 * 注：LambdaQueryWrapper 的列名解析依赖实体 lambda 缓存，测试内先初始化。
 */
class WbPipelineStrategyVersionDbServiceImplTest {

    private WbPipelineStrategyVersionMapper mapper;

    private WbPipelineStrategyVersionDbServiceImpl impl;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                WbPipelineStrategyVersion.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(WbPipelineStrategyVersionMapper.class);
        impl = new WbPipelineStrategyVersionDbServiceImpl();
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);
    }

    /**
     * 校验点：{@code listEnabledByTypeShouldFilterStatusAndOrderByIdDesc}。
     */
    @Test
    void listEnabledByTypeShouldFilterStatusAndOrderByIdDesc() {
        WbPipelineStrategyVersion row = new WbPipelineStrategyVersion();
        row.setId(9L);
        row.setVersion("v1");
        when(mapper.selectList(any())).thenReturn(java.util.List.of(row));

        java.util.List<WbPipelineStrategyVersion> result = impl.listEnabledByType("CHUNK");

        assertEquals(1, result.size());
        assertEquals("v1", result.getFirst().getVersion());
        ArgumentCaptor<Wrapper<WbPipelineStrategyVersion>> captor = ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.Mockito.verify(mapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("type"), sql);
        assertTrue(sql.contains("status"), sql);
        assertTrue(sql.contains("ORDER BY id DESC"), sql);
    }

    /**
     * 校验点：{@code listByTypeShouldNotFilterStatus}。
     */
    @Test
    void listByTypeShouldNotFilterStatus() {
        when(mapper.selectList(any())).thenReturn(java.util.List.of());

        impl.listByType("CHUNK");

        ArgumentCaptor<Wrapper<WbPipelineStrategyVersion>> captor = ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.Mockito.verify(mapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("type"), sql);
        assertTrue(!sql.contains("status"), sql);
        assertTrue(sql.contains("ORDER BY id DESC"), sql);
    }

    /**
     * 按类型取列表/按 name-version 取行/启用计数都返回真实值。
     *
     * <p>守护 {@code listByType}/{@code getByTypeAndNameAndVersion}/{@code countEnabledByType} 的返回口径。
     */
    @Test
    void strategyQueriesShouldReturnRealValues() {
        WbPipelineStrategyVersion row = new WbPipelineStrategyVersion();
        row.setId(3L);
        row.setName("chunk-hybrid");
        row.setVersion("v1");
        when(mapper.selectList(any())).thenReturn(List.of(row));
        when(mapper.selectOne(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(row);
        when(mapper.selectCount(any())).thenReturn(2L);

        assertEquals(1, impl.listByType("CHUNK").size());
        assertEquals(3L, impl.getByTypeAndNameAndVersion("CHUNK", "chunk-hybrid", "v1").getId());
        assertEquals(2L, impl.countEnabledByType(com.workbuddy.common.enums.strategy.StrategyType.CHUNK));
    }
}
