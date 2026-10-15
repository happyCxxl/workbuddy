package com.workbuddy.model.route;

import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.model.config.ModelRouteProperties;
import com.workbuddy.model.invoke.ModelErrorClassifier;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnresolvedModelServerException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型熔断器单测：参数映射、三条公开状态迁移路径、失败口径、两条口径的差异点与打开保持的时间行为。
 *
 * <p>驱动方式：打开 / 半开 / 关闭三种前提由库的公开迁移方法构造（{@code transitionToOpenState}、
 * {@code transitionToHalfOpenState}、{@code reset}），不注入时钟；时间行为单独一组用例，
 * 它们按 {@link ModelCircuitBreakers#configFor} 自带一份百毫秒级窗口的熔断参数并把爬升时长一并收窄，
 * 恢复放行段与打开保持的真实等待都在百毫秒量级，等待上界两千毫秒。生效值由参数映射那条用例断言。
 *
 * @author cxxl
 */
class ModelCircuitBreakersTest {

    private static final String VENDOR = "dashscope";

    private static final String MODEL = "qwen-max";

    /** 达到最小调用条数并全部失败所需的次数：等于熔断器的最少调用条数 */
    private static final int OPENING_FAILURES = 10;

    /** 计入失败率的失败：服务端错误 */
    private static final Throwable SERVER_ERROR = new InternalServerException("500");

    /** 打开保持与恢复爬升的用例窗口（毫秒）：真实等待的尺度取百毫秒级 */
    private static final long SHORT_WINDOW_MILLIS = 100L;

    /** 用例窗口的熔断参数：与生产路径共用同一份映射，只把打开保持时长收窄 */
    private static final int USE_CASE_ERROR_WINDOW_SECONDS = 60;

    /** 用例窗口的错误率越线比例：与生产默认值一致 */
    private static final double USE_CASE_ERROR_TRIGGER_RATIO = 0.05D;

    /** 打开保持到期的等待上界（毫秒）：留出调度余量，仍属短等待 */
    private static final long PROBE_WAIT_MILLIS = 2_000L;

    /** 爬升段走完的判断余量（毫秒） */
    private static final long RAMP_MARGIN_MILLIS = 40L;

    /** 爬升段读数前的等待余量（毫秒）：让经过时长明确大于零 */
    private static final long RAMP_PROBE_MARGIN_MILLIS = 20L;

    private ModelRouteProperties properties;

    private ModelCircuitBreakers breakers;

    @BeforeEach
    void setUp() {
        properties = new ModelRouteProperties();
        properties.setErrorTriggerRatio(0.05D);
        properties.setErrorWindowSeconds(60);
        properties.setCooldownSeconds(60);
        properties.setSlowStartSeconds(45);
        breakers = new ModelCircuitBreakers(properties, new ModelErrorClassifier());
    }

    @Test
    void parametersShouldMapFromRouteProperties() {
        CircuitBreakerConfig config = breakers.configuration();

        assertEquals(5.0F, config.getFailureRateThreshold());
        assertEquals(CircuitBreakerConfig.SlidingWindowType.TIME_BASED, config.getSlidingWindowType());
        assertEquals(60, config.getSlidingWindowSize());
        assertEquals(10, config.getMinimumNumberOfCalls());
        assertEquals(1, config.getPermittedNumberOfCallsInHalfOpenState());
        assertTrue(config.isAutomaticTransitionFromOpenToHalfOpenEnabled());
        // 打开保持时长取 cooldown-seconds 的毫秒值，爬升窗口取 slow-start-seconds 的毫秒值
        assertEquals(60_000L, config.getWaitIntervalFunctionInOpenState().apply(1).longValue());
        assertEquals(45_000L, breakers.slowStartMillis());
    }

    @Test
    void closedBreakerShouldPermitCall() {
        assertEquals(CircuitBreaker.State.CLOSED, breakers.state(VENDOR, MODEL));
        assertTrue(breakers.tryAcquire(VENDOR, MODEL));

        breakers.release(VENDOR, MODEL);

        assertTrue(breakers.tryAcquire(VENDOR, MODEL), "归还后许可仍可用");
    }

    @Test
    void openBreakerShouldRejectCall() {
        breakers.breaker(VENDOR, MODEL).transitionToOpenState();

        assertEquals(CircuitBreaker.State.OPEN, breakers.state(VENDOR, MODEL));
        assertFalse(breakers.tryAcquire(VENDOR, MODEL), "打开期间不放行");
    }

    @Test
    void resetBreakerShouldCloseAndClearCounters() {
        recordFailures(OPENING_FAILURES);
        assertEquals(CircuitBreaker.State.OPEN, breakers.state(VENDOR, MODEL));

        breakers.breaker(VENDOR, MODEL).reset();

        assertEquals(CircuitBreaker.State.CLOSED, breakers.state(VENDOR, MODEL));
        assertEquals(0, breakers.breaker(VENDOR, MODEL).getMetrics().getNumberOfFailedCalls(), "复位后失败条数从零起算");
        assertTrue(breakers.tryAcquire(VENDOR, MODEL));
    }

    @Test
    void halfOpenBreakerShouldReleaseSingleProbe() {
        openToHalfOpen();

        assertEquals(CircuitBreaker.State.HALF_OPEN, breakers.state(VENDOR, MODEL));
        assertTrue(breakers.tryAcquire(VENDOR, MODEL), "半开放行一次探测");
        assertFalse(breakers.tryAcquire(VENDOR, MODEL), "半开只放行一次");
    }

    @Test
    void releasedProbeShouldBeUsableAgain() {
        openToHalfOpen();
        assertTrue(breakers.tryAcquire(VENDOR, MODEL));

        breakers.release(VENDOR, MODEL);

        assertTrue(breakers.tryAcquire(VENDOR, MODEL), "未被调用的候选归还后名额回到可用状态");
    }

    @Test
    void halfOpenProbeSuccessShouldCloseBreakerAndStartRamp() throws InterruptedException {
        openToHalfOpen();

        assertTrue(breakers.tryAcquire(VENDOR, MODEL));
        breakers.onSuccess(VENDOR, MODEL);

        assertEquals(CircuitBreaker.State.CLOSED, breakers.state(VENDOR, MODEL));
        assertTrue(breakers.inSlowStart(VENDOR, MODEL), "探测成功即进恢复爬升段");
        Thread.sleep(RAMP_PROBE_MARGIN_MILLIS);
        assertTrue(breakers.admissionRatio(VENDOR, MODEL) > 0.2D, "放行比例随经过时长上升");
    }

    @Test
    void halfOpenProbeFailureShouldReopenBreaker() {
        openToHalfOpen();
        assertTrue(breakers.tryAcquire(VENDOR, MODEL));

        breakers.onError(VENDOR, MODEL, SERVER_ERROR);

        assertEquals(CircuitBreaker.State.OPEN, breakers.state(VENDOR, MODEL));
        assertFalse(breakers.tryAcquire(VENDOR, MODEL), "重新打开后不放行");
    }

    @Test
    void admissionRatioShouldClimbToFullAfterSlowStartWindow() throws InterruptedException {
        ModelCircuitBreakers shortRamp = shortWindowBreakers(new ModelErrorClassifier());

        assertEquals(1D, shortRamp.admissionRatio(VENDOR, MODEL), 0.001D, "没转入过半开即按满量");

        shortRamp.breaker(VENDOR, MODEL).transitionToOpenState();
        shortRamp.breaker(VENDOR, MODEL).transitionToHalfOpenState();
        assertTrue(shortRamp.tryAcquire(VENDOR, MODEL));
        shortRamp.onSuccess(VENDOR, MODEL);

        assertTrue(shortRamp.inSlowStart(VENDOR, MODEL));
        assertTrue(shortRamp.admissionRatio(VENDOR, MODEL) >= 0.2D, "爬升段起点为两成");
        assertTrue(shortRamp.admissionRatio(VENDOR, MODEL) < 1D, "爬升段内不满量");

        Thread.sleep(SHORT_WINDOW_MILLIS + RAMP_MARGIN_MILLIS);

        assertFalse(shortRamp.inSlowStart(VENDOR, MODEL), "爬升段走完即退出恢复放行段");
        assertEquals(1D, shortRamp.admissionRatio(VENDOR, MODEL), 0.001D);
    }

    @Test
    void waitDurationInOpenStateShouldHoldCallsUntilElapsed() {
        ModelCircuitBreakers shortWait = shortWindowBreakers(new ModelErrorClassifier());

        assertEquals(SHORT_WINDOW_MILLIS,
                shortWait.configuration().getWaitIntervalFunctionInOpenState().apply(1).longValue());
        assertTrue(shortWait.tryAcquire(VENDOR, MODEL), "关闭状态放行");
        shortWait.breaker(VENDOR, MODEL).transitionToOpenState();

        assertEquals(CircuitBreaker.State.OPEN, shortWait.state(VENDOR, MODEL), "保持期内按打开记");
        assertFalse(shortWait.tryAcquire(VENDOR, MODEL), "保持期内不放行");
    }

    @Test
    void halfOpenWaitElapsedShouldReleaseProbeWithinShortWait() throws InterruptedException {
        ModelCircuitBreakers shortWait = shortWindowBreakers(new ModelErrorClassifier());
        shortWait.breaker(VENDOR, MODEL).transitionToOpenState();
        assertEquals(CircuitBreaker.State.OPEN, shortWait.state(VENDOR, MODEL), "刚打开时按打开记");

        // 打开保持到期由库的调度线程推进：先等到状态转半开（读数不改状态，不占探测名额）
        long deadline = System.currentTimeMillis() + PROBE_WAIT_MILLIS;
        CircuitBreaker.State state = CircuitBreaker.State.OPEN;
        while (state == CircuitBreaker.State.OPEN && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
            state = shortWait.state(VENDOR, MODEL);
        }

        assertEquals(CircuitBreaker.State.HALF_OPEN, state, "打开保持到期后应转入半开");
        assertTrue(shortWait.tryAcquire(VENDOR, MODEL), "半开放行一次探测");
        assertFalse(shortWait.tryAcquire(VENDOR, MODEL), "半开只放行一次");
    }

    @Test
    void failureRateOverThresholdShouldOpenBreaker() {
        recordFailures(OPENING_FAILURES);

        assertEquals(CircuitBreaker.State.OPEN, breakers.state(VENDOR, MODEL));
        assertFalse(breakers.tryAcquire(VENDOR, MODEL), "打开期间不放行");
    }

    @Test
    void callsBelowMinimumShouldNotOpenBreaker() {
        recordFailures(OPENING_FAILURES - 1);

        assertEquals(CircuitBreaker.State.CLOSED, breakers.state(VENDOR, MODEL));
        assertTrue(breakers.tryAcquire(VENDOR, MODEL), "未达最少调用条数即不判越线");
    }

    @Test
    void failureRateAtThresholdShouldOpenBreaker() {
        // 二十次调用里一次失败即 5%，与阈值相等也判越线
        for (int i = 0; i < OPENING_FAILURES * 2 - 1; i++) {
            breakers.onSuccess(VENDOR, MODEL);
        }
        breakers.onError(VENDOR, MODEL, SERVER_ERROR);

        assertEquals(CircuitBreaker.State.OPEN, breakers.state(VENDOR, MODEL));
    }

    @Test
    void failureRateUnderThresholdShouldKeepBreakerClosed() {
        // 三十次调用里失败一次：失败率 3.33%，低于 5% 阈值
        for (int i = 0; i < 29; i++) {
            breakers.onSuccess(VENDOR, MODEL);
        }
        breakers.onError(VENDOR, MODEL, SERVER_ERROR);

        assertEquals(CircuitBreaker.State.CLOSED, breakers.state(VENDOR, MODEL));
    }

    @Test
    void unhealthyFailuresShouldCountAsBreakerFailure() {
        assertEquals(CircuitBreaker.State.OPEN, stateAfterFailures("rate-limited", new RateLimitException("429")));
        assertEquals(CircuitBreaker.State.OPEN, stateAfterFailures("server-error", new InternalServerException("500")));
        assertEquals(CircuitBreaker.State.OPEN, stateAfterFailures("timeout", new TimeoutException("408")));
        assertEquals(CircuitBreaker.State.OPEN,
                stateAfterFailures("unreachable", new UnresolvedModelServerException("dns")));
        assertEquals(CircuitBreaker.State.OPEN,
                stateAfterFailures("refused", new RuntimeException(new ConnectException("Connection refused"))));
        assertEquals(CircuitBreaker.State.OPEN,
                stateAfterFailures("unknown-host", new RuntimeException(new UnknownHostException("nope.invalid"))));
    }

    @Test
    void healthyFailuresShouldNotCountAsBreakerFailure() {
        assertEquals(CircuitBreaker.State.CLOSED, stateAfterFailures("auth", new AuthenticationException("401")));
        assertEquals(CircuitBreaker.State.CLOSED, stateAfterFailures("bad-request", new InvalidRequestException("400")));
        assertEquals(CircuitBreaker.State.CLOSED,
                stateAfterFailures("content-filtered", new ContentFilteredException("DataInspectionFailed")));
        assertEquals(CircuitBreaker.State.CLOSED, stateAfterFailures("not-found", new ModelNotFoundException("404")));
        assertEquals(CircuitBreaker.State.CLOSED,
                stateAfterFailures("output-invalid", new KnowledgeException(ErrorCode.MODEL_OUTPUT_INVALID, "输出结构不符")));
    }

    /**
     * 两条口径的差异点之一：输出结构校验失败可降级，而不算厂商侧不健康。
     * 熔断器按后一条口径记成功，该模型留在候选集里。
     */
    @Test
    void fallbackAllowedOutputInvalidShouldNotCountAsBreakerFailure() {
        ModelErrorClassifier classifier = new ModelErrorClassifier();
        Throwable cause = new KnowledgeException(ErrorCode.MODEL_OUTPUT_INVALID, "输出结构不符");

        assertTrue(classifier.canFallback(cause), "输出结构不符可降级");
        assertFalse(classifier.isVendorUnhealthy(cause), "输出结构不符不指向厂商侧健康");

        for (int i = 0; i < OPENING_FAILURES; i++) {
            breakers.onError(VENDOR, MODEL, cause);
        }

        assertEquals(CircuitBreaker.State.CLOSED, breakers.state(VENDOR, MODEL));
        assertEquals(0, breakers.breaker(VENDOR, MODEL).getMetrics().getNumberOfFailedCalls());
        assertEquals(OPENING_FAILURES, breakers.breaker(VENDOR, MODEL).getMetrics().getNumberOfSuccessfulCalls());
    }

    /**
     * 两条口径的差异点之二：内容拒绝与参数错两条口径都取否，该模型不被推到打开状态；
     * 模型不存在同样按成功记，即使它落在「不可重试」基类下。
     */
    @Test
    void failuresUnrelatedToVendorHealthShouldNotCountAsUnhealthy() {
        ModelErrorClassifier classifier = new ModelErrorClassifier();
        Throwable contentFiltered = new ContentFilteredException("DataInspectionFailed");
        Throwable invalidRequest = new InvalidRequestException("400", new IllegalStateException("参数不接受"));
        Throwable modelNotFound = new ModelNotFoundException("404");

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, classifier.classify(contentFiltered));
        assertFalse(classifier.canFallback(contentFiltered), "内容拒绝不可降级");
        assertFalse(classifier.isVendorUnhealthy(contentFiltered), "内容拒绝不指向厂商侧健康");
        assertFalse(classifier.canFallback(invalidRequest), "参数错不可降级");
        assertFalse(classifier.isVendorUnhealthy(invalidRequest), "参数错不指向厂商侧健康");
        assertFalse(classifier.isVendorUnhealthy(modelNotFound), "模型不存在不指向厂商侧健康");

        for (int i = 0; i < OPENING_FAILURES; i++) {
            breakers.onError(VENDOR, MODEL, contentFiltered);
        }

        assertEquals(CircuitBreaker.State.CLOSED, breakers.state(VENDOR, MODEL));
        assertEquals(0, breakers.breaker(VENDOR, MODEL).getMetrics().getNumberOfFailedCalls());
    }

    /**
     * 百毫秒级窗口的熔断器集合：熔断参数自建（与生产路径共用同一份映射），
     * 打开保持与恢复爬升都取用例尺度。
     */
    private ModelCircuitBreakers shortWindowBreakers(ModelErrorClassifier classifier) {
        CircuitBreakerConfig shortConfig = ModelCircuitBreakers.configFor(USE_CASE_ERROR_TRIGGER_RATIO,
                USE_CASE_ERROR_WINDOW_SECONDS, SHORT_WINDOW_MILLIS, classifier);
        return new ModelCircuitBreakers(shortConfig, SHORT_WINDOW_MILLIS);
    }

    /** 把该模型的熔断器推到半开：库只接受「打开 → 半开」这一步迁移 */
    private void openToHalfOpen() {
        CircuitBreaker breaker = breakers.breaker(VENDOR, MODEL);
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
    }

    /** 记若干次失败：同一类失败连记，用于把失败率推过阈值 */
    private void recordFailures(int count) {
        for (int i = 0; i < count; i++) {
            breakers.onError(VENDOR, MODEL, SERVER_ERROR);
        }
    }

    /**
     * 同一类失败连记十次后的熔断状态：计入失败率的类型会打开，按成功记的类型保持关闭。
     * 每次用独立的模型名，各类型的读数互不干扰。
     *
     * @param modelKey 模型名（每类失败一个）
     * @param cause    失败原因
     * @return 记录后的熔断状态
     */
    private CircuitBreaker.State stateAfterFailures(String modelKey, Throwable cause) {
        for (int i = 0; i < OPENING_FAILURES; i++) {
            breakers.onError(VENDOR, modelKey, cause);
        }
        return breakers.state(VENDOR, modelKey);
    }
}
