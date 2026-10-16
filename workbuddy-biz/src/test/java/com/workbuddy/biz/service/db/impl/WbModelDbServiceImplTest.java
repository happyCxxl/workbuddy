package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.mapper.WbModelMapper;
import com.workbuddy.common.domain.entity.WbModel;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
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
class WbModelDbServiceImplTest {

    private WbModelMapper mapper;

    private WbModelDbServiceImpl dbService;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WbModel.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(WbModelMapper.class);
        dbService = new WbModelDbServiceImpl();
        ReflectionTestUtils.setField(dbService, "baseMapper", mapper);
    }

    @Test
    void listByConditionShouldFilterStatusInSql() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L)));

        List<WbModel> rows = dbService.listByCondition("ENABLED");

        assertEquals(1, rows.size());
        String sql = capturedListSql();
        assertTrue(sql.contains("status"), sql);
        assertTrue(sql.contains("ORDER BY"), sql);
    }

    @Test
    void listByConditionShouldSkipBlankFilters() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertTrue(dbService.listByCondition(null).isEmpty());

        // 条件不下发：SQL 里没有 WHERE，只有排序（排序键里本就含 model_key，故不能整串判）
        String sql = capturedListSql();
        assertFalse(sql.contains("WHERE"), sql);
        assertTrue(sql.contains("ORDER BY"), sql);
    }

    @Test
    void pageByConditionShouldFilterStatusKeywordAndCapabilityInSql() {
        Page<WbModel> page = new Page<>(1, 10);
        page.setRecords(List.of(row(1L)));
        when(mapper.selectPage(any(), any())).thenReturn(page);

        IPage<WbModel> result = dbService.pageByCondition(1, 10, "ENABLED", "qwen", "OCR");

        assertEquals(1, result.getRecords().size());
        String sql = capturedPageSql();
        assertTrue(sql.contains("status"), sql);
        assertTrue(sql.contains("model_key"), sql);
        assertTrue(sql.contains("FIND_IN_SET"), sql);
        assertTrue(sql.contains("capabilities"), sql);
        assertTrue(sql.contains("ORDER BY"), sql);
    }

    /** 能力留空时不拼 FIND_IN_SET，状态与关键字留空时不拼 WHERE */
    @Test
    void pageByConditionShouldSkipAbsentFilters() {
        Page<WbModel> page = new Page<>(1, 10);
        when(mapper.selectPage(any(), any())).thenReturn(page);

        dbService.pageByCondition(1, 10, null, null, null);

        String sql = capturedPageSql();
        assertFalse(sql.contains("FIND_IN_SET"), sql);
        assertFalse(sql.contains("WHERE"), sql);
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

        LambdaUpdateWrapper<WbModel> wrapper = capturedUpdateWrapper();
        assertTrue(wrapper.getSqlSet().contains("status"), wrapper.getSqlSet());
        assertTrue(wrapper.getSqlSegment().contains("id"), wrapper.getSqlSegment());
    }

    @Test
    void updateAllShouldCarryValuesOnEntityAndClearNullColumns() {
        WbModel row = row(6L);
        row.setPriceInPerM(null);
        row.setRpmLimit(null);
        row.setInputLimit(null);

        dbService.updateAll(row);

        ArgumentCaptor<WbModel> entityCaptor = ArgumentCaptor.forClass(WbModel.class);
        ArgumentCaptor<Wrapper<WbModel>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(entityCaptor.capture(), wrapperCaptor.capture());
        WbModel entity = entityCaptor.getValue();
        assertEquals("qwen-max", entity.getModelKey());
        assertEquals(new BigDecimal("60"), entity.getPriceOutPerM());
        assertNull(entity.getPriceInPerM());

        String sqlSet = ((LambdaUpdateWrapper<WbModel>) wrapperCaptor.getValue()).getSqlSet();
        assertTrue(sqlSet.contains("price_in_per_m"), sqlSet);
        assertTrue(sqlSet.contains("rpm_limit"), sqlSet);
        assertTrue(sqlSet.contains("input_limit"), sqlSet);
        assertFalse(sqlSet.contains("price_out_per_m"), sqlSet);
        assertFalse(sqlSet.contains("model_key"), sqlSet);
    }

    /** 取最近一次查询用的条件 */
    private String capturedListSql() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<WbModel>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        return captor.getValue().getSqlSegment();
    }

    /** 取最近一次分页查询用的条件 */
    private String capturedPageSql() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<WbModel>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(any(), captor.capture());
        return captor.getValue().getSqlSegment();
    }

    /** 取最近一次定点回写用的条件与赋值 */
    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<WbModel> capturedUpdateWrapper() {
        ArgumentCaptor<Wrapper<WbModel>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(any(), captor.capture());
        return (LambdaUpdateWrapper<WbModel>) captor.getValue();
    }

    /** 目录行：非空列齐全，可空列按需覆盖 */
    private WbModel row(Long id) {
        WbModel row = new WbModel();
        row.setId(id);
        row.setVendorCode("dashscope");
        row.setModelKey("qwen-max");
        row.setCapabilities("CHAT");
        row.setPriceOutPerM(new BigDecimal("60"));
        row.setCurrency("CNY");
        row.setStatus("ENABLED");
        row.setInputLimit(1000000);
        return row;
    }
}
