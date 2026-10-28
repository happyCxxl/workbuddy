package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.biz.mapper.WbStrategyBindingMapper;
import com.workbuddy.common.domain.entity.WbStrategyBinding;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 策略绑定数据访问单测。
 *
 * <p>守护 {@code getByKbAndType}/{@code getAnyByKbAndType}/{@code listActiveByTypeAndKbIds}/
 * {@code existsByStrategyVersionId} 的口径：删除过滤的宽严两档、批量绑定查询、
 * 删除防线（有绑定禁删策略版本）。
 */
@ExtendWith(MockitoExtension.class)
class WbStrategyBindingDbServiceImplTest {

    @Mock
    private WbStrategyBindingMapper mapper;

    private WbStrategyBindingDbServiceImpl impl;

    /**
     * 注入替身 mapper 并初始化实体列缓存。
     */
    @BeforeEach
    void setUp() {
        impl = new WbStrategyBindingDbServiceImpl();
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                WbStrategyBinding.class);
    }

    /**
     * 按「库 + 类型」取绑定（只取未删除）。
     *
     * <p>守护 {@code getByKbAndType}：两条件缺一即失败（跨库串绑定）。
     */
    @Test
    void getByKbAndTypeShouldMatchKbAndType() {
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(null);

        assertNull(impl.getByKbAndType(9L, "CHUNK"));
        ArgumentCaptor<Wrapper<WbStrategyBinding>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectOne(captor.capture(), anyBoolean());
        LambdaQueryWrapper<WbStrategyBinding> wrapper = (LambdaQueryWrapper<WbStrategyBinding>) captor.getValue();
        String segment = wrapper.getTargetSql();
        assertTrue(segment.contains("knowledge_base_id") && segment.contains("strategy_type"),
                () -> segment);
        assertTrue(segment.contains("LIMIT 1"), () -> segment);
    }

    /**
     * 宽松口径（含已删除）多一个 del_flag IN 条件。
     *
     * <p>守护 {@code getAnyByKbAndType}：与严格口径无差异即失败（删除过滤丢失）。
     */
    @Test
    void getAnyShouldIncludeDeletedRows() {
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(null);

        assertNull(impl.getAnyByKbAndType(9L, "CHUNK"));
        ArgumentCaptor<Wrapper<WbStrategyBinding>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectOne(captor.capture(), anyBoolean());
        LambdaQueryWrapper<WbStrategyBinding> wrapper = (LambdaQueryWrapper<WbStrategyBinding>) captor.getValue();
        assertTrue(wrapper.getTargetSql().contains("del_flag"), () -> wrapper.getTargetSql());
    }

    /**
     * 批量绑定查询：空入参短路，非空按类型与库集合 IN。
     *
     * <p>守护 {@code listActiveByTypeAndKbIds} 的空入参兜底。
     */
    @Test
    void listActiveShouldShortCircuitAndFilterByTypeAndKbs() {
        assertTrue(impl.listActiveByTypeAndKbIds("CHUNK", List.of()).isEmpty());
        verify(mapper, org.mockito.Mockito.never()).selectList(any(Wrapper.class));

        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(new WbStrategyBinding()));
        assertEquals(1, impl.listActiveByTypeAndKbIds("CHUNK", List.of(1L, 2L)).size());
        ArgumentCaptor<Wrapper<WbStrategyBinding>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        LambdaQueryWrapper<WbStrategyBinding> wrapper = (LambdaQueryWrapper<WbStrategyBinding>) captor.getValue();
        String segment = wrapper.getTargetSql();
        assertTrue(segment.contains("strategy_type") && segment.contains("IN"), () -> segment);
    }

    /**
     * 删除防线：按策略版本查绑定存在性。
     *
     * <p>守护 {@code existsByStrategyVersionId}：计数恒 0 即失败（有绑定的版本被误删）。
     */
    @Test
    void existsShouldFollowCountResult() {
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertTrue(impl.existsByStrategyVersionId(3L));

        when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        assertFalse(impl.existsByStrategyVersionId(3L));

        ArgumentCaptor<Wrapper<WbStrategyBinding>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, org.mockito.Mockito.times(2)).selectCount(captor.capture());
        LambdaQueryWrapper<WbStrategyBinding> wrapper = (LambdaQueryWrapper<WbStrategyBinding>) captor.getValue();
        assertTrue(wrapper.getTargetSql().contains("strategy_version_id"), () -> wrapper.getTargetSql());
        assertTrue(wrapper.getTargetSql().contains("LIMIT 1"), () -> wrapper.getTargetSql());
    }

    /**
     * 按库与类型命中时返回真实行（含含退役行的宽松查询）。
     *
     * <p>守护 {@code getByKbAndType}/{@code getAnyByKbAndType} 的返回口径：恒 null 会让绑定看起来永远缺失。
     */
    @Test
    void bindingQueriesShouldReturnHitRow() {
        WbStrategyBinding hit = new WbStrategyBinding();
        hit.setId(9L);
        when(mapper.selectOne(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(hit);

        assertEquals(9L, impl.getByKbAndType(1L, "CHUNK").getId());
        assertEquals(9L, impl.getAnyByKbAndType(1L, "CHUNK").getId());
    }
}
