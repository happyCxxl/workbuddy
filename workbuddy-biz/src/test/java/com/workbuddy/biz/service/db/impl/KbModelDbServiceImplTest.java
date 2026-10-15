package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.biz.mapper.KbModelMapper;
import com.workbuddy.common.domain.entity.KbModel;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型目录数据访问单测（Mapper 打桩 + SQL 片段断言）：清单的过滤条件与排序、
 * 唯一键判定（含编辑时排除自身）、启停的定点回写、覆盖式更新的清空列。
 *
 * @author cxxl
 */
class KbModelDbServiceImplTest {

    private KbModelMapper mapper;

    private KbModelDbServiceImpl dbService;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), KbModel.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(KbModelMapper.class);
        dbService = new KbModelDbServiceImpl();
        ReflectionTestUtils.setField(dbService, "baseMapper", mapper);
    }

    @Test
    void listByConditionShouldFilterStatusAndKeywordInSql() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L)));

        List<KbModel> rows = dbService.listByCondition("ENABLED", "qwen");

        assertEquals(1, rows.size());
        String sql = capturedListSql();
        assertTrue(sql.contains("status"), sql);
        assertTrue(sql.contains("model_key"), sql);
        assertTrue(sql.contains("display_name"), sql);
        assertTrue(sql.contains("ORDER BY"), sql);
    }

    @Test
    void listByConditionShouldSkipBlankFilters() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertTrue(dbService.listByCondition(null, null).isEmpty());

        String sql = capturedListSql();
        assertFalse(sql.contains("status"), sql);
        assertFalse(sql.contains("display_name"), sql);
        assertTrue(sql.contains("ORDER BY"), sql);
    }

    @Test
    void getExistingByIdShouldSkipQueryOnNullId() {
        assertNull(dbService.getExistingById(null));

        verify(mapper, never()).selectById(any());
    }

    @Test
    void getExistingByIdShouldReturnRow() {
        when(mapper.selectById(1L)).thenReturn(row(1L));

        assertEquals(1L, dbService.getExistingById(1L).getId());
    }

    @Test
    void existsVendorAndModelKeyShouldFilterBothKeys() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L)));

        assertTrue(dbService.existsVendorAndModelKey("dashscope", "qwen-max", null));

        String sql = capturedListSql();
        assertTrue(sql.contains("vendor_code"), sql);
        assertTrue(sql.contains("model_key"), sql);
        assertFalse(sql.contains("id"), sql);
    }

    @Test
    void existsVendorAndModelKeyShouldExcludeSelfWhenEditing() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertFalse(dbService.existsVendorAndModelKey("dashscope", "qwen-max", 5L));

        String sql = capturedListSql();
        assertTrue(sql.contains("vendor_code"), sql);
        assertTrue(sql.contains("id"), sql);
    }

    @Test
    void updateStatusShouldWriteStatusColumn() {
        dbService.updateStatus(4L, "DISABLED");

        LambdaUpdateWrapper<KbModel> wrapper = capturedUpdateWrapper();
        assertTrue(wrapper.getSqlSet().contains("status"), wrapper.getSqlSet());
        assertTrue(wrapper.getSqlSegment().contains("id"), wrapper.getSqlSegment());
    }

    @Test
    void updateAllShouldCarryValuesOnEntityAndClearNullColumns() {
        KbModel row = row(6L);
        row.setPriceInPerM(null);
        row.setRpmLimit(null);
        row.setEffectiveTo(null);
        row.setRemark(null);

        dbService.updateAll(row);

        ArgumentCaptor<KbModel> entityCaptor = ArgumentCaptor.forClass(KbModel.class);
        ArgumentCaptor<Wrapper<KbModel>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(entityCaptor.capture(), wrapperCaptor.capture());
        KbModel entity = entityCaptor.getValue();
        assertEquals("qwen-max", entity.getModelKey());
        assertEquals(new BigDecimal("60"), entity.getPriceOutPerM());
        assertNull(entity.getPriceInPerM());

        String sqlSet = ((LambdaUpdateWrapper<KbModel>) wrapperCaptor.getValue()).getSqlSet();
        assertTrue(sqlSet.contains("price_in_per_m"), sqlSet);
        assertTrue(sqlSet.contains("rpm_limit"), sqlSet);
        assertTrue(sqlSet.contains("effective_to"), sqlSet);
        assertTrue(sqlSet.contains("remark"), sqlSet);
        assertFalse(sqlSet.contains("price_out_per_m"), sqlSet);
        assertFalse(sqlSet.contains("model_key"), sqlSet);
    }

    /** 取最近一次查询用的条件 */
    private String capturedListSql() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<KbModel>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        return captor.getValue().getSqlSegment();
    }

    /** 取最近一次定点回写用的条件与赋值 */
    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<KbModel> capturedUpdateWrapper() {
        ArgumentCaptor<Wrapper<KbModel>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(any(), captor.capture());
        return (LambdaUpdateWrapper<KbModel>) captor.getValue();
    }

    /** 目录行：非空列齐全，可空列按需覆盖 */
    private KbModel row(Long id) {
        KbModel row = new KbModel();
        row.setId(id);
        row.setVendorCode("dashscope");
        row.setModelKey("qwen-max");
        row.setDisplayName("通义千问 Max");
        row.setCapabilities("CHAT");
        row.setPriceOutPerM(new BigDecimal("60"));
        row.setCurrency("CNY");
        row.setStatus("ENABLED");
        row.setEffectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0));
        row.setEffectiveTo(LocalDateTime.of(2026, 12, 31, 0, 0));
        row.setRemark("备注");
        return row;
    }
}
