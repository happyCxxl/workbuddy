package com.workbuddy.model.service.support.invoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.workbuddy.common.domain.model.chat.OutputFormat;
import com.workbuddy.common.domain.model.chat.OutputSpec;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输出校验单测：TEXT / JSON_OBJECT / JSON_SCHEMA 的通过与失败口径。
 */
class OutputSchemaValidatorTest {

    private final OutputSchemaValidator validator = new OutputSchemaValidator();

    private OutputSpec spec(OutputFormat format, String schema) {
        OutputSpec output = new OutputSpec();
        output.setFormat(format);
        output.setSchema(schema);
        return output;
    }

    /** TEXT 不校验，返回 null（{@code textShouldSkip}）。 */
    @Test
    void textShouldSkip() {
        assertNull(validator.validate(spec(OutputFormat.TEXT, null), "not json"));
        assertNull(validator.validate(null, "not json"));
    }

    /** JSON_OBJECT 接受对象，拒绝数组与非法 JSON（{@code jsonObjectShouldRequireObject}）。 */
    @Test
    void jsonObjectShouldRequireObject() {
        Object result = validator.validate(spec(OutputFormat.JSON_OBJECT, null), "{\"a\":1}");

        assertInstanceOf(JsonNode.class, result);
        assertTrue(((JsonNode) result).isObject());
        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, assertThrows(WorkbuddyException.class,
                () -> validator.validate(spec(OutputFormat.JSON_OBJECT, null), "[1,2]")).getErrorCode());
        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, assertThrows(WorkbuddyException.class,
                () -> validator.validate(spec(OutputFormat.JSON_OBJECT, null), "not json")).getErrorCode());
    }

    /** JSON_SCHEMA 满足 schema 通过，不满足失败（{@code jsonSchemaShouldValidate}）。 */
    @Test
    void jsonSchemaShouldValidate() {
        String schema = "{\"type\":\"object\",\"required\":[\"a\"],"
                + "\"properties\":{\"a\":{\"type\":\"string\"}}}";

        Object result = validator.validate(spec(OutputFormat.JSON_SCHEMA, schema), "{\"a\":\"x\"}");

        assertInstanceOf(JsonNode.class, result);
        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, assertThrows(WorkbuddyException.class,
                () -> validator.validate(spec(OutputFormat.JSON_SCHEMA, schema), "{\"a\":1}")).getErrorCode());
    }

    /** JSON_SCHEMA 缺 schema 或 schema 无法编译按参数错误拒绝（{@code jsonSchemaShouldRejectBadSchema}）。 */
    @Test
    void jsonSchemaShouldRejectBadSchema() {
        assertEquals(ErrorCode.MODEL_BAD_REQUEST, assertThrows(WorkbuddyException.class,
                () -> validator.checkBeforeCall(spec(OutputFormat.JSON_SCHEMA, " "))).getErrorCode());
        assertEquals(ErrorCode.MODEL_BAD_REQUEST, assertThrows(WorkbuddyException.class,
                () -> validator.validate(spec(OutputFormat.JSON_SCHEMA, "{\"type\":"), "{}")).getErrorCode());
    }
}
