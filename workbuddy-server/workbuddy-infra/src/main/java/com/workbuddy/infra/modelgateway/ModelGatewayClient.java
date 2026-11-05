package com.workbuddy.infra.modelgateway;

import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 模型网关（灵犀）统一客户端：Java 侧调用模型能力的唯一 HTTP 出口。
 * 信封 {request_id, source, capability, payload} + X-Internal-Token 鉴权 + code/data 解包与错误归一；
 * 能力方法只做 payload 组装并返回网关响应的直译记录，不做业务解读；新增模型能力一律在本类加方法，
 * 任何调用方不得自建 HTTP 客户端。
 */
@Slf4j
@Component
public class ModelGatewayClient {

    private static final String EMBED_PATH = "/internal/v1/embed";
    private static final String OCR_PATH = "/internal/v1/ocr";
    private static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    private final ModelGatewayProperties properties;
    private final RestClient restClient;

    /**
     * 构造客户端：建立全生命周期复用的 RestClient。底层走 JDK HttpClient（JdkClientHttpRequestFactory）：
     * 连接 keep-alive 复用、虚拟线程执行器承载阻塞调用（高并发不占平台线程、不逐请求建连）；
     * 连接/读超时取 properties。
     *
     * @param properties 网关连接参数
     */
    public ModelGatewayClient(ModelGatewayProperties properties) {
        this.properties = properties;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(properties.getTimeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 一批文本编码为向量（网关直译返回，EmbeddingResult 组装归调用方）。
     *
     * @param request 向量化请求
     * @return 向量与网关元信息
     */
    public EmbedResponse embed(EmbeddingRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("input", request.getTexts());
        payload.put("dimensions", request.getDimension());
        payload.put("input_type", "document");
        payload.put("model_key", request.getModel());
        return EmbedResponse.from(post(EMBED_PATH, request.getRequestId(), "knowledge.embed",
                "EMBEDDING", payload));
    }

    /**
     * 图片 OCR（网关直译返回）：提示词与 schema 均按业务键统一管理（网关 prompts/schemas 目录），
     * 调用方只传 key 与占位符参数；单页图 pdfPageLimit 恒 1。
     *
     * @param requestId    调用追踪 ID
     * @param source       调用来源标识
     * @param image        图片字节
     * @param mimeType     图片 MIME
     * @param promptKey    业务提示词键（网关侧模板）
     * @param promptParams 提示词占位符参数（可空）
     * @param schemaKey    业务 schema 键（网关 schemas 目录；空为纯文本输出）
     * @param pdfPageLimit PDF 输入页数上限（图片输入不生效）
     * @return 模型原文与结构化字段
     */
    public OcrResponse ocrLines(String requestId, String source, byte[] image, String mimeType,
                                String promptKey, Map<String, Object> promptParams,
                                String schemaKey, int pdfPageLimit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("file_base64", Base64.getEncoder().encodeToString(image));
        payload.put("mime_type", mimeType);
        payload.put("prompt_key", promptKey);
        payload.put("prompt_params", promptParams);
        payload.put("schema_key", schemaKey);
        payload.put("pdf_page_limit", pdfPageLimit);
        return OcrResponse.from(post(OCR_PATH, requestId, source, "OCR", payload));
    }

    /**
     * 信封调用：组装请求体与鉴权头，code/data 解包与错误归一，返回 data 段。
     *
     * @param path       端点路径
     * @param requestId  调用追踪 ID
     * @param source     调用来源标识
     * @param capability 能力名
     * @param payload    能力入参
     * @return data 段
     */
    private Map<String, Object> post(String path, String requestId, String source, String capability,
                                     Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        body.put("source", source);
        body.put("capability", capability);
        body.put("payload", payload);
        Map<?, ?> response = null;
        try {
            response = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HEADER_INTERNAL_TOKEN, properties.getInternalToken())
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException exception) {
            log.warn("===> ModelGatewayClient 网关不可达, path={}", path, exception);
        }
        ThrowUtil.throwIf(response == null, ErrorCode.MODEL_UNREACHABLE, "模型网关不可达");
        ThrowUtil.throwIf(!Integer.valueOf(0).equals(response.get("code")),
                ErrorCode.MODEL_SERVER_ERROR, gatewayMessage(response));
        Map<String, Object> data = GatewayDataSupport.mapOf(response, "data");
        ThrowUtil.throwIf(NullUtil.isNull(data), ErrorCode.MODEL_SERVER_ERROR, "模型网关响应缺少 data");
        return data;
    }

    /** 网关失败消息：msg 字段（灵犀信封口径），缺失时回落 message，再缺给通用文案。 */
    private static String gatewayMessage(Map<?, ?> response) {
        Object msg = response.get("msg");
        if (msg != null) {
            return String.valueOf(msg);
        }
        Object message = response.get("message");
        return message == null ? "模型网关调用失败" : String.valueOf(message);
    }
}
