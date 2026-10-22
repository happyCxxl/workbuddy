package com.workbuddy.common.convert;

import com.workbuddy.common.domain.entity.WbModel;
import com.workbuddy.common.domain.model.RegisteredModel;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.dto.request.model.ModelSaveDTO;
import com.workbuddy.common.enums.model.ModelCapability;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 模型转换单测：库表行 → 注册表行、注册表行 → 目录视图、保存请求 → 实体。
 */
class ModelConverterTest {

    private final ModelConverter converter = new ModelConverterImpl();

    /** 库表行 → 注册表行：能力 CSV 解析成集合（{@code shouldMapRegistered}）。 */
    @Test
    void shouldMapRegistered() {
        WbModel row = new WbModel();
        row.setVendorCode("dashscope");
        row.setModelKey("qwen-max");
        row.setCapabilities("CHAT,EMBEDDING");
        row.setContextWindow(32768);
        row.setInputLimit(30720);
        row.setOutputLimit(8192);
        row.setPriceInPerM(new BigDecimal("20"));
        row.setPriceInPerMCacheHit(new BigDecimal("2"));
        row.setPriceOutPerM(new BigDecimal("60"));
        row.setCurrency("CNY");
        row.setRpmLimit(600);
        row.setTpmLimit(120000);
        row.setStatus("ENABLED");

        RegisteredModel model = converter.toRegistered(row);

        assertEquals("dashscope", model.getVendorCode());
        assertEquals("qwen-max", model.getModelKey());
        assertEquals(Set.of(ModelCapability.CHAT, ModelCapability.EMBEDDING), model.getCapabilities());
        assertEquals(32768, model.getContextWindow());
        assertEquals(new BigDecimal("20"), model.getPriceInPerM());
        assertEquals(600, model.getRpmLimit());
        assertEquals("ENABLED", model.getStatus());
    }

    /** 向量模型映射时叠加静态规格（{@code shouldAttachEmbedSpecs}）。 */
    @Test
    void shouldAttachEmbedSpecs() {
        RegisteredModel model = registered("text-embedding-v4");

        ModelDescriptor descriptor = converter.toDescriptor(model);

        assertEquals("text-embedding-v4", descriptor.getModelKey());
        assertNotNull(descriptor.getEmbedSpecs());
        assertNotNull(descriptor.getEmbedSpecs().getDimension());
    }

    /** 非向量模型不叠向量规格（{@code shouldNotAttachEmbedSpecs}）。 */
    @Test
    void shouldNotAttachEmbedSpecs() {
        ModelDescriptor descriptor = converter.toDescriptor(registered("qwen-max"));

        assertNull(descriptor.getEmbedSpecs());
    }

    /** 保存请求 → 实体：只搬同名字段，业务字段留给调用方（{@code shouldMapSaveDto}）。 */
    @Test
    void shouldMapSaveDto() {
        ModelSaveDTO dto = new ModelSaveDTO();
        dto.setVendorCode("dashscope");
        dto.setModelKey("qwen-max");
        dto.setCapabilities(List.of("CHAT"));
        dto.setInputLimit(30720);
        dto.setOutputLimit(8192);
        dto.setContextWindow(32768);
        dto.setPriceInPerM(new BigDecimal("20"));
        dto.setPriceOutPerM(new BigDecimal("60"));
        dto.setCurrency("CNY");
        dto.setRpmLimit(600);
        dto.setTpmLimit(120000);

        WbModel row = converter.toModel(dto);

        assertEquals(30720, row.getInputLimit());
        assertEquals(8192, row.getOutputLimit());
        assertEquals(32768, row.getContextWindow());
        assertEquals(new BigDecimal("20"), row.getPriceInPerM());
        assertEquals("CNY", row.getCurrency());
        assertEquals(600, row.getRpmLimit());
        assertEquals(120000, row.getTpmLimit());
        assertNull(row.getVendorCode());
        assertNull(row.getModelKey());
        assertNull(row.getCapabilities());
    }

    private RegisteredModel registered(String modelKey) {
        RegisteredModel model = new RegisteredModel();
        model.setVendorCode("dashscope");
        model.setModelKey(modelKey);
        model.setCapabilities(Set.of(ModelCapability.CHAT));
        model.setStatus("ENABLED");
        return model;
    }
}
