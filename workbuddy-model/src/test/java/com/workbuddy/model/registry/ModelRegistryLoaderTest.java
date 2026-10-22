package com.workbuddy.model.registry;

import com.workbuddy.common.domain.entity.WbModel;
import com.workbuddy.common.domain.model.RegisteredModel;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.model.db.WbModelDbService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 模型注册表装载器单测：启用行映射（能力解析）、空表合法、查库失败上抛。
 */
@ExtendWith(MockitoExtension.class)
class ModelRegistryLoaderTest {

    @Mock
    private WbModelDbService wbModelDbService;

    private ModelRegistry registry;

    private ModelRegistryLoader loader;

    @BeforeEach
    void setUp() {
        registry = new ModelRegistry();
        loader = new ModelRegistryLoader(wbModelDbService, registry);
    }

    private WbModel row(String vendorCode, String modelKey, String capabilities) {
        WbModel row = new WbModel();
        row.setVendorCode(vendorCode);
        row.setModelKey(modelKey);
        row.setCapabilities(capabilities);
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
        return row;
    }

    /**
     * 守护 {@code shouldLoadEnabledRowsWithParsedCapabilities}：该场景行为改动即失败。
     */
    @Test
    void shouldLoadEnabledRowsWithParsedCapabilities() {
        when(wbModelDbService.listByCondition("ENABLED")).thenReturn(List.of(
                row("deepseek", "deepseek-chat", "CHAT,EMBEDDING"),
                row("dashscope", "qwen-max", "CHAT")));

        int loaded = loader.reload();

        assertEquals(2, loaded);
        assertEquals(2, registry.all().size());
        RegisteredModel first = registry.all().getFirst();
        assertEquals("deepseek", first.getVendorCode());
        assertEquals("deepseek-chat", first.getModelKey());
        assertEquals(Set.of(ModelCapability.CHAT, ModelCapability.EMBEDDING), first.getCapabilities());
        assertEquals(32768, first.getContextWindow());
        assertEquals(30720, first.getInputLimit());
        assertEquals(8192, first.getOutputLimit());
        assertEquals(new BigDecimal("20"), first.getPriceInPerM());
        assertEquals(new BigDecimal("2"), first.getPriceInPerMCacheHit());
        assertEquals(new BigDecimal("60"), first.getPriceOutPerM());
        assertEquals("CNY", first.getCurrency());
        assertEquals(600, first.getRpmLimit());
        assertEquals(120000, first.getTpmLimit());
        assertEquals("ENABLED", first.getStatus());
    }

    /** 空表合法：装载零行，注册表为空（{@code shouldTolerateEmptyCatalog}）。 */
    @Test
    void shouldTolerateEmptyCatalog() {
        when(wbModelDbService.listByCondition("ENABLED")).thenReturn(List.of());

        assertEquals(0, loader.reload());
        assertTrue(registry.all().isEmpty());
    }

    /** 查库失败异常上抛：启动期终止启动，刷新期报给操作方（{@code shouldPropagateDbFailure}）。 */
    @Test
    void shouldPropagateDbFailure() {
        when(wbModelDbService.listByCondition("ENABLED")).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, loader::reload);
    }
}
