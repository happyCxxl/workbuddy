package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.biz.mapper.WbKnowledgeBaseMapper;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DbService 单测：getActiveById 的对象级获取语义 + 归属集合查询的过滤条件。
 */
class WbKnowledgeBaseDbServiceImplTest {

    private WbKnowledgeBaseMapper mapper;

    private WbKnowledgeBaseDbServiceImpl impl;

    @BeforeEach
    void setUp() {
        mapper = mock(WbKnowledgeBaseMapper.class);
        impl = new WbKnowledgeBaseDbServiceImpl();
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);
        // 脱离 Spring 上下文时 MP 没有 TableInfo 缓存，LambdaQueryWrapper 解析列名会抛
        // "can not find lambda cache for this entity" —— 凡是用 Lambda 的用例都要先初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                WbKnowledgeBase.class);
    }

    /**
     * 校验点：{@code getActiveByIdWhenExistsShouldReturn}。
     */
    @Test
    void getActiveByIdWhenExistsShouldReturn() {
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(1L);
        kb.setName("库1");
        when(mapper.selectById(1L)).thenReturn(kb);

        assertSame(kb, impl.getActiveById(1L));
    }

    /**
     * 校验点：{@code getActiveByIdWhenAbsentShouldThrowNotFound}。
     */
    @Test
    void getActiveByIdWhenAbsentShouldThrowNotFound() {
        when(mapper.selectById(2L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> impl.getActiveById(2L));
        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 校验点：{@code listIdsByOwnerShouldFilterByUserId}。
     */
    @Test
    void listIdsByOwnerShouldFilterByUserId() {
        when(mapper.selectObjs(any())).thenReturn(List.of(1L, 2L));

        List<Long> ids = impl.listIdsByOwner(7L);

        assertEquals(List.of(1L, 2L), ids);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<WbKnowledgeBase>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectObjs(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("user_id ="),
                captor.getValue().getSqlSegment());
    }

    /**
     * 校验点：{@code listIdsByOwnerWithNullOwnerShouldNotFilter}。
     */
    @Test
    void listIdsByOwnerWithNullOwnerShouldNotFilter() {
        // null = 管理员视角：不加归属条件（与"看不到自己的库"是两回事）
        when(mapper.selectObjs(any())).thenReturn(List.of(1L));

        impl.listIdsByOwner(null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<WbKnowledgeBase>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectObjs(captor.capture());
        assertFalse(captor.getValue().getSqlSegment().contains("user_id"),
                captor.getValue().getSqlSegment());
    }

    /**
     * 分页与计数返回真实口径。
     *
     * <p>守护 {@code pageByCondition}/{@code countByStatus} 的返回：恒空/恒 0 会让首页统计全为 0。
     */
    @Test
    void pageAndCountShouldReturnRealValues() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WbKnowledgeBase> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        page.setRecords(List.of(new WbKnowledgeBase()));
        when(mapper.selectPage(any(), any())).thenReturn(page);
        when(mapper.selectCount(any())).thenReturn(3L);

        assertEquals(1, impl.pageByCondition(1, 10, null, null, null, 1L).getRecords().size());
        assertEquals(3L, impl.countByStatus(null, 1L));
    }
}
