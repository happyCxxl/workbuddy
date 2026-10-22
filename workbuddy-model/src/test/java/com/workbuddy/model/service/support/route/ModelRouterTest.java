package com.workbuddy.model.service.support.route;

import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.model.ModelCatalogPort;
import com.workbuddy.model.ModelRef;
import com.workbuddy.model.config.ModelRouteProperties;
import com.workbuddy.model.service.support.capacity.ModelCapacityGuard;
import com.workbuddy.model.service.support.capacity.ModelCooldownStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 候选路由单测：硬闸门（能力/启用/模型名/限额/冷却）与容量优先排序。
 */
@ExtendWith(MockitoExtension.class)
class ModelRouterTest {

    @Mock
    private ModelCatalogPort modelCatalogPort;

    @Mock
    private ModelCapacityGuard modelCapacityGuard;

    @Mock
    private ModelCooldownStore modelCooldownStore;

    @Mock
    private ModelCircuitBreakers modelCircuitBreakers;

    @Mock
    private RouteStateStore routeStateStore;

    private ModelRouteProperties routeProperties;

    private ModelRouter router;

    @BeforeEach
    void setUp() {
        routeProperties = new ModelRouteProperties();
        routeProperties.setSlowStartSeconds(45);
        router = new ModelRouter(modelCatalogPort, new CapacityFirstStrategy(),
                modelCapacityGuard, modelCooldownStore, modelCircuitBreakers, routeStateStore, routeProperties);
        lenient().when(modelCapacityGuard.snapshot(any(), any(), any())).thenReturn(new CapacitySnapshot(1, 1));
        lenient().when(modelCooldownStore.coolingDown(any())).thenReturn(false);
        lenient().when(modelCircuitBreakers.isOpen(any())).thenReturn(false);
        lenient().when(routeStateStore.slowStartFactor(any(), anyLong())).thenReturn(1.0);
        lenient().when(routeStateStore.latencyMillis(any())).thenReturn(0L);
    }

    private ModelDescriptor model(String modelKey, String status, Integer rpm, Integer tpm) {
        ModelDescriptor model = new ModelDescriptor();
        model.setVendorCode("dashscope");
        model.setModelKey(modelKey);
        model.setCapabilities(Set.of(ModelCapability.CHAT));
        model.setStatus(status);
        model.setRpmLimit(rpm);
        model.setTpmLimit(tpm);
        return model;
    }

    /** 只保留启用、限额已配且模型名非空的候选（{@code shouldFilterByStatusAndLimits}）。 */
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

    /** 冷却中的模型不进候选（{@code shouldExcludeCoolingDown}）。 */
    @Test
    void shouldExcludeCoolingDown() {
        when(modelCatalogPort.listByCapability(ModelCapability.CHAT)).thenReturn(List.of(
                model("a", "ENABLED", 60, 1000),
                model("b", "ENABLED", 60, 1000)));
        when(modelCooldownStore.coolingDown(new ModelRef("dashscope", "a"))).thenReturn(true);

        List<ModelDescriptor> candidates = router.candidates(ModelCapability.CHAT, null);

        assertEquals(1, candidates.size());
        assertEquals("b", candidates.getFirst().getModelKey());
    }

    /** 容量分高的排前面（{@code shouldOrderByCapacity}）。 */
    @Test
    void shouldOrderByCapacity() {
        when(modelCatalogPort.listByCapability(ModelCapability.CHAT)).thenReturn(List.of(
                model("a", "ENABLED", 60, 1000),
                model("b", "ENABLED", 60, 1000)));
        when(modelCapacityGuard.snapshot(new ModelRef("dashscope", "a"), 60, 1000))
                .thenReturn(new CapacitySnapshot(0.2, 0.2));
        when(modelCapacityGuard.snapshot(new ModelRef("dashscope", "b"), 60, 1000))
                .thenReturn(new CapacitySnapshot(0.9, 0.9));

        List<ModelDescriptor> candidates = router.candidates(ModelCapability.CHAT, null);

        assertEquals(List.of("b", "a"), candidates.stream().map(ModelDescriptor::getModelKey).toList());
    }

    /** slow start 折减容量分：恢复期模型排后（{@code shouldApplySlowStartFactor}）。 */
    @Test
    void shouldApplySlowStartFactor() {
        when(modelCatalogPort.listByCapability(ModelCapability.CHAT)).thenReturn(List.of(
                model("a", "ENABLED", 60, 1000),
                model("b", "ENABLED", 60, 1000)));
        when(modelCapacityGuard.snapshot(new ModelRef("dashscope", "a"), 60, 1000))
                .thenReturn(new CapacitySnapshot(0.5, 0.5));
        when(modelCapacityGuard.snapshot(new ModelRef("dashscope", "b"), 60, 1000))
                .thenReturn(new CapacitySnapshot(0.5, 0.5));
        when(routeStateStore.slowStartFactor(new ModelRef("dashscope", "a"), routeProperties.getSlowStartSeconds()))
                .thenReturn(0.2);
        when(routeStateStore.slowStartFactor(new ModelRef("dashscope", "b"), routeProperties.getSlowStartSeconds()))
                .thenReturn(1.0);

        List<ModelDescriptor> candidates = router.candidates(ModelCapability.CHAT, null);

        assertEquals(List.of("b", "a"), candidates.stream().map(ModelDescriptor::getModelKey).toList());
    }

    /** 熔断打开的模型不进候选（{@code shouldExcludeOpenCircuit}）。 */
    @Test
    void shouldExcludeOpenCircuit() {
        when(modelCatalogPort.listByCapability(ModelCapability.CHAT)).thenReturn(List.of(
                model("a", "ENABLED", 60, 1000),
                model("b", "ENABLED", 60, 1000)));
        when(modelCircuitBreakers.isOpen(new ModelRef("dashscope", "a"))).thenReturn(true);

        List<ModelDescriptor> candidates = router.candidates(ModelCapability.CHAT, null);

        assertEquals(1, candidates.size());
        assertEquals("b", candidates.getFirst().getModelKey());
    }

    /** 已尝试过的模型跳过，取下一个（{@code shouldSelectNextNotTried}）。 */
    @Test
    void shouldSelectNextNotTried() {
        when(modelCatalogPort.listByCapability(ModelCapability.CHAT)).thenReturn(List.of(
                model("a", "ENABLED", 60, 1000),
                model("b", "ENABLED", 60, 1000)));

        ModelDescriptor selected = router.select(ModelCapability.CHAT, null,
                Set.of(new ModelRef("dashscope", "a")));

        assertEquals("b", selected.getModelKey());
    }

    /** 全部已尝试过：返回空（{@code shouldReturnNullWhenAllTried}）。 */
    @Test
    void shouldReturnNullWhenAllTried() {
        when(modelCatalogPort.listByCapability(ModelCapability.CHAT)).thenReturn(List.of(
                model("a", "ENABLED", 60, 1000)));

        ModelDescriptor selected = router.select(ModelCapability.CHAT, null,
                Set.of(new ModelRef("dashscope", "a")));

        assertNull(selected);
    }
}
