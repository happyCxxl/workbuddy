package com.workbuddy.model.service.support.invoke;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.workbuddy.common.domain.model.chat.OutputFormat;
import com.workbuddy.common.domain.model.chat.OutputSpec;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.utils.NullUtil;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 输出结构校验：调用前拒绝缺 schema 的 JSON_SCHEMA；调用后按格式校验并返回 JSON 树。
 */
@Component
public class OutputSchemaValidator {

    /**
     * 我们的 JSON 树用 Jackson 2（与接口序列化一致）
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * networknt 3.x 使用 Jackson 3 节点，校验单独用一份 mapper
     */
    private static final tools.jackson.databind.ObjectMapper JSON3 =
            new tools.jackson.databind.json.JsonMapper();

    /**
     * 严格 JSON Schema 校验器（2020-12 方言）
     */
    private static final SchemaRegistry SCHEMAS =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    /**
     * 调用前检查：JSON_SCHEMA 的 schema 为空白即拒绝，请求不发出。
     *
     * @param output 输出约束，可空
     */
    public void checkBeforeCall(OutputSpec output) {
        if (NullUtil.isNull(output) || output.getFormat() != OutputFormat.JSON_SCHEMA) {
            return;
        }
        ThrowUtil.throwIf(StrUtil.isBlank(output.getSchema()), ErrorCode.MODEL_BAD_REQUEST, "JSON_SCHEMA 输出缺少 schema");
    }

    /**
     * 调用后校验：TEXT 返回 null；JSON_OBJECT 要求合法对象；JSON_SCHEMA 做严格 schema 校验。
     *
     * @param output  输出约束，可空
     * @param content 模型原始文本
     * @return 解析后的 JSON 树；TEXT 返回 null
     */
    public Object validate(OutputSpec output, String content) {
        OutputFormat format = NullUtil.isNull(output) || NullUtil.isNull(output.getFormat())
                ? OutputFormat.TEXT : output.getFormat();
        if (format == OutputFormat.TEXT) {
            return null;
        }
        JsonNode node = parse(content);
        if (format == OutputFormat.JSON_OBJECT) {
            ThrowUtil.throwIf(!node.isObject(), ErrorCode.MODEL_OUTPUT_INVALID, "JSON_OBJECT 输出不是合法 JSON 对象");
            return node;
        }
        ThrowUtil.throwIf(StrUtil.isBlank(output.getSchema()), ErrorCode.MODEL_BAD_REQUEST,
                "JSON_SCHEMA 输出缺少 schema");
        Schema schema;
        try {
            schema = SCHEMAS.getSchema(output.getSchema());
        } catch (RuntimeException error) {
            throw new WorkbuddyException(ErrorCode.MODEL_BAD_REQUEST,
                    "JSON Schema 无法编译: " + error.getMessage());
        }
        tools.jackson.databind.JsonNode schemaNode;
        try {
            schemaNode = JSON3.readTree(content);
        } catch (RuntimeException error) {
            throw invalid("输出不是合法 JSON: " + error.getMessage());
        }
        List<Error> errors = schema.validate(schemaNode);
        ThrowUtil.throwIf(!errors.isEmpty(),
                () -> new WorkbuddyException(ErrorCode.MODEL_OUTPUT_INVALID,
                        "输出不满足 JSON Schema: " + errors.getFirst().getMessage()));
        return node;
    }

    private JsonNode parse(String content) {
        ThrowUtil.throwIf(StrUtil.isBlank(content), ErrorCode.MODEL_OUTPUT_INVALID, "输出为空，无法解析 JSON");
        try {
            return JSON.readTree(content);
        } catch (Exception error) {
            throw invalid("输出不是合法 JSON: " + error.getMessage());
        }
    }

    private WorkbuddyException invalid(String detail) {
        return new WorkbuddyException(ErrorCode.MODEL_OUTPUT_INVALID, detail);
    }
}
