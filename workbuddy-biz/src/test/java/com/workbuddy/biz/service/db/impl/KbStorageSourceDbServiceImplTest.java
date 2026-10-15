package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.biz.mapper.KbStorageSourceMapper;
import com.workbuddy.biz.service.db.KbStorageSourceDbService;
import com.workbuddy.common.domain.entity.KbStorageSource;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 存储数据源数据访问服务单测：清单排序 / 当前启用标记的清与置 / 探测结论回写。
 *
 * @author cxxl
 */
class KbStorageSourceDbServiceImplTest {

    private KbStorageSourceMapper mapper;

    private KbStorageSourceDbServiceImpl impl;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                KbStorageSource.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(KbStorageSourceMapper.class);
        impl = new KbStorageSourceDbServiceImpl();
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);
    }

    private KbStorageSource row(Long id, String name, int isCurrent) {
        KbStorageSource row = new KbStorageSource();
        row.setId(id);
        row.setName(name);
        row.setStorageType("local");
        row.setIsCurrent(isCurrent);
        return row;
    }

    /** 取最近一次查询用的条件 */
    private Wrapper<KbStorageSource> capturedQuery() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<KbStorageSource>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        return captor.getValue();
    }

    @Test
    void listAllShouldOrderById() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L, "本地磁盘", 1)));

        List<KbStorageSource> rows = impl.listAll();

        assertEquals(1, rows.size());
        String sql = capturedQuery().getSqlSegment();
        assertTrue(sql.contains("ORDER BY"), sql);
        assertTrue(sql.contains("id"), sql);
    }

    @Test
    void markCurrentShouldSetCurrentFlagOnTheRow() {
        when(mapper.updateById(any(KbStorageSource.class))).thenReturn(1);

        impl.markCurrent(9L);

        ArgumentCaptor<KbStorageSource> captor = ArgumentCaptor.forClass(KbStorageSource.class);
        verify(mapper).updateById(captor.capture());
        assertEquals(9L, captor.getValue().getId());
        assertEquals(KbStorageSourceDbService.CURRENT_YES, captor.getValue().getIsCurrent());
    }

    @Test
    void clearCurrentShouldResetEveryCurrentRow() {
        when(mapper.selectList(any())).thenReturn(List.of(row(9L, "本地磁盘", 1)));
        when(mapper.updateById(any(KbStorageSource.class))).thenReturn(1);

        impl.clearCurrent();

        ArgumentCaptor<KbStorageSource> captor = ArgumentCaptor.forClass(KbStorageSource.class);
        verify(mapper).updateById(captor.capture());
        assertEquals(KbStorageSourceDbService.CURRENT_NO, captor.getValue().getIsCurrent());
    }

    @Test
    void clearCurrentShouldNotWriteWithoutCurrentRow() {
        when(mapper.selectList(any())).thenReturn(List.of());

        impl.clearCurrent();

        verify(mapper, never()).updateById(any(KbStorageSource.class));
    }

    @Test
    void recordProbeShouldWriteProbeColumnsOnly() {
        LocalDateTime probeAt = LocalDateTime.of(2026, 10, 9, 10, 30, 15);
        when(mapper.update(isNull(), any())).thenReturn(1);

        impl.recordProbe(9L, false, probeAt);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<KbStorageSource>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        // LambdaUpdateWrapper：SET 段在 getSqlSet，WHERE 段在 getSqlSegment
        LambdaUpdateWrapper<KbStorageSource> wrapper = (LambdaUpdateWrapper<KbStorageSource>) captor.getValue();
        assertTrue(wrapper.getSqlSet().contains("last_probe_ok"), wrapper.getSqlSet());
        assertTrue(wrapper.getSqlSet().contains("last_probe_at"), wrapper.getSqlSet());
        assertTrue(wrapper.getSqlSegment().contains("id"), wrapper.getSqlSegment());
    }
}
