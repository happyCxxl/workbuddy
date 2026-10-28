package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.biz.mapper.WbRetrievalRunMapper;
import com.workbuddy.common.domain.entity.WbRetrievalRun;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索运行记录数据访问单测：条数上限的开关与批量回填的顺序语义。
 */
@ExtendWith(MockitoExtension.class)
class WbRetrievalRunDbServiceImplTest {

    @Mock
    private WbRetrievalRunMapper mapper;

    private WbRetrievalRunDbServiceImpl impl;

    /**
     * 每个用例重建实现并初始化实体列缓存。
     */
    @BeforeEach
    void setUp() {
        impl = new WbRetrievalRunDbServiceImpl();
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                WbRetrievalRun.class);
    }

    /**
     * 条数上限为正才拼 LIMIT，非正数表示不限。
     *
     * <p>守护 {@code listByKb} 的上限开关：非正数也拼 LIMIT 会静默截断成 0 行。
     */
    @Test
    void listByKbShouldAppendLimitOnlyWhenPositive() {
        when(mapper.selectList(any())).thenReturn(List.of(new WbRetrievalRun()));

        assertEquals(1, impl.listByKb(3L, 5).size());

        verify(mapper).selectList(argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            String segment = w.getSqlSegment();
            return segment.contains("kb_id") && segment.contains("LIMIT 5") && segment.contains("DESC");
        }));
    }

    /**
     * 上限为 0 时不拼 LIMIT。
     *
     * <p>守护 {@code listByKb} 的不限量分支。
     */
    @Test
    void listByKbShouldSkipLimitWhenNonPositive() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertEquals(List.of(), impl.listByKb(3L, 0));

        verify(mapper).selectList(argThat(wrapper -> {
            AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
            return !w.getSqlSegment().contains("LIMIT");
        }));
    }

    /**
     * 批量取按传入 ID 顺序回填，查不到的 ID 直接丢弃。
     *
     * <p>守护 {@code listByIdsOrdered} 的顺序与缺失过滤：直接用库返回顺序会让结果错位。
     */
    @Test
    void listByIdsOrderedShouldKeepInputOrderAndDropMissing() {
        WbRetrievalRun first = new WbRetrievalRun();
        first.setId(1L);
        WbRetrievalRun third = new WbRetrievalRun();
        third.setId(3L);
        when(mapper.selectBatchIds(any())).thenReturn(List.of(third, first));

        List<WbRetrievalRun> rows = impl.listByIdsOrdered(List.of(1L, 2L, 3L));

        assertEquals(List.of(1L, 3L), rows.stream().map(WbRetrievalRun::getId).toList());
    }

    /**
     * 空入参不落 SQL。
     *
     * <p>守护 {@code listByIdsOrdered} 的空集合兜底：空 IN () 是语法错误。
     */
    @Test
    void listByIdsOrderedShouldShortCircuitOnEmpty() {
        assertEquals(List.of(), impl.listByIdsOrdered(null));
        assertEquals(List.of(), impl.listByIdsOrdered(List.of()));
        verify(mapper, org.mockito.Mockito.never()).selectBatchIds(any());
    }
}
