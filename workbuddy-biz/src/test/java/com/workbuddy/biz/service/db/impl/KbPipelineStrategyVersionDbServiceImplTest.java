package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.biz.mapper.KbPipelineStrategyVersionMapper;
import com.workbuddy.common.domain.entity.KbPipelineStrategyVersion;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 策略版本数据访问服务单测：按类型查询语义（状态过滤/排序/取一）。
 * 注：LambdaQueryWrapper 的列名解析依赖实体 lambda 缓存，测试内先初始化。
 *
 * @author cxxl
 */
class KbPipelineStrategyVersionDbServiceImplTest {

    private KbPipelineStrategyVersionMapper mapper;

    private KbPipelineStrategyVersionDbServiceImpl impl;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                KbPipelineStrategyVersion.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(KbPipelineStrategyVersionMapper.class);
        impl = new KbPipelineStrategyVersionDbServiceImpl();
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);
    }

    @Test
    void listEnabledByTypeShouldFilterStatusAndOrderByIdDesc() {
        KbPipelineStrategyVersion row = new KbPipelineStrategyVersion();
        row.setId(9L);
        row.setVersion("v1");
        when(mapper.selectList(any())).thenReturn(java.util.List.of(row));

        java.util.List<KbPipelineStrategyVersion> result = impl.listEnabledByType("CHUNK");

        assertEquals(1, result.size());
        assertEquals("v1", result.getFirst().getVersion());
        ArgumentCaptor<Wrapper<KbPipelineStrategyVersion>> captor = ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.Mockito.verify(mapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("type"), sql);
        assertTrue(sql.contains("status"), sql);
        assertTrue(sql.contains("ORDER BY id DESC"), sql);
    }

    @Test
    void listByTypeShouldNotFilterStatus() {
        when(mapper.selectList(any())).thenReturn(java.util.List.of());

        impl.listByType("CHUNK");

        ArgumentCaptor<Wrapper<KbPipelineStrategyVersion>> captor = ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.Mockito.verify(mapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("type"), sql);
        assertTrue(!sql.contains("status"), sql);
        assertTrue(sql.contains("ORDER BY id DESC"), sql);
    }
}
