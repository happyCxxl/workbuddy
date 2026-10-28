package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.biz.mapper.WbIndexSetMapper;
import com.workbuddy.common.domain.entity.WbIndexSet;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 索引集合数据访问服务单测：按库查询 / 幂等创建（uk 冲突兜底）/ 二级指针更新。
 */
class WbIndexSetDbServiceImplTest {

    private WbIndexSetMapper mapper;

    private WbIndexSetDbServiceImpl impl;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                WbIndexSet.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(WbIndexSetMapper.class);
        impl = new WbIndexSetDbServiceImpl();
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);
    }

    /**
     * 校验点：{@code getByKbShouldQueryByKnowledgeBaseId}。
     */
    @Test
    void getByKbShouldQueryByKnowledgeBaseId() {
        WbIndexSet row = new WbIndexSet();
        row.setId(10L);
        row.setKnowledgeBaseId(2L);
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(row);

        WbIndexSet result = impl.getByKb(2L);

        assertNotNull(result);
        assertEquals(10L, result.getId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<WbIndexSet>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectOne(captor.capture(), org.mockito.ArgumentMatchers.eq(false));
        assertTrue(captor.getValue().getSqlSegment().contains("knowledge_base_id"), captor.getValue().getSqlSegment());
    }

    /**
     * 校验点：{@code getOrCreateShouldReturnExisting}。
     */
    @Test
    void getOrCreateShouldReturnExisting() {
        WbIndexSet existing = new WbIndexSet();
        existing.setId(10L);
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(existing);

        WbIndexSet result = impl.getOrCreateByKb(2L);

        assertSame(existing, result);
        verify(mapper, times(0)).insert(any());
    }

    /**
     * 校验点：{@code getOrCreateShouldSaveWhenMissing}。
     */
    @Test
    void getOrCreateShouldSaveWhenMissing() {
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(null);
        when(mapper.insert(any(WbIndexSet.class))).thenReturn(1);

        WbIndexSet result = impl.getOrCreateByKb(2L);

        assertNotNull(result);
        assertEquals(2L, result.getKnowledgeBaseId());
        ArgumentCaptor<WbIndexSet> captor = ArgumentCaptor.forClass(WbIndexSet.class);
        verify(mapper).insert(captor.capture());
        assertEquals(2L, captor.getValue().getKnowledgeBaseId());
    }

    /**
     * 校验点：{@code getOrCreateShouldFallbackOnDuplicateKey}。
     */
    @Test
    void getOrCreateShouldFallbackOnDuplicateKey() {
        WbIndexSet existing = new WbIndexSet();
        existing.setId(11L);
        // 首次查询缺失 → insert 冲突 → 重查返回既有行
        when(mapper.selectOne(any(), anyBoolean())).thenReturn(null, existing);
        when(mapper.insert(any(WbIndexSet.class))).thenThrow(new DuplicateKeyException("uk_kb"));

        WbIndexSet result = impl.getOrCreateByKb(2L);

        assertSame(existing, result);
    }

    /**
     * 校验点：{@code updatePublishedVersionShouldSetPointer}。
     */
    @Test
    void updatePublishedVersionShouldSetPointer() {
        impl.updatePublishedVersion(10L, 99L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<WbIndexSet>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(org.mockito.ArgumentMatchers.isNull(), captor.capture());
        // LambdaUpdateWrapper：SET 段在 getSqlSet，WHERE 段在 getSqlSegment
        LambdaUpdateWrapper<WbIndexSet> wrapper = (LambdaUpdateWrapper<WbIndexSet>) captor.getValue();
        assertTrue(wrapper.getSqlSet().contains("current_published_version_id"), wrapper.getSqlSet());
        assertTrue(wrapper.getSqlSegment().contains("id"), wrapper.getSqlSegment());
    }

    /**
     * 按知识库批量取集合索引：空入参不查库，非空返回按库映射。
     *
     * <p>守护 {@code listByKbIds} 的空入参短路与映射口径。
     */
    @Test
    void listByKbIdsShouldMapRowsAndShortCircuitEmpty() {
        assertTrue(impl.listByKbIds(List.of()).isEmpty());

        WbIndexSet row = new WbIndexSet();
        row.setId(5L);
        row.setKnowledgeBaseId(1L);
        when(mapper.selectList(any())).thenReturn(List.of(row));

        assertEquals(5L, impl.listByKbIds(List.of(1L)).get(1L).getId());
    }
}
