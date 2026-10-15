package com.workbuddy.model.invoke;

import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.ChatMessage;
import com.workbuddy.common.domain.model.ChatRequest;
import com.workbuddy.common.domain.model.GenerationResult;
import com.workbuddy.common.domain.model.ImageRegion;
import com.workbuddy.common.domain.model.ModelUsage;
import com.workbuddy.common.domain.model.OutputSpec;
import com.workbuddy.common.domain.model.VisionRequest;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.enums.model.RouteReason;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.adapter.ModelAdapter;
import com.workbuddy.model.calllog.ModelCallLogRecorder;
import com.workbuddy.model.listener.ModelCallTrace;
import com.workbuddy.model.listener.ModelCallTraceHolder;
import com.workbuddy.model.route.ModelCircuitBreakers;
import com.workbuddy.model.route.ModelRouter;
import com.workbuddy.model.route.RouteStateStore;
import com.workbuddy.model.validate.OutputSchemaValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * 模型调用器：取适配器执行一次调用，对结果做结构校验后返回，并在调用结束后落一条调用明细。
 * 同一实例内的重试与退避由底层实例承担，本类不另加重试；调用失败的异常在本类映射成错误码，
 * 「是否可降级」由 {@link ModelErrorClassifier} 当场判定并经 {@link #canFallback(Throwable)} 暴露。
 * 调用上下文经 {@link ModelCallTraceHolder} 交给适配器，随请求选项的属性表到达用量监听器。
 * 对话与向量两个模态共用同一套上下文绑定、异常映射与明细落库，错误码口径一致。
 *
 * <p>两条入口：厂商码由形参给出的那条按请求体里的模型名直接调用；按能力自动选型的那条先经
 * {@link ModelRouter} 拿到候选顺序，再按顺序换模型，换模那次把原模型名写进 `fallback_from`、
 * 把本次的路由原因写进 `route_reason`，两者随明细落库。每次尝试都向 {@link RouteStateStore}
 * 登记在途与耗时、向 {@link ModelCircuitBreakers} 登记成败（失败是否计入熔断由错误分类判定）。
 * 真实调用结束后另按本次消耗的请求数与 token 合计写一次占用，容量信号据此反映真实占用；
 * 厂商未回用量时按请求文本长度粗估 token 补上，该估值只进占用窗口，不落明细、不参与成本折算。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelInvoker {

    /** 向量模态没有输出约束，调用前检查为空 */
    private static final Runnable NO_PRE_CHECK = () -> {
    };

    /** token 粗估口径：请求文本的字符数 ÷ 该值（只补占用读数，不落明细也不算成本） */
    private static final int CHARS_PER_TOKEN = 3;

    /** 请求文本为空时的 token 粗估值 */
    private static final long EMPTY_TEXT_TOKENS = 1L;

    private final ModelAdapter modelAdapter;

    private final OutputSchemaValidator outputSchemaValidator;

    private final ModelErrorClassifier modelErrorClassifier;

    private final ModelCallTraceHolder modelCallTraceHolder;

    private final ModelCallLogRecorder modelCallLogRecorder;

    private final ModelRouter modelRouter;

    private final RouteStateStore routeStateStore;

    private final ModelCircuitBreakers modelCircuitBreakers;

    /**
     * 对话调用：厂商码由形参给出，模型名在请求体；调用上下文在本方法内创建，调用结束即丢弃。
     *
     * @param vendorCode 厂商码
     * @param request    对话入参（模型/消息/输出约束/超时）
     * @return 出参（原始文本 + 校验后的对象树 + 用量 + 模型标识）
     */
    public GenerationResult chat(String vendorCode, ChatRequest request) {
        return chat(vendorCode, request, new ModelCallTrace());
    }

    /**
     * 对话调用：调用上下文由形参给出，调用结束后从入参读到尝试次数、用量、延迟与错误码。
     *
     * @param vendorCode 厂商码
     * @param request    对话入参（模型/消息/输出约束/超时）
     * @param trace      调用上下文；入参为空即改用本方法内新建的上下文
     * @return 出参（原始文本 + 校验后的对象树 + 用量 + 模型标识）
     */
    public GenerationResult chat(String vendorCode, ChatRequest request, ModelCallTrace trace) {
        ModelCallTrace callTrace = traceOf(trace);
        return invoke(vendorCode, callTrace, ModelCapability.CHAT, () -> modelKey(request),
                () -> outputSchemaValidator.checkBeforeCall(output(request)),
                () -> chatCall(vendorCode, request, callTrace));
    }

    /**
     * 对话调用（按能力自动选型）：候选顺序由路由给出，首选失守且可降级时换下一个候选。
     *
     * @param capability 能力
     * @param request    对话入参（模型名由选型结果覆盖）
     * @return 出参（原始文本 + 校验后的对象树 + 用量 + 模型标识）
     */
    public GenerationResult chatByCapability(ModelCapability capability, ChatRequest request) {
        if (NullUtil.isNull(request)) {
            throw new KnowledgeException(ErrorCode.MODEL_BAD_REQUEST, "对话入参不能为空");
        }
        return runByCapability(capability, new ModelCall<>() {

            @Override
            public GenerationResult call(String vendorCode, String modelKey, ModelCallTrace trace) {
                request.setModelKey(modelKey);
                return chatCall(vendorCode, request, trace);
            }

            @Override
            public Runnable beforeCall() {
                return () -> outputSchemaValidator.checkBeforeCall(output(request));
            }

            @Override
            public String promptText() {
                return ModelInvoker.this.chatPromptText(request);
            }
        });
    }

    /**
     * 向量编码调用：厂商码由形参给出，模型名在请求体；错误码映射与可降级判定与对话调用同一套。
     * 向量数据缺失按「输出结构校验失败」（40469）拒绝，避免该失败退化成服务端错误。
     *
     * @param vendorCode 厂商码
     * @param request    向量入参（模型/文本/超时/批大小/requestId）
     * @param trace      调用上下文；入参为空即改用本方法内新建的上下文
     * @return 向量列表 + 维度 + 模型回显 + requestId
     */
    public EmbeddingResult embed(String vendorCode, EmbeddingRequest request, ModelCallTrace trace) {
        ModelCallTrace callTrace = traceOf(trace);
        return invoke(vendorCode, callTrace, ModelCapability.EMBEDDING, () -> modelKey(request), NO_PRE_CHECK,
                () -> embedCall(vendorCode, request, callTrace));
    }

    /**
     * 向量编码调用（按能力自动选型）：候选顺序、降级口径与明细落库口径与对话调用一致。
     *
     * @param capability 能力
     * @param request    向量入参（模型名由选型结果覆盖）
     * @return 向量列表 + 维度 + 模型回显 + requestId
     */
    public EmbeddingResult embedByCapability(ModelCapability capability, EmbeddingRequest request) {
        if (NullUtil.isNull(request)) {
            throw new KnowledgeException(ErrorCode.MODEL_BAD_REQUEST, "向量入参不能为空");
        }
        return runByCapability(capability, new ModelCall<>() {

            @Override
            public EmbeddingResult call(String vendorCode, String modelKey, ModelCallTrace trace) {
                request.setModel(modelKey);
                return embedCall(vendorCode, request, trace);
            }

            @Override
            public Runnable beforeCall() {
                return NO_PRE_CHECK;
            }

            @Override
            public String promptText() {
                return ModelInvoker.this.embedPromptText(request);
            }
        });
    }

    /**
     * 视觉调用：厂商码由形参给出，模型名在请求体；错误码映射与前两个模态同一套。
     * 出参结构校验按请求里的输出约束做，与对话模态同一口径（TEXT 不校验、JSON 类回填对象树）。
     *
     * <p>该入口不带能力信息，落明细时 {@code capability} 列固定记为 {@code TABLE_STRUCTURE}
     * （当前只服务测试与将来的按厂商直调场景）；生产链路一律走 {@code visionByCapability(...)}，
     * 能力列取真实能力。
     *
     * @param vendorCode 厂商码
     * @param request    视觉入参（模型/图像/区域/提示词/输出约束/超时）
     * @param trace      调用上下文；入参为空即改用本方法内新建的上下文
     * @return 出参（原始文本 + 校验后的对象树 + 用量 + 模型标识）
     */
    public GenerationResult vision(String vendorCode, VisionRequest request, ModelCallTrace trace) {
        ModelCallTrace callTrace = traceOf(trace);
        return invoke(vendorCode, callTrace, ModelCapability.TABLE_STRUCTURE, () -> modelKey(request),
                () -> outputSchemaValidator.checkBeforeCall(output(request)),
                () -> visionCall(vendorCode, request, callTrace, ModelCapability.TABLE_STRUCTURE));
    }

    /**
     * 视觉调用（按能力自动选型）：候选顺序由路由给出，厂商码取本次选中的候选，
     * 首选失守且可降级时换下一个候选；明细里的能力列按形参给出的能力落。
     *
     * @param capability 能力（OCR / LAYOUT / TABLE_STRUCTURE）
     * @param request    视觉入参（模型名由选型结果覆盖）
     * @return 出参（原始文本 + 校验后的对象树 + 用量 + 模型标识）
     */
    public GenerationResult visionByCapability(ModelCapability capability, VisionRequest request) {
        if (NullUtil.isNull(request)) {
            throw new KnowledgeException(ErrorCode.MODEL_BAD_REQUEST, "视觉入参不能为空");
        }
        return runByCapability(capability, new ModelCall<>() {

            @Override
            public GenerationResult call(String vendorCode, String modelKey, ModelCallTrace trace) {
                request.setModelKey(modelKey);
                return visionCall(vendorCode, request, trace, capability);
            }

            @Override
            public Runnable beforeCall() {
                return () -> outputSchemaValidator.checkBeforeCall(output(request));
            }

            @Override
            public String promptText() {
                return ModelInvoker.this.visionPromptText(request);
            }
        });
    }

    /**
     * 一次调用的公共外壳：形参校验 → 绑定调用上下文 → 调用前检查 → 执行本次调用 → 失败映射错误码并回写上下文 → 解绑。
     * 三个模态共用本方法，失败口径与明细落库口径保持一致。
     *
     * <p>明细只记真实发出的调用：形参校验与调用前检查拦下的失败没有发出请求，不落行；
     * 请求发出后的失败（含超时、限流、输出结构校验失败）同样落行，状态与错误码取自本次映射结果。
     *
     * @param vendorCode 厂商码
     * @param callTrace  调用上下文（非空）
     * @param capability 能力（本次调用按该能力计量）
     * @param modelKeyOf 模型名来源（取请求体里的模型名，出参为 null 按参数问题拒绝）
     * @param beforeCall 调用前检查（请求不发出的拒绝点）
     * @param call       本次调用（适配器调用、出参校验与成功明细落库）
     * @return 本次调用的出参
     */
    private <T> T invoke(String vendorCode, ModelCallTrace callTrace, ModelCapability capability,
            Supplier<String> modelKeyOf, Runnable beforeCall, Supplier<T> call) {
        boolean issued = false;
        String modelKey = null;
        try {
            modelKey = modelKeyOf.get();
            checkArguments(vendorCode, modelKey);
            modelCallTraceHolder.bind(callTrace);
            beforeCall.run();
            issued = true;
            return call.get();
        } catch (KnowledgeException e) {
            callTrace.setErrorCode(e.getErrorCode());
            markUnhealthy(callTrace, e);
            recordFailure(issued, vendorCode, modelKey, capability, callTrace, e.getErrorCode());
            throw e;
        } catch (RuntimeException e) {
            KnowledgeException error = toKnowledgeException(vendorCode, modelKey, e);
            callTrace.setErrorCode(error.getErrorCode());
            markUnhealthy(callTrace, error);
            recordFailure(issued, vendorCode, modelKey, capability, callTrace, error.getErrorCode());
            throw error;
        } finally {
            modelCallTraceHolder.clear();
        }
    }

    /**
     * 按能力选型后再调用：候选顺序由路由给出，按顺序逐次尝试，失败且可降级时换下一个候选。
     * 每次尝试先取一次熔断许可（拿不到即跳过该候选，不计为调用失败），各建一份调用上下文、各落一行明细，
     * 并各向路由状态与熔断器登记一次结果与耗时；真实调用结束后把本次消耗的请求数与 token 合计写进
     * 路由状态，容量信号据此反映真实占用。
     *
     * @param capability 能力
     * @param modelCall  本次调用的入参形状（调用体、模型名来源、调用前检查与请求文本）
     * @return 本次调用的出参
     */
    private <T> T runByCapability(ModelCapability capability, ModelCall<T> modelCall) {
        ModelRouter.RoutePlan plan = modelRouter.plan(capability);
        List<ModelRouter.Decision> ordered = plan.ordered();
        String fallbackFrom = plan.fallbackFrom();
        for (int i = 0; i < ordered.size(); i++) {
            ModelRouter.Decision decision = ordered.get(i);
            ModelRouter.Decision runnerUp = i + 1 < ordered.size() ? ordered.get(i + 1) : null;
            String vendorCode = decision.vendorCode();
            String modelKey = decision.modelKey();
            if (!modelCircuitBreakers.tryAcquire(vendorCode, modelKey)) {
                log.debug("候选未取得熔断许可, 能力=[{}], 厂商=[{}], 模型=[{}]", capability, vendorCode, modelKey);
                continue;
            }
            ModelCallTrace trace = new ModelCallTrace();
            trace.setRouteReason(modelRouter.reasonOf(decision, runnerUp, fallbackFrom));
            trace.setFallbackFrom(fallbackFrom);
            routeStateStore.enter(vendorCode, modelKey);
            try {
                T result = invoke(vendorCode, trace, capability, () -> modelKey, modelCall.beforeCall(),
                        () -> modelCall.call(vendorCode, modelKey, trace));
                modelCircuitBreakers.onSuccess(vendorCode, modelKey);
                recordOccupancy(vendorCode, modelKey, trace, result, modelCall.promptText());
                return result;
            } catch (KnowledgeException e) {
                modelCircuitBreakers.onError(vendorCode, modelKey, e);
                recordOccupancy(vendorCode, modelKey, trace, null, modelCall.promptText());
                if (!canFallback(e)) {
                    throw e;
                }
                fallbackFrom = modelKey;
            } finally {
                routeStateStore.exit(vendorCode, modelKey);
            }
        }
        throw new KnowledgeException(ErrorCode.MODEL_VENDOR_UNKNOWN,
                "能力：" + capability + " 的候选全部被剔除，没有可调用的模型");
    }

    /**
     * 把一次真实调用的占用写进路由状态：一次请求与本次消耗的 token 合计都按本次记录，
     * 成功后另写本次耗时。未取得熔断许可而跳过的候选没有发出请求，不写占用。
     *
     * <p>token 合计取调用上下文里的用量，上下文没写上时回落到出参的用量；两者都没有即按请求文本长度粗估，
     * 该估值只进占用窗口，不落调用明细、不参与成本折算。明细里的用量仍按「未知即空值」的口径写。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     * @param trace      本次调用的上下文
     * @param result     本次出参；失败传空
     * @param promptText 请求文本（用量缺失时按它粗估）
     */
    private void recordOccupancy(String vendorCode, String modelKey, ModelCallTrace trace, Object result,
            String promptText) {
        boolean success = NullUtil.isNull(trace.errorCode());
        Long totalTokens = totalTokens(trace, result, promptText);
        routeStateStore.record(vendorCode, modelKey, success, success ? trace.latencyMs() : null,
                NullUtil.isNull(trace.errorCode()) ? null : trace.errorCode().name(), totalTokens, true);
    }

    /** 本次消耗的 token 合计：上下文用量优先，其次出参用量，都没有即按请求文本长度粗估 */
    private Long totalTokens(ModelCallTrace trace, Object result, String promptText) {
        Long counted = totalOf(trace.usage());
        if (NullUtil.isNull(counted) && result instanceof GenerationResult generation) {
            counted = totalOf(generation.getUsage());
        }
        return NullUtil.isNull(counted) ? estimateTokens(promptText) : counted;
    }

    /** 用量里的 token 合计：厂商回了合计即取它，只回单项即两项相加；三项全空为 null */
    private Long totalOf(ModelUsage usage) {
        if (NullUtil.isNull(usage)) {
            return null;
        }
        if (NullUtil.isNotNull(usage.getTotalTokens())) {
            return usage.getTotalTokens().longValue();
        }
        if (NullUtil.isNull(usage.getPromptTokens()) && NullUtil.isNull(usage.getCompletionTokens())) {
            return null;
        }
        long prompt = NullUtil.isNull(usage.getPromptTokens()) ? 0L : usage.getPromptTokens();
        long completion = NullUtil.isNull(usage.getCompletionTokens()) ? 0L : usage.getCompletionTokens();
        return prompt + completion;
    }

    /** 按请求文本长度粗估 token：字符数 ÷ 3，只用于占用判断，不落明细、不算成本 */
    private Long estimateTokens(String promptText) {
        if (NullUtil.isNull(promptText) || promptText.isEmpty()) {
            return EMPTY_TEXT_TOKENS;
        }
        return (long) Math.max(promptText.length() / CHARS_PER_TOKEN, 1);
    }

    /** 对话入参的请求文本：各条消息的文本依次相接 */
    private String chatPromptText(ChatRequest request) {
        if (NullUtil.isNull(request) || NullUtil.isNull(request.getMessages())) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (ChatMessage message : request.getMessages()) {
            if (NullUtil.isNotNull(message) && NullUtil.isNotNull(message.getContent())) {
                text.append(message.getContent());
            }
        }
        return text.toString();
    }

    /** 向量入参的请求文本：本批各条文本依次相接 */
    private String embedPromptText(EmbeddingRequest request) {
        if (NullUtil.isNull(request) || NullUtil.isNull(request.getTexts())) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (String item : request.getTexts()) {
            if (NullUtil.isNotNull(item)) {
                text.append(item);
            }
        }
        return text.toString();
    }

    /** 视觉入参的请求文本：提示词与区域坐标（页码）依次相接，供厂商未回用量时粗估 token */
    private String visionPromptText(VisionRequest request) {
        if (NullUtil.isNull(request)) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        if (NullUtil.isNotNull(request.getPrompt())) {
            text.append(request.getPrompt());
        }
        ImageRegion region = request.getRegion();
        if (NullUtil.isNotNull(region) && NullUtil.isNotNull(region.getPageNo())) {
            text.append(' ').append(region.getPageNo());
        }
        return text.toString();
    }

    /**
     * 是否可降级：入参取映射后的知识异常或适配器原始异常，两者结论一致。
     *
     * @param cause 异常
     * @return 可降级为 true
     */
    public boolean canFallback(Throwable cause) {
        return modelErrorClassifier.canFallback(cause);
    }

    /** 对话调用体：模型名以选型结果为准，出参做结构校验后落成功明细 */
    private GenerationResult chatCall(String vendorCode, ChatRequest request, ModelCallTrace trace) {
        String modelKey = modelKey(request);
        GenerationResult generation = modelAdapter.chat(vendorCode, request);
        generation.setJson(outputSchemaValidator.validate(output(request), generation.getContent()));
        modelCallLogRecorder.recordSuccess(vendorCode, modelKey, ModelCapability.CHAT, trace, generation.getModel(),
                generation.getModelVersion(), generation.getUsage());
        return generation;
    }

    /** 向量调用体：模型名以选型结果为准，缺向量数据按输出结构校验失败拒绝，成功落明细 */
    private EmbeddingResult embedCall(String vendorCode, EmbeddingRequest request, ModelCallTrace trace) {
        String modelKey = modelKey(request);
        EmbeddingResult result = modelAdapter.embed(vendorCode, request);
        List<List<Float>> embeddings = NullUtil.isNull(result) ? null : result.getEmbeddings();
        if (NullUtil.isNull(embeddings) || embeddings.isEmpty()) {
            throw new KnowledgeException(ErrorCode.MODEL_OUTPUT_INVALID,
                    "厂商：" + vendorCode + "，模型：" + modelKey + "，原因：向量化响应缺少向量数据");
        }
        modelCallLogRecorder.recordSuccess(vendorCode, modelKey, ModelCapability.EMBEDDING, trace, result.getModel(),
                null, null);
        return result;
    }

    /**
     * 视觉调用体：模型名以选型结果为准，出参按请求里的输出约束做结构校验后落成功明细。
     * 明细的能力列取形参给出的能力（按能力选型的那条入口给出本次能力，直接给厂商码的那条按表格结构记）。
     *
     * @param vendorCode 厂商码
     * @param request    视觉入参
     * @param trace      本次调用的上下文
     * @param capability 本次调用按该能力计量
     * @return 出参（原始文本 + 校验后的对象树 + 用量 + 模型标识）
     */
    private GenerationResult visionCall(String vendorCode, VisionRequest request, ModelCallTrace trace,
            ModelCapability capability) {
        String modelKey = modelKey(request);
        GenerationResult generation = modelAdapter.vision(vendorCode, request);
        generation.setJson(outputSchemaValidator.validate(output(request), generation.getContent()));
        modelCallLogRecorder.recordSuccess(vendorCode, modelKey, capability, trace, generation.getModel(),
                generation.getModelVersion(), generation.getUsage());
        return generation;
    }

    /** 调用上下文：入参为空即新建一份（调用结束后丢弃） */
    private ModelCallTrace traceOf(ModelCallTrace trace) {
        return NullUtil.isNull(trace) ? new ModelCallTrace() : trace;
    }

    /** 对话入参里的输出约束；入参为空返回 null */
    private OutputSpec output(ChatRequest request) {
        return NullUtil.isNull(request) ? null : request.getOutput();
    }

    /** 视觉入参里的输出约束；入参为空返回 null */
    private OutputSpec output(VisionRequest request) {
        return NullUtil.isNull(request) ? null : request.getOutput();
    }

    /** 对话入参里的模型名；入参为空返回 null */
    private String modelKey(ChatRequest request) {
        return NullUtil.isNull(request) ? null : request.getModelKey();
    }

    /** 视觉入参里的模型名；入参为空返回 null */
    private String modelKey(VisionRequest request) {
        return NullUtil.isNull(request) ? null : request.getModelKey();
    }

    /** 向量入参里的模型名；入参为空返回 null */
    private String modelKey(EmbeddingRequest request) {
        return NullUtil.isNull(request) ? null : request.getModel();
    }

    /**
     * 失败当次的路由原因：经营由选型的调用在可降级的失败上按健康不佳记（该次失败即换模型的依据），
     * 不经选型的调用两列都留空。写入落在明细落库之前，失败那一行据此带出原因。
     *
     * @param callTrace 本次调用的上下文
     * @param error     本次失败映射出的知识异常
     */
    private void markUnhealthy(ModelCallTrace callTrace, KnowledgeException error) {
        if (NullUtil.isNotNull(callTrace.routeReason()) && canFallback(error)) {
            callTrace.setRouteReason(RouteReason.UNHEALTHY);
        }
    }

    /** 失败明细落库：只记真实发出的调用 */
    private void recordFailure(boolean issued, String vendorCode, String modelKey, ModelCapability capability,
            ModelCallTrace callTrace, ErrorCode errorCode) {
        if (issued) {
            modelCallLogRecorder.recordFailure(vendorCode, modelKey, capability, callTrace, errorCode);
        }
    }

    /** 映射成「错误码 + detail」：detail 带厂商码、模型名与厂商原文消息；已带消息的知识异常原样上抛 */
    private KnowledgeException toKnowledgeException(String vendorCode, String modelKey, RuntimeException cause) {
        if (cause instanceof KnowledgeException knowledgeException
                && NullUtil.isNotNull(knowledgeException.getMessage())) {
            return knowledgeException;
        }
        ErrorCode errorCode = modelErrorClassifier.classify(cause);
        KnowledgeException error = new KnowledgeException(errorCode,
                "厂商：" + vendorCode + "，模型：" + modelKey + "，原因：" + message(cause));
        error.initCause(cause);
        return error;
    }

    /** 异常原文：消息缺失时取异常类名 */
    private String message(RuntimeException cause) {
        return NullUtil.isNull(cause.getMessage()) ? cause.getClass().getName() : cause.getMessage();
    }

    /** 形参校验：厂商码缺失按未配置的厂商拒绝，模型名缺失按参数问题拒绝 */
    private void checkArguments(String vendorCode, String modelKey) {
        if (NullUtil.isNull(vendorCode) || vendorCode.isBlank()) {
            throw new KnowledgeException(ErrorCode.MODEL_VENDOR_UNKNOWN, "厂商：" + vendorCode);
        }
        if (NullUtil.isNull(modelKey)) {
            throw new KnowledgeException(ErrorCode.MODEL_BAD_REQUEST, "模型名不能为空，厂商：" + vendorCode);
        }
    }

    /**
     * 单次调用的入参形状：对话与向量两个模态共用按能力选型的调用链。
     *
     * @param <T> 出参类型
     */
    private interface ModelCall<T> {

        /**
         * 执行一次调用（把本次选中的模型名写进入参、调用适配器、校验出参与落成功明细）。
         *
         * @param vendorCode 厂商码
         * @param modelKey   本次选中的模型名
         * @param trace      本次调用的上下文
         * @return 本次调用的出参
         */
        T call(String vendorCode, String modelKey, ModelCallTrace trace);

        /**
         * 调用前检查：请求不发出的拒绝点。
         *
         * @return 调用前检查
         */
        Runnable beforeCall();

        /**
         * 本次调用的请求文本：厂商未回用量时按它粗估 token，补上占用读数。
         *
         * @return 请求文本；无文本为 null
         */
        String promptText();
    }
}
