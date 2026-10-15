package com.workbuddy.model.dashscope;

import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.GenerationResult;
import com.workbuddy.common.domain.model.VisionRequest;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.config.ModelVendorProperties;
import com.workbuddy.model.engine.ModelInstanceRegistry;
import com.workbuddy.model.gateway.ModelGatewayPort;
import com.workbuddy.model.invoke.ModelInvoker;
import com.workbuddy.model.listener.ModelCallTrace;
import com.workbuddy.model.listener.ModelCallTraceHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 模型网关端口实现（OpenAI 兼容协议）：向量化把一批文本编码成向量，视觉模态按图像与提示词取结构化输出。
 * 向量侧的厂商按目录解析「模型 → 厂商」（{@link ModelInstanceRegistry#resolveVendorCode(String)}），
 * 模型名取请求体的 {@code EmbeddingRequest.model}；视觉侧两个入口都用请求体的 {@code VisionRequest.modelKey}，
 * 按能力选型那条的厂商码由候选给出。实例的 baseUrl / apiKey / 超时 / 重试都来自
 * {@code knowledge.model.vendors.<厂商码>}。
 *
 * <p>一次调用经 {@link ModelInvoker} 走适配器：异常按三个模态同一套错误码映射，
 * 应答缺向量数据归「输出结构校验失败」（40469），视觉出参按请求里的输出约束做结构校验。
 * 向量出参与入参逐项对应：向量列表与输入一一对应、维度取向量长度、模型回显取响应回传值
 * （缺失时才回填请求里的模型名）、requestId 原样带回。
 * 调用上下文按当前线程取：调用线程已绑定即复用该上下文（多次调用累计在同一份上下文里），
 * 未绑定即新建一份并在本次调用结束时解绑。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashScopeModelProvider implements ModelGatewayPort {

    private final ModelVendorProperties vendorProperties;

    private final ModelInstanceRegistry instanceRegistry;

    private final ModelInvoker modelInvoker;

    /** 调用上下文持有：向量调用按它把一次调用的上下文绑在调用线程上，HTTP 客户端装饰器与监听器都按它取回 */
    private final ModelCallTraceHolder modelCallTraceHolder;

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        String modelKey = NullUtil.isNull(request) ? null : request.getModel();
        String vendorCode = resolveVendorCode(modelKey);
        ModelVendorProperties.Vendor vendor = vendorProperties.getVendors().get(vendorCode);
        ThrowUtil.throwIf(NullUtil.isNull(vendor) || NullUtil.isNull(vendor.getApiKey()) || vendor.getApiKey().isBlank(),
                () -> new IllegalStateException(
                        "向量化 API Key 未配置（knowledge.model.vendors." + vendorCode + ".api-key）"));
        ModelCallTrace bound = modelCallTraceHolder.current();
        ModelCallTrace trace = NullUtil.isNull(bound) ? new ModelCallTrace() : bound;
        modelCallTraceHolder.bind(trace);
        try {
            return modelInvoker.embed(vendorCode, request, trace);
        } finally {
            if (NullUtil.isNull(bound)) {
                modelCallTraceHolder.clear();
            }
        }
    }

    /**
     * 视觉模态：厂商码由形参给出，模型名取 {@code VisionRequest.modelKey}，出参口径与对话模态一致。
     * 调用上下文按当前线程取：调用线程已绑定即复用该上下文，未绑定即新建一份并在本次调用结束时解绑。
     *
     * @param vendorCode 厂商码
     * @param request    视觉入参（模型/图像/区域/提示词/输出约束/超时）
     * @return 原始文本 + 通用树 + 用量 + 模型标识
     */
    @Override
    public GenerationResult vision(String vendorCode, VisionRequest request) {
        return inTrace(() -> modelInvoker.vision(vendorCode, request, modelCallTraceHolder.current()));
    }

    /**
     * 视觉模态（按能力自动选型）：候选集按能力取，厂商码由本次选中的候选给出，模型名写进请求体。
     * 出参与明细口径与按厂商码直调的那条入口一致，明细的能力列取形参给出的能力。
     *
     * @param capability 能力（OCR / LAYOUT / TABLE_STRUCTURE）
     * @param request    视觉入参（模型名由选型结果覆盖）
     * @return 原始文本 + 通用树 + 用量 + 模型标识
     */
    @Override
    public GenerationResult visionByCapability(ModelCapability capability, VisionRequest request) {
        return inTrace(() -> modelInvoker.visionByCapability(capability, request));
    }

    /**
     * 按模型名解析厂商码：配置里一个厂商都没有时按「模型厂商未配置」直接拒绝，其余交给目录解析
     * （目录优先，目录未收录时回落到唯一厂商）。
     *
     * @param modelKey 模型名
     * @return 厂商码
     */
    @Override
    public String resolveVendorCode(String modelKey) {
        Map<String, ModelVendorProperties.Vendor> vendors = vendorProperties.getVendors();
        if (NullUtil.isNull(vendors) || vendors.isEmpty()) {
            throw new KnowledgeException(ErrorCode.MODEL_VENDOR_UNKNOWN, "厂商：未配置，配置键：knowledge.model.vendors");
        }
        return instanceRegistry.resolveVendorCode(modelKey);
    }

    /**
     * 绑定调用上下文执行一次调用：调用线程未绑定上下文时新建一份并在结束时解绑，
     * 已绑定则复用（多次调用累计在同一份上下文里）。
     *
     * @param call 本次调用
     * @return 本次调用的出参
     */
    private GenerationResult inTrace(Supplier<GenerationResult> call) {
        ModelCallTrace bound = modelCallTraceHolder.current();
        if (NullUtil.isNotNull(bound)) {
            return call.get();
        }
        modelCallTraceHolder.bind(new ModelCallTrace());
        try {
            return call.get();
        } finally {
            modelCallTraceHolder.clear();
        }
    }
}
