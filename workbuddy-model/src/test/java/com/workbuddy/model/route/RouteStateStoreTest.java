package com.workbuddy.model.route;

import com.workbuddy.model.config.ModelRouteProperties;
import com.workbuddy.model.support.TestTimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由状态单测：在途计数、延迟 EWMA、占用窗口与选中时刻的读数，以及时间推进后的变化。
 * 状态维度按模型算，同一个模型的多个能力共用一份读数（键为厂商码与模型名）。
 * 健康面（失败率、熔断、恢复放行）见 {@link ModelCircuitBreakersTest}。
 *
 * @author cxxl
 */
class RouteStateStoreTest {

    /** 用例时间起点 */
    private static final long START = 1_700_000_000_000L;

    private static final String VENDOR = "dashscope";

    private static final String MODEL = "qwen-max";

    /** 同厂商的另一个模型：用于验证状态按模型分开 */
    private static final String OTHER_MODEL = "qwen-plus";

    private ModelRouteProperties properties;

    private TestTimeSource time;

    private RouteStateStore store;

    @BeforeEach
    void setUp() {
        properties = new ModelRouteProperties();
        properties.setLatencyWindowSeconds(60);
        time = new TestTimeSource(START);
        store = new RouteStateStore(properties, time);
    }

    @Test
    void emptyStateShouldReadAsZero() {
        RouteStateStore.Snapshot snapshot = store.snapshot(VENDOR, MODEL);

        assertEquals(0, snapshot.inFlight());
        assertEquals(0, snapshot.rpmCount());
        assertEquals(0L, snapshot.tpmTokens());
        assertEquals(0D, snapshot.latencyEwmaMs());
        assertEquals(0, snapshot.totalCalls());
        assertEquals(0, snapshot.totalFailures());
        assertEquals(0L, snapshot.lastSelectedAt());
    }

    @Test
    void inFlightShouldCountEnterAndExit() {
        store.enter(VENDOR, MODEL);
        store.enter(VENDOR, MODEL);

        assertEquals(2, store.snapshot(VENDOR, MODEL).inFlight());

        store.exit(VENDOR, MODEL);
        store.exit(VENDOR, MODEL);
        store.exit(VENDOR, MODEL);

        assertEquals(0, store.snapshot(VENDOR, MODEL).inFlight());
    }

    @Test
    void successShouldRecordLatencyAndOccupancySample() {
        store.enter(VENDOR, MODEL);
        store.record(VENDOR, MODEL, true, 120L, null, 40L, true);
        store.exit(VENDOR, MODEL);

        RouteStateStore.Snapshot snapshot = store.snapshot(VENDOR, MODEL);

        assertEquals(120D, snapshot.latencyEwmaMs());
        assertEquals(1, snapshot.latencySamples());
        assertEquals(1, snapshot.rpmCount());
        assertEquals(40L, snapshot.tpmTokens());
        assertEquals(1, snapshot.totalCalls());
        assertEquals(0, snapshot.totalFailures());
    }

    @Test
    void failureShouldRecordErrorCodeAndCounters() {
        store.record(VENDOR, MODEL, false, 20L, "MODEL_SERVER_ERROR", null, true);

        RouteStateStore.Snapshot snapshot = store.snapshot(VENDOR, MODEL);

        assertEquals(1, snapshot.totalCalls());
        assertEquals(1, snapshot.totalFailures());
        assertEquals(1, snapshot.rpmCount());
        assertEquals("MODEL_SERVER_ERROR", snapshot.lastErrorCode());
        assertEquals(0D, snapshot.latencyEwmaMs(), "失败调用不写延迟样本");
    }

    @Test
    void occupancySamplesOutsideWindowShouldBeDropped() {
        store.record(VENDOR, MODEL, true, 100L, null, 10L, true);

        time.advance(61_000L);

        RouteStateStore.Snapshot snapshot = store.snapshot(VENDOR, MODEL);

        assertEquals(0, snapshot.rpmCount());
        assertEquals(0L, snapshot.tpmTokens());
        assertEquals(1, snapshot.totalCalls());
    }

    @Test
    void latencyEwmaShouldFollowRecentSamples() {
        store.record(VENDOR, MODEL, true, 100L, null, null, false);
        for (int i = 0; i < 30; i++) {
            time.advance(1_000L);
            store.record(VENDOR, MODEL, true, 1000L, null, null, false);
        }

        RouteStateStore.Snapshot snapshot = store.snapshot(VENDOR, MODEL);

        assertTrue(snapshot.latencyEwmaMs() > 900D, "EWMA 应逼近平稳后的采样值，实际 " + snapshot.latencyEwmaMs());
        assertTrue(snapshot.latencyEwmaMs() <= 1000D, "EWMA 不应超过采样值，实际 " + snapshot.latencyEwmaMs());
    }

    @Test
    void latencySamplesOutsideWindowShouldNotCount() {
        store.record(VENDOR, MODEL, true, 500L, null, null, false);

        time.advance(61_000L);

        assertEquals(0, store.snapshot(VENDOR, MODEL).latencySamples());
    }

    @Test
    void stateShouldBeKeptPerModel() {
        store.record(VENDOR, MODEL, true, 100L, null, 10L, true);
        store.record(VENDOR, OTHER_MODEL, true, 900L, null, 20L, true);

        assertEquals(100D, store.snapshot(VENDOR, MODEL).latencyEwmaMs());
        assertEquals(10L, store.snapshot(VENDOR, MODEL).tpmTokens());
        assertEquals(900D, store.snapshot(VENDOR, OTHER_MODEL).latencyEwmaMs());
        assertEquals(20L, store.snapshot(VENDOR, OTHER_MODEL).tpmTokens());
    }

    @Test
    void selectionTimeShouldBeRecorded() {
        store.recordSelection(VENDOR, MODEL);

        assertEquals(START, store.snapshot(VENDOR, MODEL).lastSelectedAt());
    }
}
