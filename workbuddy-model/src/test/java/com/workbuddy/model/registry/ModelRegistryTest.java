package com.workbuddy.model.registry;

import com.workbuddy.common.domain.model.RegisteredModel;
import com.workbuddy.common.enums.model.ModelCapability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型内存注册表单测：全量替换、空与 null 容忍、缺模型名的行丢弃。
 */
class ModelRegistryTest {

    private final ModelRegistry registry = new ModelRegistry();

    private RegisteredModel model(String vendorCode, String modelKey) {
        RegisteredModel model = new RegisteredModel();
        model.setVendorCode(vendorCode);
        model.setModelKey(modelKey);
        model.setCapabilities(Set.of(ModelCapability.CHAT));
        return model;
    }

    /**
     * 守护 {@code shouldKeepLoadedRows}：该场景行为改动即失败。
     */
    @Test
    void shouldKeepLoadedRows() {
        registry.reload(List.of(
                model("deepseek", "deepseek-chat"),
                model("dashscope", "qwen-max")));

        assertEquals(2, registry.all().size());
    }

    /** 同名跨厂商是合法录入：两行都原样保留，定位口径是 厂商码 + 模型名（{@code shouldKeepDuplicateKeysAcrossVendors}）。 */
    @Test
    void shouldKeepDuplicateKeysAcrossVendors() {
        registry.reload(List.of(
                model("deepseek", "gpt-x"),
                model("dashscope", "gpt-x")));

        assertEquals(2, registry.all().size());
    }

    /** reload 是全量替换：换一份清单后旧条目不再可见（{@code reloadShouldReplaceSnapshotEntirely}）。 */
    @Test
    void reloadShouldReplaceSnapshotEntirely() {
        registry.reload(List.of(model("deepseek", "deepseek-chat")));
        registry.reload(List.of(model("dashscope", "qwen-max")));

        assertEquals(1, registry.all().size());
        assertEquals("dashscope", registry.all().getFirst().getVendorCode());
    }

    /**
     * 守护 {@code shouldTolerateEmptyOrNullReload}：该场景行为改动即失败。
     */
    @Test
    void shouldTolerateEmptyOrNullReload() {
        registry.reload(null);
        assertTrue(registry.all().isEmpty());

        registry.reload(List.of());
        assertTrue(registry.all().isEmpty());
    }

    /** 模型名为空的行没有定位意义，装载后不进快照（{@code shouldDropRowsWithoutModelKey}）。 */
    @Test
    void shouldDropRowsWithoutModelKey() {
        registry.reload(List.of(model("deepseek", "deepseek-chat"), model("dashscope", null)));

        assertEquals(1, registry.all().size());
    }

    /** 按能力筛选只返回含该能力的行（{@code byCapabilityShouldFilter}）。 */
    @Test
    void byCapabilityShouldFilterRows() {
        RegisteredModel embedding = model("dashscope", "text-embedding-v4");
        embedding.setCapabilities(Set.of(ModelCapability.EMBEDDING));
        registry.reload(List.of(model("deepseek", "deepseek-chat"), embedding));

        List<RegisteredModel> rows = registry.byCapability(ModelCapability.EMBEDDING);

        assertEquals(1, rows.size());
        assertEquals("text-embedding-v4", rows.getFirst().getModelKey());
        assertTrue(registry.byCapability(null).isEmpty());
    }

    /** 厂商码 + 模型名定位：空参数与未命中都返回空（{@code findShouldLocateByVendorAndModelKey}）。 */
    @Test
    void findShouldLocateByVendorAndModelKey() {
        registry.reload(List.of(model("deepseek", "deepseek-chat"), model("dashscope", "qwen-max")));

        assertEquals("deepseek-chat", registry.find("deepseek", "deepseek-chat").orElseThrow().getModelKey());
        assertTrue(registry.find("deepseek", "qwen-max").isEmpty());
        assertTrue(registry.find(null, "qwen-max").isEmpty());
        assertTrue(registry.find("deepseek", null).isEmpty());
    }
}
