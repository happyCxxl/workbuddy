package com.workbuddy.model.dashscope;

import com.workbuddy.common.domain.model.OutputFormat;
import com.workbuddy.common.domain.model.OutputSpec;
import com.workbuddy.common.domain.model.VisionRequest;
import com.workbuddy.common.domain.model.calllog.ModelCallLogEntry;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.enums.model.CallStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.model.adapter.OpenAiCompatibleAdapter;
import com.workbuddy.model.calllog.ModelCallLogRecorder;
import com.workbuddy.model.catalog.ModelCatalogPort;
import com.workbuddy.model.config.ModelRouteProperties;
import com.workbuddy.model.config.ModelVendorProperties;
import com.workbuddy.model.engine.ModelInstanceRegistry;
import com.workbuddy.model.engine.OpenAiCompatibleEngineFactory;
import com.workbuddy.model.invoke.ModelErrorClassifier;
import com.workbuddy.model.invoke.ModelInvoker;
import com.workbuddy.model.listener.ModelCallTraceHolder;
import com.workbuddy.model.listener.UsageRecordingListener;
import com.workbuddy.model.route.ModelCircuitBreakers;
import com.workbuddy.model.route.ModelRouter;
import com.workbuddy.model.route.RouteStateStore;
import com.workbuddy.model.support.FakeOpenAiServer;
import com.workbuddy.model.support.InMemoryModelCallLogPort;
import com.workbuddy.model.validate.OutputSchemaValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关端口的视觉入口单测（本地假 OpenAI 兼容端点）：按能力选型那条从目录拿候选、用候选的厂商码与模型名
 * 发起多模态调用，并把明细的能力列按本次能力落；解析不出候选时按「模型厂商未配置」拒绝。
 *
 * @author cxxl
 */
class DashScopeVisionProviderTest {

    private static final String VENDOR = "dashscope";

    /** 视觉模型名：目录里声明文字识别能力 */
    private static final String MODEL = "qwen-vl-ocr";

    /** 参考 schema：与解析侧的 OCR 输出约束同形 */
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"lines\":{\"type\":\"array\","
            + "\"items\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},"
            + "\"required\":[\"text\"],\"additionalProperties\":false}}},"
            + "\"required\":[\"lines\"],\"additionalProperties\":false}";

    private static final String VISION_RESPONSE = "{\"id\":\"v1\",\"model\":\"" + MODEL + "\","
            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"lines\\\":[]}\"},"
            + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":2,\"total_tokens\":11}}";

    private FakeOpenAiServer server;

    private InMemoryModelCallLogPort callLog;

    private DashScopeModelProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeOpenAiServer();
        server.respondWith(200, VISION_RESPONSE);
        callLog = new InMemoryModelCallLogPort();
        provider = provider(new VisionCatalog(true));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void visionByCapabilityShouldRouteToCandidateAndSendImageRequest() {
        var result = provider.visionByCapability(ModelCapability.OCR, request());

        assertEquals("{\"lines\":[]}", result.getContent());
        assertEquals(MODEL, result.getModel());
        assertEquals(FakeOpenAiServer.CHAT_PATH, server.lastPath());
        assertEquals("Bearer sk-vision", server.lastAuthorization());
        String body = server.lastBody();
        assertTrue(body.contains("\"" + MODEL + "\""), body);
        assertTrue(body.contains("\"image_url\""), body);
        assertTrue(body.contains("\"response_format\""), body);
        assertEquals(1, server.requestCount());
    }

    @Test
    void visionByCapabilityShouldWriteCallLogWithGivenCapability() {
        provider.visionByCapability(ModelCapability.OCR, request());

        assertEquals(1, callLog.entries().size());
        ModelCallLogEntry entry = callLog.lastEntry();
        assertEquals(ModelCapability.OCR, entry.getCapability());
        assertEquals(VENDOR, entry.getVendor());
        assertEquals(MODEL, entry.getModel());
        assertEquals(CallStatus.SUCCESS, entry.getStatus());
        assertEquals(1, entry.getUsageUnknown(), "OCR 的计费单位未定，用量按未知记");
    }

    @Test
    void visionShouldUseGivenVendorCodeDirectly() {
        var result = provider.vision(VENDOR, request());

        assertEquals(MODEL, result.getModel());
        assertEquals(1, server.requestCount());
        assertEquals(ModelCapability.TABLE_STRUCTURE, callLog.lastEntry().getCapability(),
                "按厂商码直调的那条入口按表格结构记能力");
    }

    @Test
    void visionWithoutCandidateShouldBeRejectedWithVendorUnknown() {
        provider = provider(new VisionCatalog(false));

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> provider.visionByCapability(ModelCapability.OCR, request()));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
        assertEquals(0, server.requestCount());
        assertTrue(callLog.entries().isEmpty(), "无候选的失败不落明细");
    }

    /** 视觉入参：模型名 + 图像引用 + 提示词 + JSON_SCHEMA 输出约束 */
    private VisionRequest request() {
        VisionRequest request = new VisionRequest();
        request.setModelKey(MODEL);
        request.setImageRef("https://example.test/page7.png");
        request.setPrompt("按图给出逐行文本");
        OutputSpec output = new OutputSpec();
        output.setFormat(OutputFormat.JSON_SCHEMA);
        output.setSchema(SCHEMA);
        request.setOutput(output);
        return request;
    }

    /** 按生产装配口径接线：厂商配置 + 目录 + 引擎 + 监听器 + 路由 + 调用器 + 端口实现 */
    private DashScopeModelProvider provider(ModelCatalogPort catalog) {
        ModelVendorProperties vendorProperties = new ModelVendorProperties();
        Map<String, ModelVendorProperties.Vendor> vendors = new LinkedHashMap<>();
        ModelVendorProperties.Vendor vendor = new ModelVendorProperties.Vendor();
        vendor.setBaseUrl(server.baseUrl());
        vendor.setApiKey("sk-vision");
        vendor.setTimeoutMs(10000);
        vendor.setMaxRetries(0);
        vendors.put(VENDOR, vendor);
        vendorProperties.setVendors(vendors);
        ModelCallTraceHolder traceHolder = new ModelCallTraceHolder();
        ModelInstanceRegistry registry = new ModelInstanceRegistry(vendorProperties,
                new OpenAiCompatibleEngineFactory(new UsageRecordingListener(traceHolder), traceHolder), catalog);
        ModelErrorClassifier classifier = new ModelErrorClassifier();
        ModelRouteProperties routeProperties = new ModelRouteProperties();
        RouteStateStore stateStore = new RouteStateStore(routeProperties);
        ModelCircuitBreakers breakers = new ModelCircuitBreakers(routeProperties, classifier);
        ModelRouter router = new ModelRouter(routeProperties, vendorProperties, catalog, stateStore, breakers);
        ModelInvoker invoker = new ModelInvoker(new OpenAiCompatibleAdapter(registry, traceHolder),
                new OutputSchemaValidator(), classifier, traceHolder, new ModelCallLogRecorder(callLog, catalog),
                router, stateStore, breakers);
        return new DashScopeModelProvider(vendorProperties, registry, invoker, traceHolder);
    }

    /** 视觉用例的假目录：按开关决定是否给出文字识别候选 */
    private static final class VisionCatalog implements ModelCatalogPort {

        /** 是否给出候选 */
        private final boolean withCandidate;

        VisionCatalog(boolean withCandidate) {
            this.withCandidate = withCandidate;
        }

        @Override
        public List<ModelDescriptor> listByCapability(ModelCapability capability) {
            return withCandidate && capability == ModelCapability.OCR ? List.of(descriptor()) : List.of();
        }

        @Override
        public ModelDescriptor findByVendorAndModelKey(String vendorCode, String modelKey) {
            return VENDOR.equals(vendorCode) && MODEL.equals(modelKey) ? descriptor() : null;
        }

        @Override
        public ModelDescriptor findByModelKey(String modelKey) {
            return MODEL.equals(modelKey) ? descriptor() : null;
        }

        @Override
        public ModelDescriptor findUniqueEnabledByModelKey(String modelKey) {
            return findByModelKey(modelKey);
        }

        /** 目录项：厂商码取 dashscope，能力为文字识别，状态启用 */
        private ModelDescriptor descriptor() {
            ModelDescriptor descriptor = new ModelDescriptor();
            descriptor.setVendorCode(VENDOR);
            descriptor.setModelKey(MODEL);
            descriptor.setCapabilities(Set.of(ModelCapability.OCR));
            descriptor.setStatus("ENABLED");
            return descriptor;
        }
    }
}
