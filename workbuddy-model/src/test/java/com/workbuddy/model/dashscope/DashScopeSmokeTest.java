package com.workbuddy.model.dashscope;

import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.AuthType;
import com.workbuddy.common.domain.model.ChatMessage;
import com.workbuddy.common.domain.model.ChatRequest;
import com.workbuddy.common.domain.model.GenerationResult;
import com.workbuddy.common.domain.model.MessageRole;
import com.workbuddy.common.domain.model.ModelUsage;
import com.workbuddy.common.domain.model.calllog.ModelCallLogEntry;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.adapter.OpenAiCompatibleAdapter;
import com.workbuddy.model.calllog.ModelCallLogRecorder;
import com.workbuddy.model.catalog.ModelCatalogPort;
import com.workbuddy.model.config.ModelRouteProperties;
import com.workbuddy.model.config.ModelVendorProperties;
import com.workbuddy.model.engine.ModelInstanceRegistry;
import com.workbuddy.model.engine.OpenAiCompatibleEngineFactory;
import com.workbuddy.model.invoke.ModelErrorClassifier;
import com.workbuddy.model.invoke.ModelInvoker;
import com.workbuddy.model.listener.ModelCallTrace;
import com.workbuddy.model.listener.ModelCallTraceHolder;
import com.workbuddy.model.listener.UsageRecordingListener;
import com.workbuddy.model.route.ModelCircuitBreakers;
import com.workbuddy.model.route.ModelRouter;
import com.workbuddy.model.route.RouteStateStore;
import com.workbuddy.model.support.InMemoryModelCallLogPort;
import com.workbuddy.model.validate.OutputSchemaValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实端点手工验收入口（MODEL-08）：环境变量 `DASHSCOPE_API_KEY` 存在时才执行，
 * 缺失即整类跳过而不是失败。
 *
 * <p>执行内容是一次真对话与一次真向量编码，打 DashScope 的 OpenAI 兼容端点：
 * 厂商配置由本类按 `knowledge.model.vendors.dashscope` 的形状就地构造（密钥取环境变量，
 * 地址留空即取适配器内置的兼容端点），目录给两个真实模型名，提示词与文本都取最短。
 * 断言只覆盖"调用确实跑通"：对话的 `content` 非空、向量的维度与条数有值；
 * `usage` 按 D19 口径打印而不判失败（厂商不回 usage 或三项计数全空时三项 token 与成本都按未知记）。
 *
 * <p>运行方式（部署环境里跑）：配好环境变量后执行
 * {@code mvn -o test -Prun-tests -pl knowledge-model -Dtest=DashScopeSmokeTest}，
 * 控制台与测试报告里留下本次的 `content`、向量维度与 `usage` 三项。
 *
 * @author cxxl
 */
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class DashScopeSmokeTest {

    /** 厂商码：与配置键 knowledge.model.vendors.dashscope 同名 */
    private static final String VENDOR = "dashscope";

    /** 对话模型名：取 DashScope 兼容端点上的真实模型 */
    private static final String CHAT_MODEL = "qwen-max";

    /** 向量模型名：取 DashScope 兼容端点上的真实模型 */
    private static final String EMBED_MODEL = "text-embedding-v4";

    /** 环境变量名：密钥来源 */
    private static final String API_KEY_ENV = "DASHSCOPE_API_KEY";

    @Test
    void realChatShouldReturnContentAndUsage() {
        RealCall call = realCall();

        GenerationResult result = call.invoker.chat(VENDOR, chatRequest());

        assertNotNull(result, "对话出参不能为空");
        assertNotNull(result.getContent(), "对话内容不能为空");
        assertFalse(result.getContent().isBlank(), "对话内容不能为空白");
        report("chat", result.getModel(), result.getContent(), null, result.getUsage());
        assertEquals(1, call.callLog.entries().size(), "一次逻辑调用落一行明细");
        assertTrue(NullUtil.isNotNull(call.callLog.lastEntry().getLatencyMs()), "明细应记下整段延迟");
    }

    @Test
    void realEmbedShouldReturnVectorsAndDimension() {
        RealCall call = realCall();

        EmbeddingResult result = call.invoker.embed(VENDOR, embedRequest(), new ModelCallTrace());

        assertNotNull(result, "向量出参不能为空");
        assertNotNull(result.getEmbeddings(), "向量列表不能为空");
        assertEquals(1, result.getEmbeddings().size(), "两条短文本经批量入口合并为一次调用");
        assertNotNull(result.getDimension(), "向量维度不能为空");
        assertTrue(result.getDimension() > 0, "向量维度应为正数");
        report("embed", result.getModel(), null, result.getDimension(), null);
        ModelCallLogEntry entry = call.callLog.lastEntry();
        assertEquals(ModelCapability.EMBEDDING, entry.getCapability());
        // 向量出参不回用量，本次调用的用量按未知记（D19：未知与零不混用）
        assertEquals(1, entry.getUsageUnknown(), "向量出参不回用量即按未知记");
    }

    @Test
    void realChatByCapabilityShouldRouteAndRecordRouteReason() {
        RealCall call = realCall();

        GenerationResult result = call.invoker.chatByCapability(ModelCapability.CHAT, chatRequest());

        assertNotNull(result.getContent(), "对话内容不能为空");
        ModelCallLogEntry entry = call.callLog.lastEntry();
        assertNotNull(entry.getRouteReason(), "自动选型路径写路由原因");
        report("chatByCapability", result.getModel(), result.getContent(), null, result.getUsage());
    }

    /** 一行控制台记录：内容 / 向量维度与用量三项，供验收留档 */
    private void report(String scene, String model, String content, Integer dimension, ModelUsage usage) {
        System.out.println("真实调用 smoke, 场景=[" + scene + "], 模型=[" + model + "], 内容=[" + content + "], 向量维度=["
                + dimension + "], 用量=[" + usage + "]");
    }

    /** 对话入参：单条最短用户消息 + 纯文本输出 */
    private ChatRequest chatRequest() {
        ChatRequest request = new ChatRequest();
        request.setModelKey(CHAT_MODEL);
        ChatMessage message = new ChatMessage();
        message.setRole(MessageRole.USER);
        message.setContent("ping");
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(message);
        request.setMessages(messages);
        return request;
    }

    /** 向量入参：两条最短文本 */
    private EmbeddingRequest embedRequest() {
        EmbeddingRequest request = new EmbeddingRequest();
        request.setModel(EMBED_MODEL);
        request.setTexts(List.of("ping", "pong"));
        request.setRequestId("smoke-embed");
        return request;
    }

    /** 真实调用装配：厂商配置 + 目录 + 引擎 + 监听器 + 路由 + 调用器，明细落内存实现 */
    private RealCall realCall() {
        String apiKey = System.getenv(API_KEY_ENV);
        ModelVendorProperties vendorProperties = new ModelVendorProperties();
        Map<String, ModelVendorProperties.Vendor> vendors = new LinkedHashMap<>();
        ModelVendorProperties.Vendor vendor = new ModelVendorProperties.Vendor();
        vendor.setApiKey(apiKey);
        vendor.setAuthType(AuthType.BEARER);
        vendors.put(VENDOR, vendor);
        vendorProperties.setVendors(vendors);
        ModelCallTraceHolder traceHolder = new ModelCallTraceHolder();
        InMemoryModelCallLogPort callLog = new InMemoryModelCallLogPort();
        SmokeCatalog catalog = new SmokeCatalog();
        OpenAiCompatibleEngineFactory engineFactory =
                new OpenAiCompatibleEngineFactory(new UsageRecordingListener(traceHolder), traceHolder);
        ModelInstanceRegistry registry = new ModelInstanceRegistry(vendorProperties, engineFactory, catalog);
        ModelRouteProperties routeProperties = new ModelRouteProperties();
        ModelErrorClassifier classifier = new ModelErrorClassifier();
        RouteStateStore stateStore = new RouteStateStore(routeProperties);
        ModelCircuitBreakers breakers = new ModelCircuitBreakers(routeProperties, classifier);
        ModelRouter router = new ModelRouter(routeProperties, vendorProperties, catalog, stateStore, breakers);
        ModelInvoker invoker = new ModelInvoker(new OpenAiCompatibleAdapter(registry, traceHolder),
                new OutputSchemaValidator(), classifier, traceHolder,
                new ModelCallLogRecorder(callLog, catalog), router, stateStore, breakers);
        return new RealCall(invoker, callLog);
    }

    /**
     * 真实调用用的最小目录：按能力给出两个真实模型名，价格与限额留空
     * （价格留空即成本留空，限额留空即该模型不参与占用判断）。
     */
    private static final class SmokeCatalog implements ModelCatalogPort {

        @Override
        public List<ModelDescriptor> listByCapability(ModelCapability capability) {
            if (capability == ModelCapability.CHAT) {
                return List.of(descriptor(CHAT_MODEL, capability));
            }
            if (capability == ModelCapability.EMBEDDING) {
                return List.of(descriptor(EMBED_MODEL, capability));
            }
            return List.of();
        }

        @Override
        public ModelDescriptor findByVendorAndModelKey(String vendorCode, String modelKey) {
            if (!VENDOR.equals(vendorCode)) {
                return null;
            }
            if (CHAT_MODEL.equals(modelKey)) {
                return descriptor(modelKey, ModelCapability.CHAT);
            }
            return EMBED_MODEL.equals(modelKey) ? descriptor(modelKey, ModelCapability.EMBEDDING) : null;
        }

        @Override
        public ModelDescriptor findByModelKey(String modelKey) {
            return findByVendorAndModelKey(VENDOR, modelKey);
        }

        @Override
        public ModelDescriptor findUniqueEnabledByModelKey(String modelKey) {
            return findByModelKey(modelKey);
        }

        /** 目录项：厂商码取 dashscope，能力单项，状态启用 */
        private ModelDescriptor descriptor(String modelKey, ModelCapability capability) {
            ModelDescriptor descriptor = new ModelDescriptor();
            descriptor.setVendorCode(VENDOR);
            descriptor.setModelKey(modelKey);
            descriptor.setCapabilities(Set.of(capability));
            descriptor.setStatus("ENABLED");
            return descriptor;
        }
    }

    /** 一次真实调用的装配结果 */
    private static final class RealCall {

        /** 调用器 */
        private final ModelInvoker invoker;

        /** 调用明细端口 */
        private final InMemoryModelCallLogPort callLog;

        RealCall(ModelInvoker invoker, InMemoryModelCallLogPort callLog) {
            this.invoker = invoker;
            this.callLog = callLog;
        }
    }
}
