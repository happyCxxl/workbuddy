package com.workbuddy.biz.service.catalog;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.biz.mapper.WbModelMapper;
import com.workbuddy.common.domain.entity.WbModel;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型目录库表实现单测（Mapper 打桩 + SQL 断言）：
 * 候选筛选的 SQL 条件与能力过滤、精确取一条的键条件、按模型名取唯一启用模型的多命中拒绝。
 *
 * @author cxxl
 */
class DbModelCatalogTest {

    private WbModelMapper mapper;

    private DbModelCatalog catalog;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WbModel.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(WbModelMapper.class);
        catalog = new DbModelCatalog();
        ReflectionTestUtils.setField(catalog, "baseMapper", mapper);
    }

    @Test
    void listByCapabilityShouldFilterStatusAndEffectiveWindowInSql() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L, "dashscope", "text-embedding-v4", "EMBEDDING")));

        List<ModelDescriptor> candidates = catalog.listByCapability(ModelCapability.EMBEDDING);

        assertEquals(1, candidates.size());
        String sql = capturedListSql();
        assertTrue(sql.contains("status"), sql);
        assertTrue(sql.contains("effective_from"), sql);
        assertTrue(sql.contains("effective_to"), sql);
        assertTrue(sql.contains("ORDER BY"), sql);
        assertTrue(sql.contains("model_key"), sql);
    }

    @Test
    void listByCapabilityShouldReturnNothingForNullCapability() {
        List<ModelDescriptor> candidates = catalog.listByCapability(null);

        assertTrue(candidates.isEmpty());
        verify(mapper, never()).selectList(any());
    }

    @Test
    void multiCapabilityModelShouldAppearInEachCapabilityCandidates() {
        WbModel multimodal = row(1L, "dashscope", "qwen-vl-max", "OCR,LAYOUT,TABLE_STRUCTURE");
        when(mapper.selectList(any())).thenReturn(List.of(multimodal));

        assertEquals(1, catalog.listByCapability(ModelCapability.OCR).size());
        assertEquals(1, catalog.listByCapability(ModelCapability.LAYOUT).size());
        assertEquals(1, catalog.listByCapability(ModelCapability.TABLE_STRUCTURE).size());
        assertTrue(catalog.listByCapability(ModelCapability.CHAT).isEmpty());
        assertTrue(catalog.listByCapability(ModelCapability.EMBEDDING).isEmpty());
    }

    @Test
    void descriptorShouldCarryCapabilitiesPricesAndEmbedSpecs() {
        WbModel embed = row(1L, "dashscope", "text-embedding-v4", "EMBEDDING");
        embed.setPricePerThousandCalls(new BigDecimal("0.000700"));
        embed.setCurrency("CNY");
        embed.setRpmLimit(600);
        embed.setEffectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0));
        when(mapper.selectList(any())).thenReturn(List.of(embed));

        ModelDescriptor descriptor = catalog.listByCapability(ModelCapability.EMBEDDING).getFirst();

        assertEquals("dashscope", descriptor.getVendorCode());
        assertEquals("text-embedding-v4", descriptor.getModelKey());
        assertEquals("dashscope-text-embedding-v4", descriptor.getDisplayName());
        assertEquals(new BigDecimal("0.000700"), descriptor.getPricePerThousandCalls());
        assertEquals(600, descriptor.getRpmLimit());
        assertTrue(descriptor.enabled());
        assertTrue(descriptor.supports(ModelCapability.EMBEDDING));
        assertNotNull(descriptor.getEmbedSpecs());
        assertEquals(1024, descriptor.getEmbedSpecs().getDimension());
        assertEquals(8192, descriptor.getEmbedSpecs().getContextWindowTokens());
        assertEquals(64, descriptor.getEmbedSpecs().getBatchLimit());
    }

    @Test
    void disabledOrOutOfWindowRowsShouldNotBeCandidates() {
        // 启用状态与生效区间由 SQL 筛出，本层只按能力过滤：不入候选的行不会出现在查询结果里
        when(mapper.selectList(any())).thenReturn(List.of());

        assertTrue(catalog.listByCapability(ModelCapability.EMBEDDING).isEmpty());
        verify(mapper).selectList(any());
    }

    @Test
    void embeddingWithoutEnumEntryShouldCarryNoEmbedSpecs() {
        when(mapper.selectList(any())).thenReturn(List.of(row(9L, "custom", "custom-embed", "EMBEDDING")));

        ModelDescriptor descriptor = catalog.listByCapability(ModelCapability.EMBEDDING).getFirst();

        assertEquals("custom-embed", descriptor.getModelKey());
        assertNull(descriptor.getEmbedSpecs());
    }

    @Test
    void findByVendorAndModelKeyShouldFilterBothKeys() {
        when(mapper.selectOne(any())).thenReturn(row(1L, "dashscope", "text-embedding-v4", "EMBEDDING"));

        ModelDescriptor descriptor = catalog.findByVendorAndModelKey("dashscope", "text-embedding-v4");

        assertNotNull(descriptor);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<WbModel>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectOne(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("vendor_code"), sql);
        assertTrue(sql.contains("model_key"), sql);
    }

    @Test
    void findByVendorAndModelKeyShouldSkipQueryOnBlankKeys() {
        assertNull(catalog.findByVendorAndModelKey(null, "text-embedding-v4"));
        assertNull(catalog.findByVendorAndModelKey("dashscope", null));

        verify(mapper, never()).selectOne(any());
    }

    @Test
    void findByModelKeyShouldReturnNullWhenAbsent() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertNull(catalog.findByModelKey("no-such-model"));
        assertNull(catalog.findByModelKey("  "));
    }

    @Test
    void findByModelKeyShouldBeStableAcrossVendors() {
        when(mapper.selectList(any())).thenReturn(List.of(
                row(2L, "zeta", "shared-model", "CHAT"),
                row(1L, "alpha", "shared-model", "CHAT")));

        ModelDescriptor descriptor = catalog.findByModelKey("shared-model");

        assertEquals("alpha", descriptor.getVendorCode());
    }

    @Test
    void findUniqueEnabledByModelKeyShouldReturnTheOnlyMatch() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L, "dashscope", "text-embedding-v4", "EMBEDDING")));

        ModelDescriptor descriptor = catalog.findUniqueEnabledByModelKey("text-embedding-v4");

        assertEquals("dashscope", descriptor.getVendorCode());
        assertEquals("text-embedding-v4", descriptor.getModelKey());
    }

    @Test
    void findUniqueEnabledByModelKeyShouldRejectMultipleMatches() {
        when(mapper.selectList(any())).thenReturn(List.of(
                row(1L, "alpha", "shared-model", "EMBEDDING"),
                row(2L, "beta", "shared-model", "EMBEDDING")));

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> catalog.findUniqueEnabledByModelKey("shared-model"));

        assertEquals(ErrorCode.MODEL_NOT_FOUND, error.getErrorCode());
        assertTrue(error.getMessage().contains("alpha"), error.getMessage());
        assertTrue(error.getMessage().contains("beta"), error.getMessage());
    }

    @Test
    void findUniqueEnabledByModelKeyShouldReturnNullWhenAbsent() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertNull(catalog.findUniqueEnabledByModelKey("no-such-model"));
    }

    /** 取最近一次查询用的条件 */
    private String capturedListSql() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<WbModel>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        Wrapper<WbModel> wrapper = captor.getValue();
        assertNotNull(wrapper);
        return wrapper.getSqlSegment();
    }

    /** 目录行：状态启用、生效区间开放 */
    private WbModel row(Long id, String vendorCode, String modelKey, String capabilities) {
        WbModel row = new WbModel();
        row.setId(id);
        row.setVendorCode(vendorCode);
        row.setModelKey(modelKey);
        row.setDisplayName(vendorCode + "-" + modelKey);
        row.setCapabilities(capabilities);
        row.setStatus("ENABLED");
        row.setCurrency("CNY");
        row.setDelFlag("0");
        return row;
    }
}
