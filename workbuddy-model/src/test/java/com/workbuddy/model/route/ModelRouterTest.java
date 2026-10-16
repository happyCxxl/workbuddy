package com.workbuddy.model.route;

import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.enums.model.RouteReason;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.model.invoke.ModelErrorClassifier;
import com.workbuddy.model.support.RouteFixture;
import com.workbuddy.model.support.TestRandomSource;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由选型单测：硬闸门逐条剔除、三信号各自的触发面、「快但忙 vs 慢但闲」的容量优先结论、
 * 并列候选的抽样、熔断与恢复爬升段的选型表现。
 *
 * <p>驱动方式：三个信号的读数直接写进 {@link RouteStateStore}，健康读数写进 {@link ModelCircuitBreakers}，
 * 时间由 {@link RouteFixture} 的时间源推进（熔断器接同一时间源驱动的时钟），随机取自由用例给定，
 * 全程不连库、不连厂商。
 *
 * @author cxxl
 */
class ModelRouterTest {

    private static final String VENDOR = "dashscope";

    /** 名字靠前的候选（字典序在前，并列时作为定序依据） */
    private static final String FIRST = "qwen-max";

    /** 名字靠后的候选 */
    private static final String SECOND = "qwen-plus";

    /** 第三个并列候选 */
    private static final String THIRD = "qwen-turbo";

    /** 用例的恢复放行爬升窗口（毫秒）：百毫秒尺度，爬升段走完只需要一次短等待 */
    private static final long RAMP_MILLIS = 200L;

    /** 爬升走完的判断余量（毫秒） */
    private static final long RAMP_MARGIN_MILLIS = 40L;

    /** 用例熔断参数的错误率统计窗口（秒）：与生产默认值一致 */
    private static final int USE_CASE_ERROR_WINDOW_SECONDS = 60;

    /** 用例熔断参数的失败率越线比例：与生产默认值一致 */
    private static final double USE_CASE_ERROR_TRIGGER_RATIO = 0.05D;

    @Test
    void candidatesWithSameReadingsShouldPickByNameUnderFixedRandom() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, FIRST, 100).withChatModel(VENDOR, SECOND, 100);
        ModelRouter router = fixture.router();

        ModelRouter.Decision selected = router.plan(ModelCapability.CHAT).selected();

        // 两个候选读数一致即分数并列：固定随机源下按模型名定序，qwen-max 在前
        assertEquals(FIRST, selected.modelKey());
        assertEquals(RouteReason.OK, router.reasonOf(selected, null));
        assertEquals(0D, selected.score(), 0.0000001D);
        assertEquals(0D, selected.occupancy(), 0.0000001D);
    }

    @Test
    void busyButFastVersusIdleButSlowShouldPickIdleOne() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, FIRST, 100).withChatModel(VENDOR, SECOND, 100);
        // 忙的那个：80 次调用占 80% 占用，延迟只有 100 毫秒
        occupy(fixture.stateStore(), FIRST, 80, 100L);
        // 闲的那个：8 次调用占 8% 占用，延迟 900 毫秒
        occupy(fixture.stateStore(), SECOND, 8, 900L);
        ModelRouter router = fixture.router();

        ModelRouter.RoutePlan plan = router.plan(ModelCapability.CHAT);
        ModelRouter.Decision selected = plan.selected();

        assertEquals(SECOND, selected.modelKey(), "容量权重 1.0 高于延迟权重 0.5，应选中空闲者");
        assertEquals(0.08D, selected.occupancy(), 0.0001D);
        assertEquals(-0.36D, selected.relativeLoad(), 0.0001D);
        assertEquals(0.4D, selected.relativeLatency(), 0.0001D);
        // 容量项 1.0 × 0.36 = 0.36 压过延迟项 0.5 × 0.4 = 0.20
        assertEquals(0.16D, selected.score(), 0.0001D);
        assertEquals(FIRST, plan.ordered().get(1).modelKey());
        assertEquals(RouteReason.CAPACITY_HIGH, router.reasonOf(selected, plan.ordered().get(1), null));
    }

    @Test
    void fastButBusyVersusSlowButIdleShouldPickTheBusyOneWhenTheTwoTermsTie() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, FIRST, 100).withChatModel(VENDOR, SECOND, 100);
        occupy(fixture.stateStore(), FIRST, 80, 100L);
        occupy(fixture.stateStore(), SECOND, 8, 3_000L);
        ModelRouter router = fixture.router();

        ModelRouter.RoutePlan plan = router.plan(ModelCapability.CHAT);
        ModelRouter.Decision selected = plan.selected();

        assertEquals(FIRST, selected.modelKey(), "延迟优势大到抵过容量优势时，选型翻到快而忙的那个");
        assertEquals(0.8D, selected.occupancy(), 0.0001D);
        assertEquals(RouteReason.CAPACITY_HIGH, router.reasonOf(selected, plan.ordered().get(1), null));
    }

    @Test
    void capacityAdvantageShouldBeatLatencyAdvantageUnderDefaultWeights() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, FIRST, 100).withChatModel(VENDOR, SECOND, 100);
        occupy(fixture.stateStore(), FIRST, 80, 100L);

        // 空闲者延迟再高也仍被选中：占用率固定为 0.08
        for (long idleLatencyMs : new long[] {100L, 900L}) {
            occupy(fixture.stateStore(), SECOND, 1, idleLatencyMs);
            ModelRouter.RoutePlan plan = fixture.router().plan(ModelCapability.CHAT);
            assertEquals(SECOND, plan.selected().modelKey(),
                    "空闲者延迟 " + idleLatencyMs + " 毫秒时仍应按容量优先选中它");
            assertEquals(RouteReason.CAPACITY_HIGH,
                    fixture.router().reasonOf(plan.selected(), plan.ordered().get(1), null));
        }
    }

    @Test
    void idleButVerySlowCandidateShouldLoseToTheBusyOne() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, FIRST, 100).withChatModel(VENDOR, SECOND, 100);
        occupy(fixture.stateStore(), FIRST, 80, 100L);
        occupy(fixture.stateStore(), SECOND, 8, 3_000L);
        ModelRouter router = fixture.router();

        ModelRouter.RoutePlan plan = router.plan(ModelCapability.CHAT);

        // 倍数差 3 秒时延迟项 0.5 × 2.47 才压过容量项 1.0 × 0.36：容量优先是阈值内的口径，不是无条件的
        assertEquals(FIRST, plan.selected().modelKey());
        assertEquals(RouteReason.CAPACITY_HIGH, router.reasonOf(plan.selected(), plan.ordered().get(1), null));
    }

    @Test
    void raisingLatencyWeightShouldFlipThePick() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, FIRST, 100).withChatModel(VENDOR, SECOND, 100);
        occupy(fixture.stateStore(), FIRST, 80, 100L);
        occupy(fixture.stateStore(), SECOND, 8, 900L);

        // 默认权重下容量项占优，选中空闲但慢的那个
        assertEquals(SECOND, fixture.router().plan(ModelCapability.CHAT).selected().modelKey());

        // 延迟权重调到 100：延迟项压过容量项，选型翻到快而忙的那个
        fixture.properties().setBetaLatency(100D);

        assertEquals(FIRST, fixture.router().plan(ModelCapability.CHAT).selected().modelKey());
    }

    @Test
    void idleCandidatesShouldPickFastestOneAndMarkLatencyHigh() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, FIRST, 100).withChatModel(VENDOR, SECOND, 100);
        occupy(fixture.stateStore(), FIRST, 10, 100L);
        occupy(fixture.stateStore(), SECOND, 10, 900L);
        ModelRouter router = fixture.router();

        ModelRouter.RoutePlan plan = router.plan(ModelCapability.CHAT);
        ModelRouter.Decision selected = plan.selected();

        assertEquals(FIRST, selected.modelKey(), "占用相同则看延迟，应选中延迟低者");
        assertEquals(RouteReason.LATENCY_HIGH, router.reasonOf(selected, plan.ordered().get(1), null));
        assertEquals(0.1D, selected.occupancy(), 0.0001D);
        assertEquals(-0.4D, selected.relativeLatency(), 0.0001D);
        assertEquals(0.2D, selected.score(), 0.0001D);
    }

    @Test
    void tieAmongMoreThanTwoCandidatesShouldNotAlwaysPickSameOne() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, FIRST, 100).withChatModel(VENDOR, SECOND, 100)
                .withChatModel(VENDOR, THIRD, 100);
        fixture.withRandomSource(TestRandomSource.random(new Random(20261014L)));
        ModelRouter router = fixture.router();
        Set<String> tied = Set.of(FIRST, SECOND, THIRD);
        Set<String> picked = new HashSet<>();

        for (int i = 0; i < 60; i++) {
            ModelRouter.Decision selected = router.plan(ModelCapability.CHAT).selected();
            assertTrue(tied.contains(selected.modelKey()), "选中者应落在并列集合内：" + selected.modelKey());
            picked.add(selected.modelKey());
        }

        assertTrue(picked.size() >= 2, "三个并列候选不应固定同一个，实际选中 " + picked);
    }

    @Test
    void disabledCandidateShouldBeRejected() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, SECOND, 100);
        fixture.catalog().register(disabledDescriptor(FIRST));
        ModelRouter router = fixture.router();

        assertEquals(SECOND, router.plan(ModelCapability.CHAT).selected().modelKey());
    }

    @Test
    void candidateWithoutConfiguredVendorShouldBeRejected() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, SECOND, 100);
        fixture.withChatModel("other-vendor", FIRST, 100);
        ModelRouter router = fixture.router();

        assertEquals(SECOND, router.plan(ModelCapability.CHAT).selected().modelKey());
    }

    @Test
    void allCandidatesRejectedShouldReportVendorUnknown() {
        RouteFixture fixture = fixture();
        fixture.withChatModel(VENDOR, SECOND, 100);
        ModelRouter router = fixture.router();

        KnowledgeException error = assertThrows(KnowledgeException.class, () -> router.plan(ModelCapability.CHAT));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
        assertTrue(error.getMessage().contains("厂商未配置或密钥为空"), error.getMessage());
    }

    @Test
    void emptyCapabilityShouldReportVendorUnknown() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        ModelRouter router = fixture.router();

        KnowledgeException error = assertThrows(KnowledgeException.class, () -> router.plan(ModelCapability.CHAT));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
        assertTrue(error.getMessage().contains("已配置厂商"), error.getMessage());
    }

    @Test
    void candidateAtRpmLimitShouldBeGatedOut() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, FIRST, 2).withChatModel(VENDOR, SECOND, 100);
        occupy(fixture.stateStore(), FIRST, 2, 50L);
        ModelRouter router = fixture.router();

        assertEquals(SECOND, router.plan(ModelCapability.CHAT).selected().modelKey());
    }

    @Test
    void modelWithoutLimitsShouldNotTakePartInOccupancy() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, FIRST, null);
        occupy(fixture.stateStore(), FIRST, 5000, 30L);
        ModelRouter router = fixture.router();

        ModelRouter.Decision selected = router.plan(ModelCapability.CHAT).selected();

        assertEquals(FIRST, selected.modelKey());
        assertEquals(0D, selected.occupancy(), 0.0001D);
        assertEquals(RouteReason.OK, router.reasonOf(selected, null));
    }

    @Test
    void occupancyAtTriggerRatioShouldMarkCapacityHigh() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, FIRST, 100);
        occupy(fixture.stateStore(), FIRST, 80, 50L);
        ModelRouter router = fixture.router();

        ModelRouter.Decision selected = router.plan(ModelCapability.CHAT).selected();

        assertEquals(0.8D, selected.occupancy(), 0.0001D);
        assertEquals(RouteReason.CAPACITY_HIGH, router.reasonOf(selected, null));
    }

    @Test
    void occupancyBelowTriggerRatioShouldMarkOk() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, FIRST, 100);
        occupy(fixture.stateStore(), FIRST, 79, 50L);
        ModelRouter router = fixture.router();

        assertEquals(RouteReason.OK, router.reasonOf(router.plan(ModelCapability.CHAT).selected(), null));
    }
    @Test
    void breakerOpenCandidateShouldBeGatedOut() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, FIRST, null).withChatModel(VENDOR, SECOND, null);
        fixture.withOpenBreaker(VENDOR, FIRST);
        ModelRouter router = fixture.router();

        // 熔断打开：该候选在选型路径被剔除，另一个候选接住
        assertEquals(SECOND, router.plan(ModelCapability.CHAT).selected().modelKey());
        assertEquals(SECOND, router.plan(ModelCapability.CHAT).selected().modelKey());
    }

    @Test
    void candidateWithOpenBreakerShouldReportBreakerRejection() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, FIRST, null);
        fixture.withOpenBreaker(VENDOR, FIRST);

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> fixture.router().plan(ModelCapability.CHAT));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
        assertTrue(error.getMessage().contains("熔断未放行"), error.getMessage());
    }

    @Test
    void halfOpenCandidateShouldBeReleasedAsProbe() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, FIRST, null).withChatModel(VENDOR, SECOND, null);
        fixture.withHalfOpenBreaker(VENDOR, FIRST);
        ModelRouter router = fixture.router();

        // 半开：该候选回到候选集，本次为探测，不受爬升比例限制
        ModelRouter.Decision selected = router.plan(ModelCapability.CHAT).selected();

        assertEquals(FIRST, selected.modelKey());
        assertTrue(selected.admitted());
        assertTrue(selected.admittedBySlowStart());
        assertTrue(fixture.breakers().inSlowStart(VENDOR, FIRST));
    }

    @Test
    void recoveredCandidateShouldComeBackGraduallyAfterHalfOpen() throws InterruptedException {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, FIRST, null).withChatModel(VENDOR, SECOND, null);
        fixture.withRandomSource(TestRandomSource.fixed(10));
        fixture.withBreakers(shortRampBreakers());
        fixture.withHalfOpenBreaker(VENDOR, FIRST);
        ModelRouter router = fixture.router();

        // 半开探测：本次选型即放行该候选
        assertEquals(FIRST, router.plan(ModelCapability.CHAT).selected().modelKey());
        fixture.breakers().onSuccess(VENDOR, FIRST);
        assertEquals(CircuitBreaker.State.CLOSED, fixture.breakers().state(VENDOR, FIRST));
        assertTrue(fixture.breakers().inSlowStart(VENDOR, FIRST), "探测成功即进恢复爬升段");

        // 爬升段：取样 10 低于门槛即放行
        assertEquals(FIRST, router.plan(ModelCapability.CHAT).selected().modelKey());

        // 爬升满量后不再按比例放行
        Thread.sleep(RAMP_MILLIS + RAMP_MARGIN_MILLIS);
        assertFalse(fixture.breakers().inSlowStart(VENDOR, FIRST));
        assertEquals(FIRST, router.plan(ModelCapability.CHAT).selected().modelKey());
    }

    @Test
    void slowStartRejectionShouldFallBackToNextAdmittedCandidate() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, FIRST, null).withChatModel(VENDOR, SECOND, null);
        // 取样 90 落在 20% 门槛之外：首个候选本次不进首选排序，另一个接住
        fixture.withRandomSource(TestRandomSource.fixed(90));
        fixture.withHalfOpenBreaker(VENDOR, FIRST);
        ModelRouter router = fixture.router();
        assertEquals(FIRST, router.plan(ModelCapability.CHAT).selected().modelKey());
        fixture.breakers().onSuccess(VENDOR, FIRST);
        assertEquals(CircuitBreaker.State.CLOSED, fixture.breakers().state(VENDOR, FIRST));

        ModelRouter.RoutePlan plan = router.plan(ModelCapability.CHAT);
        List<ModelRouter.Decision> ordered = plan.ordered();

        assertEquals(SECOND, plan.selected().modelKey());
        assertEquals(2, ordered.size());
        assertEquals(FIRST, ordered.get(1).modelKey());
        assertFalse(ordered.get(1).admitted());
        assertTrue(ordered.get(1).admittedBySlowStart());
        assertEquals(RouteReason.SLOW_START, router.reasonOf(ordered.get(1), null));
    }

    @Test
    void fallbackOriginShouldOverrideRouteReason() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, FIRST, null).withChatModel(VENDOR, SECOND, null);
        ModelRouter router = fixture.router();
        ModelRouter.Decision selected = router.plan(ModelCapability.CHAT).selected();

        assertEquals(RouteReason.FALLBACK_FROM, router.reasonOf(selected, FIRST));
        assertEquals(RouteReason.OK, router.reasonOf(selected, null));
    }

    @Test
    void planOrderShouldCarryAllCandidatesForFallback() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, FIRST, 100).withChatModel(VENDOR, SECOND, 100);
        occupy(fixture.stateStore(), FIRST, 90, 100L);
        ModelRouter router = fixture.router();

        ModelRouter.RoutePlan plan = router.plan(ModelCapability.CHAT);

        assertEquals(SECOND, plan.selected().modelKey());
        assertEquals(2, plan.ordered().size());
        assertEquals(FIRST, plan.ordered().get(1).modelKey());
        assertNull(plan.fallbackFrom());
        assertEquals(FIRST, plan.from(FIRST).fallbackFrom());
    }

    @Test
    void selectedModelShouldBeRecordedOnStateStore() {
        RouteFixture fixture = fixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, SECOND, 100);
        ModelRouter router = fixture.router();

        router.plan(ModelCapability.CHAT);

        assertEquals(RouteFixture.START_MILLIS, fixture.stateStore().snapshot(VENDOR, SECOND).lastSelectedAt());
    }

    /** 用例夹具：时间起点固定，窗口取用例尺度 */
    private RouteFixture fixture() {
        return new RouteFixture();
    }

    /**
     * 百毫秒级爬升窗口的熔断器：熔断参数与生产路径共用同一份映射，只把窗口收窄到用例尺度，
     * 爬升段走完只需要一次短等待。
     */
    private ModelCircuitBreakers shortRampBreakers() {
        CircuitBreakerConfig config = ModelCircuitBreakers.configFor(USE_CASE_ERROR_TRIGGER_RATIO,
                USE_CASE_ERROR_WINDOW_SECONDS, RAMP_MILLIS, new ModelErrorClassifier());
        return new ModelCircuitBreakers(config, RAMP_MILLIS);
    }

    /** 造占用读数：按次数记成功样本，占用率 = 次数 ÷ 限额 */
    private void occupy(RouteStateStore store, String modelKey, int count, long latencyMs) {
        for (int i = 0; i < count; i++) {
            store.record(VENDOR, modelKey, true, latencyMs, null, 100L, true);
        }
    }

    /** 停用的目录项：状态为 DISABLED */
    private ModelDescriptor disabledDescriptor(String modelKey) {
        ModelDescriptor descriptor = new ModelDescriptor();
        descriptor.setVendorCode(VENDOR);
        descriptor.setModelKey(modelKey);
        descriptor.setCapabilities(Set.of(ModelCapability.CHAT));
        descriptor.setStatus("DISABLED");
        return descriptor;
    }
}
