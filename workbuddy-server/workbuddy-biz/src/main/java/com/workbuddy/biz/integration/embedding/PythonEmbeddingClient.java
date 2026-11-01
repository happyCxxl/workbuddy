package com.workbuddy.biz.integration.embedding;

import com.workbuddy.common.domain.embed.EmbeddingClient;
import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.biz.config.LingxiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量化出口的灵犀实现：把 Knowledge 的批次请求转成 {@code /internal/v1/embed} 调用。
 */
@Component
@RequiredArgsConstructor
public class PythonEmbeddingClient implements EmbeddingClient {

    private static final String EMBED_PATH = "/internal/v1/embed";

    private final LingxiProperties properties;

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("input", request.getTexts());
        payload.put("dimensions", request.getDimension());
        payload.put("input_type", "document");
        payload.put("model_key", request.getModel());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", request.getRequestId());
        body.put("source", "knowledge.embed");
        body.put("capability", "EMBEDDING");
        body.put("timeout_ms", request.getTimeoutMs());
        body.put("payload", payload);

        Map<?, ?> response;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getTimeoutMs());
        requestFactory.setReadTimeout(properties.getTimeoutMs());
        try {
            response = RestClient.builder()
                    .baseUrl(properties.getBaseUrl())
                    .requestFactory(requestFactory)
                    .build()
                    .post()
                    .uri(EMBED_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Internal-Token", properties.getInternalToken())
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException exception) {
            throw new WorkbuddyException(ErrorCode.MODEL_UNREACHABLE, "灵犀向量化服务不可达");
        }
        if (response == null || !Integer.valueOf(0).equals(response.get("code"))) {
            Object message = response == null ? null : response.get("message");
            throw new WorkbuddyException(ErrorCode.MODEL_SERVER_ERROR,
                    message == null ? "灵犀向量化调用失败" : String.valueOf(message));
        }
        return toResult(request, response.get("data"));
    }

    /**
     * 解析灵犀信封：data.vectors 与输入同序。
     *
     * @param request  原始请求
     * @param rawData  信封 data 段
     * @return 向量结果
     */
    private EmbeddingResult toResult(EmbeddingRequest request, Object rawData) {
        if (!(rawData instanceof Map<?, ?> data) || !(data.get("vectors") instanceof List<?> rawVectors)) {
            throw new WorkbuddyException(ErrorCode.MODEL_SERVER_ERROR, "灵犀向量化响应缺少 vectors");
        }
        List<List<Float>> embeddings = new ArrayList<>(rawVectors.size());
        for (Object rawRow : rawVectors) {
            if (!(rawRow instanceof List<?> row)) {
                throw new WorkbuddyException(ErrorCode.MODEL_SERVER_ERROR, "灵犀向量化响应格式错误");
            }
            List<Float> vector = new ArrayList<>(row.size());
            for (Object value : row) {
                if (!(value instanceof Number number)) {
                    throw new WorkbuddyException(ErrorCode.MODEL_SERVER_ERROR, "灵犀向量化响应格式错误");
                }
                vector.add(number.floatValue());
            }
            embeddings.add(vector);
        }
        EmbeddingResult result = new EmbeddingResult();
        result.setEmbeddings(embeddings);
        result.setDimension(embeddings.isEmpty() ? request.getDimension() : embeddings.getFirst().size());
        result.setModel(request.getModel());
        result.setRequestId(request.getRequestId());
        return result;
    }
}
