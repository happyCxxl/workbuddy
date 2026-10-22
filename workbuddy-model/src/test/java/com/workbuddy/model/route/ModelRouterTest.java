package com.workbuddy.model.route;

import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.model.catalog.ModelCatalogPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 候选路由单测：能力/启用/模型名/限额四道过滤与策略排序的应用。
 */
@ExtendWith(MockitoExtension.class)
class ModelRouterTest {

    @Mock
    private ModelCatalogPort modelCatalogPort;

    private ModelRouter router;

    @BeforeEach
    void setUp() {
        router = new ModelRouter(modelCatalogPort, new FirstMatchStrategy());
    }

    private ModelDescriptor model(String modelKey, String status, Integer rpm, Integer tpm) {
        ModelDescriptor model = new ModelDescriptor();
        model.setVendorCode("dashscope");
        model.setModelKey(modelKey);
        model.setCapabilities(java.util.Set.of(ModelCapability.CHAT));
        model.setStatus(status);
        model.setRpmLimit(rpm);
        model.setTpmLimit(tpm);
        return model;
    }

    /**
     * 只保留启用、限额已配且模型名非空的候选（{@code shouldFilterByStatusAndLimits}）。
     */
    @Test
    void shouldFilterByStatusAndLimits() {
        when(modelCatalogPort.listByCapability(ModelCapability.CHAT)).thenReturn(List.of(
                model("a", "ENABLED", 60, 1000),
                model("b", "DISABLED", 60, 1000),
                model("c", "ENABLED", null, 1000),
                model("d", "ENABLED", 60, null),
                model(" ", "ENABLED", 60, 1000)));

        List<ModelDescriptor> candidates = router.candidates(ModelCapability.CHAT, null);

        assertEquals(1, candidates.size());
        assertEquals("a", candidates.getFirst().getModelKey());
    }

    /** 显式模型名只留精确命中（{@code shouldFilterByModelKey}）。 */
    @Test
    void shouldFilterByModelKey() {
        when(modelCatalogPort.listByCapability(ModelCapability.EMBEDDING)).thenReturn(List.of(
                model("text-embedding-v4", "ENABLED", 60, 1000),
                model("text-embedding-v3", "ENABLED", 60, 1000)));

        List<ModelDescriptor> candidates = router.candidates(ModelCapability.EMBEDDING, "text-embedding-v3");

        assertEquals(1, candidates.size());
        assertEquals("text-embedding-v3", candidates.getFirst().getModelKey());
    }

    /** 能力为空直接空候选，不查目录（{@code shouldSkipCatalogWhenCapabilityAbsent}）。 */
    @Test
    void shouldSkipCatalogWhenCapabilityAbsent() {
        assertTrue(router.candidates(null, null).isEmpty());
        verifyNoInteractions(modelCatalogPort);
    }

    /** 排序交给策略：策略反转时候选顺序反转（{@code shouldApplyStrategyOrder}）。 */
    @Test
    void shouldApplyStrategyOrder() {
        router = new ModelRouter(modelCatalogPort, candidates -> {
            List<ModelDescriptor> reversed = new java.util.ArrayList<>(candidates);
            java.util.Collections.reverse(reversed);
            return reversed;
        });
        when(modelCatalogPort.listByCapability(ModelCapability.CHAT)).thenReturn(List.of(
                model("a", "ENABLED", 60, 1000),
                model("b", "ENABLED", 60, 1000)));

        List<ModelDescriptor> candidates = router.candidates(ModelCapability.CHAT, null);

        assertEquals(List.of("b", "a"), candidates.stream().map(ModelDescriptor::getModelKey).toList());
    }
}
