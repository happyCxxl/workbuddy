package com.workbuddy.model.support;

import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.model.adapter.ModelAdapter;
import com.workbuddy.model.calllog.ModelCallLogRecorder;
import com.workbuddy.model.config.ModelRouteProperties;
import com.workbuddy.model.config.ModelVendorProperties;
import com.workbuddy.model.invoke.ModelErrorClassifier;
import com.workbuddy.model.invoke.ModelInvoker;
import com.workbuddy.model.listener.ModelCallTraceHolder;
import com.workbuddy.model.route.ModelCircuitBreakers;
import com.workbuddy.model.route.ModelRouter;
import com.workbuddy.model.route.RouteStateStore;
import com.workbuddy.model.validate.OutputSchemaValidator;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 路由用例夹具（测试用）：一份假目录 + 一份厂商配置 + 一个可推进时间的路由状态与熔断器，
 * 按生产装配顺序接成路由与调用器，供三信号打分、硬闸门、熔断与恢复、降级链各用例直接驱动。
 *
 * <p>路由侧的时间源从 {@link #START_MILLIS} 起算，用例按需推进（占用窗口、延迟窗口与生效区间都读它）；
 * 熔断器侧的打开保持与半开到期按系统时钟，三类前提由库的公开状态迁移方法构造，见
 * {@link #withOpenBreaker(String, String)} 与 {@link #withHalfOpenBreaker(String, String)}；
 * 时间行为用例经 {@link #withBreakers(ModelCircuitBreakers)} 换成自建窗口的那一份。
 * 随机源默认取固定值，用例经 {@link #withRandomSource(TestRandomSource)} 换用序列取值。
 *
 * @author cxxl
 */
public final class RouteFixture {

    /** 用例时间起点（毫秒）：取一个远离零的时刻，让「未记录」与「已记录」在读数上区分得开 */
    public static final long START_MILLIS = 1_700_000_000_000L;

    /** 延迟窗口（秒）：用例尺度 */
    private static final int LATENCY_WINDOW_SECONDS = 60;

    /** 输入每百万 token 单价 */
    private static final BigDecimal PRICE_IN = new BigDecimal("2.000000");

    /** 输出每百万 token 单价 */
    private static final BigDecimal PRICE_OUT = new BigDecimal("8.000000");

    /** 假目录：按能力登记目录项 */
    private final TestRouteCatalog catalog = new TestRouteCatalog();

    /** 厂商配置：登记即视为已配密钥 */
    private final ModelVendorProperties vendorProperties = new ModelVendorProperties();

    /** 路由参数：比例项取生产默认值，窗口项取用例尺度 */
    private final ModelRouteProperties routeProperties = new ModelRouteProperties();

    /** 可推进的时间源 */
    private final TestTimeSource timeSource = new TestTimeSource(START_MILLIS);

    /** 错误分类：熔断失败判定与调用链降级判定共用一份 */
    private final ModelErrorClassifier classifier = new ModelErrorClassifier();

    /** 模型熔断器：按生产参数装配（爬升窗口取 `slow-start-seconds`），时间行为用例经
     * {@link #withBreakers(ModelCircuitBreakers)} 换成自建的一份 */
    private ModelCircuitBreakers breakers = new ModelCircuitBreakers(routeProperties, classifier);

    /** 路由状态 */
    private final RouteStateStore stateStore = new RouteStateStore(routeProperties, timeSource);

    /** 预测用的随机源 */
    private TestRandomSource randomSource = TestRandomSource.fixed(0);

    /** 调用明细端口，在 {@link #invoker(ModelAdapter)} 时装配 */
    private InMemoryModelCallLogPort callLogPort;

    public RouteFixture() {
        routeProperties.setLatencyWindowSeconds(LATENCY_WINDOW_SECONDS);
    }

    /**
     * 换一份随机源：随机源在装配路由与调用器时注入，本方法只换字段，状态与熔断器都是同一份。
     *
     * @param source 随机源
     * @return 本对象（链式设置）
     */
    public RouteFixture withRandomSource(TestRandomSource source) {
        this.randomSource = source;
        return this;
    }

    /**
     * 换一份熔断器：时间行为用例自建百毫秒级窗口的那份后注入，本方法只换字段。
     * 路由与调用器都按本字段装配，需在 {@link #router()} 与 {@link #invoker(ModelAdapter)} 之前调用。
     *
     * @param custom 自建的熔断器
     * @return 本对象（链式设置）
     */
    public RouteFixture withBreakers(ModelCircuitBreakers custom) {
        this.breakers = custom;
        return this;
    }

    /** 假目录 */
    public TestRouteCatalog catalog() {
        return catalog;
    }

    /** 路由参数 */
    public ModelRouteProperties properties() {
        return routeProperties;
    }

    /** 可推进的时间源 */
    public TestTimeSource time() {
        return timeSource;
    }

    /** 路由状态 */
    public RouteStateStore stateStore() {
        return stateStore;
    }

    /** 模型熔断器 */
    public ModelCircuitBreakers breakers() {
        return breakers;
    }

    /**
     * 把某个模型的熔断器推到打开：走库的公开状态迁移方法，前提由用例直接构造。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     * @return 本对象（链式设置）
     */
    public RouteFixture withOpenBreaker(String vendorCode, String modelKey) {
        breakers.breaker(vendorCode, modelKey).transitionToOpenState();
        return this;
    }

    /**
     * 把某个模型的熔断器推到半开：库只接受「打开 → 半开」这一步迁移，本方法内含打开那一步。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     * @return 本对象（链式设置）
     */
    public RouteFixture withHalfOpenBreaker(String vendorCode, String modelKey) {
        breakers.breaker(vendorCode, modelKey).transitionToOpenState();
        breakers.breaker(vendorCode, modelKey).transitionToHalfOpenState();
        return this;
    }

    /**
     * 把某个模型的熔断器退回关闭并清零计数：恢复放行爬升段的起点与计数都重置。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     * @return 本对象（链式设置）
     */
    public RouteFixture withClosedBreaker(String vendorCode, String modelKey) {
        breakers.breaker(vendorCode, modelKey).reset();
        return this;
    }

    /** 用当前随机源装配路由 */
    public ModelRouter router() {
        return new ModelRouter(routeProperties, vendorProperties, catalog, stateStore, breakers, randomSource,
                timeSource);
    }

    /** 调用明细端口；未装配调用器时为 null */
    public InMemoryModelCallLogPort callLog() {
        return callLogPort;
    }

    /** 厂商配置表 */
    public Map<String, ModelVendorProperties.Vendor> vendors() {
        Map<String, ModelVendorProperties.Vendor> vendors = vendorProperties.getVendors();
        if (vendors == null) {
            vendors = new LinkedHashMap<>();
            vendorProperties.setVendors(vendors);
        }
        return vendors;
    }

    /**
     * 登记一个厂商：配置里存在且密钥非空即通过闸门的厂商面。
     *
     * @param vendorCode 厂商码
     * @return 本对象（链式登记）
     */
    public RouteFixture withVendor(String vendorCode) {
        ModelVendorProperties.Vendor vendor = new ModelVendorProperties.Vendor();
        vendor.setApiKey("sk-route");
        vendor.setTimeoutMs(10000);
        vendor.setMaxRetries(0);
        vendors().put(vendorCode, vendor);
        return this;
    }

    /**
     * 登记一个启用的对话模型：带价格快照与每分钟请求限额。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     * @param rpmLimit   每分钟请求限额（可空 = 该模型不参与占用判断）
     * @return 本对象（链式登记）
     */
    public RouteFixture withChatModel(String vendorCode, String modelKey, Integer rpmLimit) {
        ModelDescriptor descriptor = new ModelDescriptor();
        descriptor.setVendorCode(vendorCode);
        descriptor.setModelKey(modelKey);
        descriptor.setCapabilities(Set.of(ModelCapability.CHAT));
        descriptor.setStatus("ENABLED");
        descriptor.setRpmLimit(rpmLimit);
        descriptor.setPriceInPerM(PRICE_IN);
        descriptor.setPriceOutPerM(PRICE_OUT);
        descriptor.setCurrency("CNY");
        catalog.register(descriptor);
        return this;
    }

    /**
     * 登记一个启用的视觉模型：能力按形参给出（OCR / LAYOUT / TABLE_STRUCTURE），不带价格快照。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     * @param capability 该模型承载的视觉能力
     * @return 本对象（链式登记）
     */
    public RouteFixture withVisionModel(String vendorCode, String modelKey, ModelCapability capability) {
        ModelDescriptor descriptor = new ModelDescriptor();
        descriptor.setVendorCode(vendorCode);
        descriptor.setModelKey(modelKey);
        descriptor.setCapabilities(Set.of(capability));
        descriptor.setStatus("ENABLED");
        catalog.register(descriptor);
        return this;
    }

    /**
     * 装配按能力选型的调用器：明细端口为内存实现，目录、路由、状态与熔断器共用本夹具这几份。
     *
     * @param adapter 适配器
     * @return 调用器
     */
    public ModelInvoker invoker(ModelAdapter adapter) {
        callLogPort = new InMemoryModelCallLogPort();
        return new ModelInvoker(adapter, new OutputSchemaValidator(), classifier, new ModelCallTraceHolder(),
                new ModelCallLogRecorder(callLogPort, catalog), router(), stateStore, breakers);
    }
}
