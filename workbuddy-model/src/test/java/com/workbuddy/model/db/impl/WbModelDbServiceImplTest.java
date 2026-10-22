package com.workbuddy.model.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.common.domain.entity.WbModel;
import com.workbuddy.model.mapper.WbModelMapper;
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

    /**
     * 清单：状态条件与排序进 SQL。
     *
     * <p>守护 {@code listByCondition}：条件或排序丢失即失败。
     */
    @Test
    void listByConditionShouldFilterStatusInSql() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L)));

        List<WbModel> rows = dbService.listByCondition("ENABLED");

        assertEquals(1, rows.size());
        String sql = capturedListSql();
        assertTrue(sql.contains("status"), sql);
        assertTrue(sql.contains("ORDER BY"), sql);
    }

    /**
     * 清单：空过滤不下发 WHERE。
     *
     * <p>守护 {@code listByCondition}：空条件拼 WHERE 即失败。
     */
    @Test
    void listByConditionShouldSkipBlankFilters() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertTrue(dbService.listByCondition(null).isEmpty());

        // 条件不下发：SQL 里没有 WHERE，只有排序（排序键里本就含 model_key，故不能整串判）
        String sql = capturedListSql();
        assertFalse(sql.contains("WHERE"), sql);
        assertTrue(sql.contains("ORDER BY"), sql);
    }

    /**
     * 分页：状态/关键字/能力三条件进 SQL。
     *
     * <p>守护 {@code pageByCondition}：能力匹配语法写错即失败。
     */
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
        // 参数取的是入参能力项本身：取成空串会让 FIND_IN_SET 永远不命中
        org.mockito.ArgumentCaptor<
                com.baomidou.mybatisplus.core.conditions.Wrapper<com.workbuddy.common.domain.entity.WbModel>>
                captor = org.mockito.ArgumentCaptor.forClass(
                        com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(mapper).selectPage(any(), captor.capture());
        com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> wrapper =
                (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) captor.getValue();
        wrapper.getSqlSegment();
        assertTrue(wrapper.getParamNameValuePairs().containsValue("OCR"));
        assertTrue(sql.contains("capabilities"), sql);
        assertTrue(sql.contains("ORDER BY"), sql);
    }

    /** 能力留空时不拼 FIND_IN_SET，状态与关键字留空时不拼 WHERE */
    /**
     * 分页：条件缺失时不拼 WHERE 与 FIND_IN_SET。
     *
     * <p>守护 {@code pageByCondition}：缺条件仍拼 SQL 即失败。
     */
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

    /**
     * 按 ID 取行：空 ID 不查库。
     *
     * <p>守护 {@code getExistingById}：空 ID 查库即失败。
     */
    @Test
    void getExistingByIdShouldSkipQueryOnNullId() {
        assertNull(dbService.getExistingById(null));

        verify(mapper, never()).selectById(any());
    }

    /**
     * 按 ID 取行：命中返回该行。
     *
     * <p>守护 {@code getExistingById}：返回错行即失败。
     */
    @Test
    void getExistingByIdShouldReturnRow() {
        when(mapper.selectById(1L)).thenReturn(row(1L));

        assertEquals(1L, dbService.getExistingById(1L).getId());
    }

    /**
     * 唯一键判定：厂商码与模型键双条件。
     *
     * <p>守护 {@code existsVendorAndModelKey}：少一条件即失败。
     */
    @Test
    void existsVendorAndModelKeyShouldFilterBothKeys() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L)));

        assertTrue(dbService.existsVendorAndModelKey("dashscope", "qwen-max", null));

        String sql = capturedListSql();
        assertTrue(sql.contains("vendor_code"), sql);
        assertTrue(sql.contains("model_key"), sql);
        assertFalse(sql.contains("id"), sql);
    }

    /**
     * 唯一键判定：编辑时排除自身。
     *
     * <p>守护 {@code existsVendorAndModelKey}：不排除自身即误判。
     */
    @Test
    void existsVendorAndModelKeyShouldExcludeSelfWhenEditing() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertFalse(dbService.existsVendorAndModelKey("dashscope", "qwen-max", 5L));

        String sql = capturedListSql();
        assertTrue(sql.contains("vendor_code"), sql);
        assertTrue(sql.contains("id"), sql);
    }

    /**
     * 启停：定点只更新状态列。
     *
     * <p>守护 {@code updateStatus}：状态列或主键条件错即失败。
     */
    @Test
    void updateStatusShouldWriteStatusColumn() {
        dbService.updateStatus(4L, "DISABLED");

        LambdaUpdateWrapper<WbModel> wrapper = capturedUpdateWrapper();
        assertTrue(wrapper.getSqlSet().contains("status"), wrapper.getSqlSet());
        assertTrue(wrapper.getSqlSegment().contains("id"), wrapper.getSqlSegment());
    }

    /**
     * 覆盖更新：空值列显式写 NULL，有值列走实体更新。
     *
     * <p>守护 {@code updateModel}：清空列漏写即旧值残留。
     */
    @Test
    void updateAllShouldCarryValuesOnEntityAndClearNullColumns() {
        WbModel row = row(6L);
        // 八个可空数值列全部清空：实体更新策略跳过 null，必须逐列显式写 NULL
        row.setInputLimit(null);
        row.setOutputLimit(null);
        row.setThinkingInputLimit(null);
        row.setThinkingOutputLimit(null);
        row.setContextWindow(null);
        row.setPriceInPerM(null);
        row.setPriceInPerMCacheHit(null);

        dbService.updateModel(row);

        ArgumentCaptor<WbModel> entityCaptor = ArgumentCaptor.forClass(WbModel.class);
        ArgumentCaptor<Wrapper<WbModel>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(entityCaptor.capture(), wrapperCaptor.capture());
        WbModel entity = entityCaptor.getValue();
        assertEquals("qwen-max", entity.getModelKey());
        assertEquals(new BigDecimal("60"), entity.getPriceOutPerM());
        assertNull(entity.getPriceInPerM());

        String sqlSet = ((LambdaUpdateWrapper<WbModel>) wrapperCaptor.getValue()).getSqlSet();
        for (String column : List.of("input_limit", "output_limit", "thinking_input_limit",
                "thinking_output_limit", "context_window", "price_in_per_m", "price_in_per_m_cache_hit")) {
            // 按 SET 片段精确匹配：thinking_input_limit 等列名互为前缀，纯 contains 会放过漏清列
            assertTrue(java.util.Arrays.stream(sqlSet.split(","))
                    .anyMatch(part -> part.startsWith(column + "=")), column + " 未清空: " + sqlSet);
        }
        // 有值的列走实体更新，不重复写 SET；主键/业务列不进 SET
        // 有值列不进 SET；清空后必须显式写 NULL
        assertFalse(java.util.Arrays.stream(sqlSet.split(","))
                .anyMatch(part -> part.startsWith("price_out_per_m=")), sqlSet);

        // 价格清空：这一列有值时无操作，清空时必须显式写 NULL 才能清掉旧值
        row.setPriceOutPerM(null);
        dbService.updateModel(row);
        ArgumentCaptor<Wrapper<WbModel>> clearedWrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, org.mockito.Mockito.times(2)).update(any(), clearedWrapper.capture());
        String clearedSet = ((LambdaUpdateWrapper<WbModel>) clearedWrapper.getValue()).getSqlSet();
        assertTrue(java.util.Arrays.stream(clearedSet.split(","))
                .anyMatch(part -> part.startsWith("price_out_per_m=")), clearedSet);
        assertFalse(sqlSet.contains("model_key="), sqlSet);
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
