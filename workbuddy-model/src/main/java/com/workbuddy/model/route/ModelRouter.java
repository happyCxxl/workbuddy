package com.workbuddy.model.route;

import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.enums.model.RouteReason;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.catalog.ModelCatalogPort;
import com.workbuddy.model.config.ModelRouteProperties;
import com.workbuddy.model.config.ModelVendorProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 模型路由：按「容量占用 / 健康 / 延迟」三个信号为一次调用选出模型，并给出本次调用的路由原因。
 *
 * <p>先过硬闸门（启用、能力匹配、厂商已配密钥、熔断未打开、占用未超上限、恢复爬升段按比例放行），
 * 再对剩余候选按归一化两项打分排序：候选集自身均值归一化后，占用权重 1.0、延迟权重 0.5 做扣分，取最高分。
 * 占用率与延迟都取自模型级状态：一个模型声明多个能力时共用一份读数，限额留空则该模型不参与占用判断。
 *
 * <p>决策粒度是每次调用，不锁定上一次的模型；同一能力的多个候选按分数排成一条顺序，
 * 首选失守时由调用链按该顺序换下一个模型。全自动，没有人工优先级、权重与灰度。
 *
 * @author cxxl
 */
@Slf4j
@Component
public class ModelRouter {

    /** 放行判断的取样上界：放行比例按百分数比较 */
    private static final int ADMISSION_BOUND = 100;

    /** 分数比较的容差：两项都是归一化量，差异小于该值即按并列处理 */
    private static final double TIE_TOLERANCE = 1.0E-9D;

    /** 放行比例的满量值 */
    private static final double FULL_RATIO = 1.0D;

    /** 浮点比较容差：比例换算成百分数时的取整误差 */
    private static final double RATIO_EPSILON = 1.0E-9D;

    /** 补全厂商码的提示语 */
    private static final String VENDOR_HINT = "（配置键 knowledge.model.vendors）";

    /** 路由参数 */
    private final ModelRouteProperties routeProperties;

    /** 厂商配置：密钥是否已配由它判定 */
    private final ModelVendorProperties vendorProperties;

    /** 模型目录：候选集按能力取 */
    private final ModelCatalogPort modelCatalog;

    /** 路由状态：占用与延迟读数从它读 */
    private final RouteStateStore stateStore;

    /** 模型熔断器：健康闸门与恢复放行段从它读 */
    private final ModelCircuitBreakers breakers;

    /** 随机源：恢复放行与并列抽样 */
    private final RandomSource randomSource;

    /**
     * 装配入口：随机源取默认实现（线程本地随机数）。
     *
     * @param routeProperties  路由参数
     * @param vendorProperties 厂商配置
     * @param modelCatalog     模型目录
     * @param stateStore       路由状态
     * @param breakers         模型熔断器
     */
    @Autowired
    public ModelRouter(ModelRouteProperties routeProperties, ModelVendorProperties vendorProperties,
            ModelCatalogPort modelCatalog, RouteStateStore stateStore, ModelCircuitBreakers breakers) {
        this(routeProperties, vendorProperties, modelCatalog, stateStore, breakers, RandomSource.DEFAULT);
    }

    /**
     * 指定随机源的路由：供用例注入取值可预测的随机源。
     *
     * @param routeProperties  路由参数
     * @param vendorProperties 厂商配置
     * @param modelCatalog     模型目录
     * @param stateStore       路由状态
     * @param breakers         模型熔断器
     * @param randomSource     随机源
     */
    public ModelRouter(ModelRouteProperties routeProperties, ModelVendorProperties vendorProperties,
            ModelCatalogPort modelCatalog, RouteStateStore stateStore, ModelCircuitBreakers breakers,
            RandomSource randomSource) {
        this.routeProperties = routeProperties;
        this.vendorProperties = vendorProperties;
        this.modelCatalog = modelCatalog;
        this.stateStore = stateStore;
        this.breakers = breakers;
        this.randomSource = randomSource;
    }

    /**
     * 为一次调用选型：过一次闸门、打一次分、排一条顺序，并记下本次选中的模型。
     *
     * @param capability 能力
     * @return 本次调用的选型结果（首选 + 降级顺序）
     * @throws KnowledgeException 该能力没有启用候选，或候选全部被闸门剔除
     */
    public RoutePlan plan(ModelCapability capability) {
        List<ModelDescriptor> candidates = modelCatalog.listByCapability(capability);
        if (NullUtil.isNull(candidates) || candidates.isEmpty()) {
            throw noCandidate(capability, null);
        }
        List<Decision> decisions = new ArrayList<>();
        List<ModelDescriptor> admitted = new ArrayList<>();
        StringJoiner blocked = new StringJoiner(", ");
        for (ModelDescriptor candidate : candidates) {
            String blockedReason = blockedReason(candidate);
            if (NullUtil.isNotNull(blockedReason)) {
                log.debug("候选被闸门剔除, 能力=[{}], 厂商=[{}], 模型=[{}], 原因=[{}]", capability, candidate.getVendorCode(),
                        candidate.getModelKey(), blockedReason);
                blocked.add(candidate.getModelKey() + "（" + blockedReason + "）");
                continue;
            }
            Decision decision = decisionOf(candidate);
            decisions.add(decision);
            if (decision.admitted()) {
                admitted.add(candidate);
            }
        }
        if (admitted.isEmpty()) {
            throw noCandidate(capability, blocked.toString());
        }
        List<Decision> ordered = orderByScore(decisions, admitted);
        Decision selected = ordered.getFirst();
        Decision runnerUp = ordered.size() > 1 ? ordered.get(1) : null;
        stateStore.recordSelection(selected.vendorCode(), selected.modelKey());
        log.debug("本次选型, 能力=[{}], 厂商=[{}], 模型=[{}], 原因=[{}], 候选数=[{}]", capability, selected.vendorCode(),
                selected.modelKey(), reasonOf(selected, runnerUp, null), candidates.size());
        return new RoutePlan(ordered, null);
    }

    /**
     * 硬闸门：逐个条件剔除候选，返回空值即通过。熔断未放行的模型直接剔除，不放行任何探测流量；
     * 恢复爬升段的按比例放行由决策阶段给出。
     *
     * @param candidate 候选模型
     * @return 剔除原因；通过为 null
     */
    private String blockedReason(ModelDescriptor candidate) {
        if (!candidate.enabled()) {
            return "未启用";
        }
        if (NullUtil.isNull(candidate.getModelKey()) || candidate.getModelKey().isBlank()) {
            return "模型名为空";
        }
        if (NullUtil.isNull(candidate.getVendorCode()) || candidate.getVendorCode().isBlank()) {
            return "厂商码为空";
        }
        if (!vendorConfigured(candidate.getVendorCode())) {
            return "厂商未配置或密钥为空";
        }
        if (!healthGate(candidate.getVendorCode(), candidate.getModelKey())) {
            return "熔断未放行";
        }
        if (occupancy(candidate) >= 1.0D) {
            return "占用已达上限";
        }
        return null;
    }

    /**
     * 健康闸门：取一次熔断许可并当场归还 —— 选型阶段只判本次能否调用，许可由调用阶段取走，
     * 半开探测的名额不会被没被调用的候选用掉。取许可这一步同时把到期的打开状态推进到半开
     * （库按注入的时钟判定打开保持是否到期）。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     * @return 本次可调用为 true
     */
    private boolean healthGate(String vendorCode, String modelKey) {
        if (!breakers.tryAcquire(vendorCode, modelKey)) {
            return false;
        }
        breakers.release(vendorCode, modelKey);
        return true;
    }

    /**
     * 取候选的读数与本次的放行结论；读数与放行判断相邻取值，保证同一次决策看到同一份状态。
     * 半开探测不受比例限制（库已把半开放行条数限为一），恢复爬升段按当前比例抽样。
     */
    private Decision decisionOf(ModelDescriptor candidate) {
        String vendorCode = candidate.getVendorCode();
        String modelKey = candidate.getModelKey();
        RouteStateStore.Snapshot snapshot = stateStore.snapshot(vendorCode, modelKey);
        double admissionRatio = breakers.admissionRatio(vendorCode, modelKey);
        boolean admittedBySlowStart = breakers.inSlowStart(vendorCode, modelKey);
        boolean admitted = !admittedBySlowStart || isProbe(vendorCode, modelKey) || admittedByRatio(admissionRatio);
        return new Decision(candidate, occupancy(snapshot, candidate), snapshot.latencyEwmaMs(), admitted,
                admittedBySlowStart, admissionRatio, snapshot.latencySamples(), 0D, 0D, 0D);
    }

    /** 本次是否为半开探测：半开状态只放行一次探测，该次调用不再按比例抽样 */
    private boolean isProbe(String vendorCode, String modelKey) {
        return breakers.state(vendorCode, modelKey) == CircuitBreaker.State.HALF_OPEN;
    }

    /** 恢复爬升段的按比例放行：取样低于门槛即本次不进首选排序，其余候选接住 */
    private boolean admittedByRatio(double admissionRatio) {
        if (admissionRatio >= FULL_RATIO) {
            return true;
        }
        return randomSource.nextInt(ADMISSION_BOUND) < Math.round(admissionRatio * ADMISSION_BOUND + RATIO_EPSILON);
    }

    /**
     * 排序：先按分数从高到低，再按归一化占用、归一化延迟、模型名依次定序。
     * 公式里的两个量都按候选集自身均值归一化（占用率是 0 ~ 1 的比例量，延迟取秒），
     * 于是同一组 β 在不同规模下含义一致；默认 β 容量 1.0、延迟 0.5，占用越高扣分越重。
     * 放行候选排在前面（首个即首选），未放行的候选排在后面，只作降级顺序里的备选。
     *
     * @param decisions 过闸门后的候选决策
     * @param admitted  放行判断通过的候选（参与选型与均值计算）
     * @return 排序结果
     */
    private List<Decision> orderByScore(List<Decision> decisions, List<ModelDescriptor> admitted) {
        double meanLoad = meanOccupancy(admitted);
        double meanLatency = meanLatency(admitted);
        List<Decision> scanned = new ArrayList<>();
        for (Decision decision : decisions) {
            double relativeLoad = relative(decision.occupancy(), meanLoad);
            double relativeLatency = relative(latencySeconds(decision.latencyEwmaMs()), meanLatencySeconds(meanLatency));
            double score = -(routeProperties.getBetaCapacity() * relativeLoad
                    + routeProperties.getBetaLatency() * relativeLatency);
            scanned.add(decision.withScore(score, relativeLoad, relativeLatency));
        }
        List<Decision> selected = new ArrayList<>();
        for (Decision decision : scanned) {
            if (decision.admitted()) {
                selected.add(decision);
            }
        }
        selected.sort(decisionOrder());
        List<Decision> ordered = new ArrayList<>();
        Decision best = pickTied(selected);
        if (NullUtil.isNotNull(best)) {
            ordered.add(best);
        }
        for (Decision decision : selected) {
            if (decision != best) {
                ordered.add(decision);
            }
        }
        for (Decision decision : scanned) {
            if (!decision.admitted()) {
                ordered.add(decision);
            }
        }
        return ordered;
    }

    /**
     * 并列抽样：分数并列的放行候选超过一个时随机打乱后取前两个再比，避免全部涌向同一个候选。
     * 打乱的范围由随机源决定，并列候选越多越分散；两个并列候选之间按归一化占用、归一化延迟、
     * 模型名依次定序，同一份状态与同一个抽样结论下结果稳定。并列者不足两个即直接取该候选。
     *
     * @param selected 按分数从高到低排好的放行候选
     * @return 首选模型对应的决策；入参为空返回 null
     */
    private Decision pickTied(List<Decision> selected) {
        if (selected.isEmpty()) {
            return null;
        }
        List<Decision> tied = new ArrayList<>();
        double bestScore = selected.getFirst().score();
        for (Decision decision : selected) {
            if (bestScore - decision.score() > TIE_TOLERANCE) {
                break;
            }
            tied.add(decision);
        }
        if (tied.size() < 2) {
            return tied.getFirst();
        }
        shuffle(tied);
        Decision first = tied.getFirst();
        Decision second = tied.get(1);
        return decisionOrder().compare(first, second) <= 0 ? first : second;
    }

    /** 并列者随机打乱：抽样范围由随机源决定 */
    private void shuffle(List<Decision> tied) {
        for (int i = tied.size() - 1; i > 0; i--) {
            int picked = randomSource.nextInt(i + 1);
            if (picked != i) {
                Decision swap = tied.get(i);
                tied.set(i, tied.get(picked));
                tied.set(picked, swap);
            }
        }
    }

    /** 决策排序：分数从高到低，再按归一化占用、归一化延迟、模型名依次定序 */
    private Comparator<Decision> decisionOrder() {
        return Comparator.comparingDouble(Decision::score).reversed()
                .thenComparingDouble(Decision::relativeLoad)
                .thenComparingDouble(Decision::relativeLatency)
                .thenComparing(decision -> NullUtil.isNull(decision.modelKey()) ? "" : decision.modelKey());
    }

    /** 候选集占用率均值 */
    private double meanOccupancy(List<ModelDescriptor> admitted) {
        if (admitted.isEmpty()) {
            return 0D;
        }
        double sum = 0D;
        for (ModelDescriptor candidate : admitted) {
            sum += occupancy(candidate);
        }
        return sum / admitted.size();
    }

    /** 候选集延迟均值：没有成功样本的候选按零计入，单位毫秒 */
    private double meanLatency(List<ModelDescriptor> admitted) {
        if (admitted.isEmpty()) {
            return 0D;
        }
        double sum = 0D;
        for (ModelDescriptor candidate : admitted) {
            sum += stateStore.snapshot(candidate.getVendorCode(), candidate.getModelKey()).latencyEwmaMs();
        }
        return sum / admitted.size();
    }

    /** 占用率：取该项模型的读数与限额相比 */
    private double occupancy(ModelDescriptor candidate) {
        return occupancy(stateStore.snapshot(candidate.getVendorCode(), candidate.getModelKey()), candidate);
    }

    /**
     * 占用率：每分钟窗口内的计数 ÷ 该类限额，取请求数与 token 数两项中的较大者。
     * 限额留空的模型不参与占用判断，两项都留空即占用率为 0。
     */
    private double occupancy(RouteStateStore.Snapshot snapshot, ModelDescriptor candidate) {
        double rpmRatio = ratio(snapshot.rpmCount(), candidate.getRpmLimit());
        double tpmRatio = ratio(snapshot.tpmTokens(), candidate.getTpmLimit());
        return Math.max(rpmRatio, tpmRatio);
    }

    /** 单项占用率：限额留空或非正数按不参与处理（返回 0） */
    private double ratio(long used, Integer limit) {
        if (NullUtil.isNull(limit) || limit <= 0) {
            return 0D;
        }
        return (double) used / limit;
    }

    /**
     * 归一化：与候选集均值的偏离程度，除以均值与 1 中的较大者（+1.0 = 高于均值一倍）。
     * 两项的量纲都与 1 可比：占用率是 0 ~ 1 的比例，延迟按秒计。
     */
    private double relative(double value, double mean) {
        double normalized = (value - mean) / Math.max(1D, mean);
        return normalized == 0D ? 0D : normalized;
    }

    /** 延迟读数按秒参与打分：占用率是比例量，延迟取秒后两项量纲可比（公式里两者共用一个 1 作为下限） */
    private double latencySeconds(double latencyEwmaMs) {
        return latencyEwmaMs / ModelRouteProperties.MILLIS_PER_SECOND;
    }

    /** 延迟均值同样按秒 */
    private double meanLatencySeconds(double meanLatencyMs) {
        return meanLatencyMs / ModelRouteProperties.MILLIS_PER_SECOND;
    }

    /**
     * 路由原因（判定优先级从高到低）：
     * ① {@code FALLBACK_FROM} —— 本次由上一个模型降级而来，原因最强，覆盖其它判断；
     * ② {@code SLOW_START} —— 本次落到半开探测或恢复爬升段的放行上，该模型刚出熔断打开，读数还不足以作其它判定；
     * ③ {@code CAPACITY_HIGH} —— 该模型占用率达到 capacity-trigger-ratio，或容量信号驱动了这次选型
     * （相对次选，选中者在容量项上的优势大于延迟项）且其占用率低于候选集均值；
     * ④ {@code LATENCY_HIGH} —— 延迟信号驱动了这次选型，且该模型延迟低于候选集均值；
     * ⑤ {@code OK} —— 两个信号都没有区分度（并列或都未越线）。
     *
     * <p>两个「驱动」判定都按打分里用到的归一化量比较：选中者相对次选在容量项上的优势与在延迟项上的优势，
     * 哪一项更大就以哪一项为本次原因；两项优势都为零时是并列，按 {@code OK} 记。
     *
     * <p>健康面（{@code UNHEALTHY}）在选型路径上不会出现：熔断打开的模型被硬闸门剔除，不会成为本次调用的对象。
     * 该取值用在「失败当次」的明细上，由调用链在本次调用失败且可降级时写入。
     *
     * @param decision     选中者的决策
     * @param runnerUp     次选决策；没有次选传空
     * @param fallbackFrom 上一个模型名；首选为空
     * @return 本次调用的路由原因
     */
    public RouteReason reasonOf(Decision decision, Decision runnerUp, String fallbackFrom) {
        if (NullUtil.isNotNull(fallbackFrom)) {
            return RouteReason.FALLBACK_FROM;
        }
        if (decision.admittedBySlowStart()) {
            return RouteReason.SLOW_START;
        }
        if (decision.occupancy() >= routeProperties.getCapacityTriggerRatio()) {
            return RouteReason.CAPACITY_HIGH;
        }
        double capacityGain = gainOf(decision, runnerUp, true);
        double latencyGain = gainOf(decision, runnerUp, false);
        if (capacityGain > latencyGain && decision.relativeLoad() <= 0D) {
            return RouteReason.CAPACITY_HIGH;
        }
        if (latencyGain > capacityGain && decision.relativeLatency() <= 0D) {
            return RouteReason.LATENCY_HIGH;
        }
        return RouteReason.OK;
    }

    /**
     * 路由原因（无次选信息的重载）：只按选中者自身的读数判定。
     *
     * @param decision     选中者的决策
     * @param fallbackFrom 上一个模型名；首选为空
     * @return 本次调用的路由原因
     */
    public RouteReason reasonOf(Decision decision, String fallbackFrom) {
        return reasonOf(decision, null, fallbackFrom);
    }

    /** 选中者相对次选在某一项上的优势：次选为空即视为无人可比，返回零（分数本身已含"越线"与否的信息） */
    private double gainOf(Decision decision, Decision runnerUp, boolean capacity) {
        if (NullUtil.isNull(runnerUp)) {
            return 0D;
        }
        if (capacity) {
            return runnerUp.relativeLoad() - decision.relativeLoad();
        }
        return runnerUp.relativeLatency() - decision.relativeLatency();
    }

    /** 厂商是否已配密钥：配置里没有该厂商或密钥为空都按未配处理，其模型不进候选 */
    private boolean vendorConfigured(String vendorCode) {
        Map<String, ModelVendorProperties.Vendor> vendors = vendorProperties.getVendors();
        ModelVendorProperties.Vendor vendor = NullUtil.isNull(vendors) ? null : vendors.get(vendorCode);
        return NullUtil.isNotNull(vendor) && NullUtil.isNotNull(vendor.getApiKey()) && !vendor.getApiKey().isBlank();
    }

    /** 没有可用候选：消息带能力、已剔除的候选与原因，以及已配置的厂商清单 */
    private KnowledgeException noCandidate(ModelCapability capability, String blocked) {
        Map<String, ModelVendorProperties.Vendor> vendors = vendorProperties.getVendors();
        Set<String> codes = NullUtil.isNull(vendors) ? Set.of() : vendors.keySet();
        StringJoiner detail = new StringJoiner("，");
        detail.add("能力：" + capability);
        detail.add("已配置厂商：" + codes + VENDOR_HINT);
        if (NullUtil.isNotNull(blocked) && !blocked.isBlank()) {
            detail.add("被剔除的候选：" + blocked);
        }
        return new KnowledgeException(ErrorCode.MODEL_VENDOR_UNKNOWN, detail.toString());
    }

    /**
     * 本次调用的选型结果：首选与降级顺序，以及本次是否由别的模型降级而来。
     */
    public static final class RoutePlan {

        /** 按分数排好的候选顺序：首个是本次首选，其余是首选失守后的备选 */
        private final List<Decision> ordered;

        /** 降级来源模型名；本次不是降级则为 null */
        private final String fallbackFrom;

        /**
         * 构造选型结果。
         *
         * @param ordered      候选顺序
         * @param fallbackFrom 降级来源模型名（可空）
         */
        public RoutePlan(List<Decision> ordered, String fallbackFrom) {
            this.ordered = List.copyOf(ordered);
            this.fallbackFrom = fallbackFrom;
        }

        /** 按分数排好的候选顺序：首个是本次首选 */
        public List<Decision> ordered() {
            return ordered;
        }

        /** 本次首选 */
        public Decision selected() {
            return ordered.isEmpty() ? null : ordered.getFirst();
        }

        /** 降级来源模型名；本次不是降级则为 null */
        public String fallbackFrom() {
            return fallbackFrom;
        }

        /**
         * 换一个模型作为本次调用对象：降级来源取自入参，候选顺序不变。
         *
         * @param source 降级来源模型名
         * @return 指向同一候选顺序的选型结果
         */
        public RoutePlan from(String source) {
            return new RoutePlan(ordered, source);
        }
    }

    /**
     * 一个候选的决策：目录项、读数与本次的放行结论。
     *
     * @param descriptor          候选目录项
     * @param occupancy           占用率（窗口计数 ÷ 限额，限额留空为零）
     * @param latencyEwmaMs       延迟 EWMA（毫秒；打分时按秒参与归一化）
     * @param admitted            本次是否进首选排序（半开探测与不在恢复爬升段的候选一律进）
     * @param admittedBySlowStart 本次落在半开探测或恢复爬升段
     * @param slowStartRatio      读取状态时的爬升比例（诊断用）
     * @param latencySamples      窗口内的延迟样本条数
     * @param score               打分结果（放行候选中有意义）
     * @param relativeLoad        归一化占用
     * @param relativeLatency     归一化延迟（同按秒计）
     */
    public record Decision(ModelDescriptor descriptor, double occupancy, double latencyEwmaMs, boolean admitted,
            boolean admittedBySlowStart, double slowStartRatio, int latencySamples, double score,
            double relativeLoad, double relativeLatency) {

        /** 厂商码 */
        public String vendorCode() {
            return descriptor.getVendorCode();
        }

        /** 模型名 */
        public String modelKey() {
            return descriptor.getModelKey();
        }

        /** 补齐打分结果 */
        Decision withScore(double scoreValue, double loadValue, double latencyValue) {
            return new Decision(descriptor, occupancy, latencyEwmaMs, admitted, admittedBySlowStart, slowStartRatio,
                    latencySamples, scoreValue, loadValue, latencyValue);
        }
    }
}
