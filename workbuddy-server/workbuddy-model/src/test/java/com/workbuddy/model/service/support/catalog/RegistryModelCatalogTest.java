package com.workbuddy.model.service.support.catalog;

import com.workbuddy.common.convert.model.ModelConverterImpl;
import com.workbuddy.common.domain.model.RegisteredModel;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.enums.embed.EmbedMetric;
import com.workbuddy.common.enums.model.ModelCapability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目录端口实现单测：注册表行映射（含向量模型规格叠加）与按厂商+模型名定位。
 */
class RegistryModelCatalogTest {

    private final ModelRegistry registry = new ModelRegistry();

    private RegistryModelCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new RegistryModelCatalog(registry, new ModelConverterImpl());
    }

    private RegisteredModel row(String vendorCode, String modelKey, ModelCapability capability) {
        RegisteredModel model = new RegisteredModel();
        model.setVendorCode(vendorCode);
        model.setModelKey(modelKey);
        model.setCapabilities(Set.of(capability));
        model.setContextWindow(32768);
        model.setInputLimit(30720);
        model.setOutputLimit(8192);
        model.setPriceInPerM(new BigDecimal("20"));
        model.setRpmLimit(60);
        model.setTpmLimit(100000);
        model.setCurrency("CNY");
        model.setStatus("ENABLED");
        return model;
    }

    /** 按能力取候选并映射长度/价格/限额（{@code shouldMapDirectoryFields}）。 */
    @Test
    void shouldMapDirectoryFields() {
        registry.reload(List.of(row("dashscope", "qwen-max", ModelCapability.CHAT)));

        List<ModelDescriptor> candidates = catalog.listByCapability(ModelCapability.CHAT);

        assertEquals(1, candidates.size());
        ModelDescriptor descriptor = candidates.getFirst();
        assertEquals("dashscope", descriptor.getVendorCode());
        assertEquals("qwen-max", descriptor.getModelKey());
        assertEquals(32768, descriptor.getContextWindow());
        assertEquals(30720, descriptor.getInputLimit());
        assertEquals(8192, descriptor.getOutputLimit());
        assertEquals(new BigDecimal("20"), descriptor.getPriceInPerM());
        assertEquals(60, descriptor.getRpmLimit());
        assertEquals(100000, descriptor.getTpmLimit());
        assertEquals("CNY", descriptor.getCurrency());
        assertEquals("ENABLED", descriptor.getStatus());
    }

    /** 向量模型叠加静态规格：维度/度量/归一化/窗口/批上限（{@code shouldAttachEmbedSpecs}）。 */
    @Test
    void shouldAttachEmbedSpecs() {
        registry.reload(List.of(row("dashscope", "text-embedding-v4", ModelCapability.EMBEDDING)));

        ModelDescriptor descriptor = catalog.listByCapability(ModelCapability.EMBEDDING).getFirst();

        assertEquals(1024, descriptor.getEmbedSpecs().getDimension());
        assertEquals(EmbedMetric.COSINE, descriptor.getEmbedSpecs().getMetric());
        assertTrue(descriptor.getEmbedSpecs().getNormalized());
        assertEquals(8192, descriptor.getEmbedSpecs().getContextWindowTokens());
        assertEquals(64, descriptor.getEmbedSpecs().getBatchLimit());
    }

    /** 按厂商码 + 模型名定位（{@code shouldFindByVendorAndModelKey}）。 */
    @Test
    void shouldFindByVendorAndModelKey() {
        registry.reload(List.of(row("dashscope", "qwen-max", ModelCapability.CHAT)));

        assertEquals("qwen-max", catalog.find("dashscope", "qwen-max").orElseThrow().getModelKey());
        assertTrue(catalog.find("deepseek", "qwen-max").isEmpty());
    }
}
