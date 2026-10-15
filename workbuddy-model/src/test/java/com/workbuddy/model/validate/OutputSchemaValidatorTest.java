package com.workbuddy.model.validate;

import com.workbuddy.common.domain.model.OutputFormat;
import com.workbuddy.common.domain.model.OutputSpec;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构校验单测：TEXT 不校验、JSON_OBJECT 只要求 JSON 对象、JSON_SCHEMA 的严格校验与
 * schema 缺失（调用前拒绝）和 schema 无法编译的口径。
 *
 * @author cxxl
 */
class OutputSchemaValidatorTest {

    /** 严格校验用 schema：name 必填且为字符串，age 为整数 */
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
            + "\"age\":{\"type\":\"integer\"}},\"required\":[\"name\"],\"additionalProperties\":false}";

    /** 枚举约束用 schema：color 只能取 red / green */
    private static final String ENUM_SCHEMA = "{\"type\":\"object\",\"properties\":{\"color\":{\"type\":\"string\","
            + "\"enum\":[\"red\",\"green\"]}},\"required\":[\"color\"]}";

    /** 数组根结构的 schema */
    private static final String ARRAY_SCHEMA = "{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}";

    private final OutputSchemaValidator validator = new OutputSchemaValidator();

    @Test
    void textOutputShouldPassWithoutParsing() {
        assertNull(validator.validate(output(OutputFormat.TEXT, null), "这不是 JSON，也不做结构校验"));
        assertNull(validator.validate(output(OutputFormat.TEXT, null), null));
    }

    @Test
    void missingOutputSpecOrFormatShouldPassWithoutParsing() {
        assertNull(validator.validate(null, "任意文本"));
        assertNull(validator.validate(output(null, null), "任意文本"));
    }

    @Test
    void jsonObjectOutputShouldReturnParsedTree() {
        Object tree = validator.validate(output(OutputFormat.JSON_OBJECT, null), "{\"name\":\"张三\",\"age\":3}");

        Map<?, ?> parsed = assertInstanceOf(Map.class, tree);
        assertEquals("张三", parsed.get("name"));
        assertEquals(3, parsed.get("age"));
    }

    @Test
    void jsonObjectOutputNotJsonShouldBeRejected() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> validator.validate(output(OutputFormat.JSON_OBJECT, null), "not-json"));

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, error.getErrorCode());
        assertEquals(40469, error.getCode());
    }

    @Test
    void jsonObjectOutputOfArrayShouldBeRejected() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> validator.validate(output(OutputFormat.JSON_OBJECT, null), "[1,2]"));

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, error.getErrorCode());
        assertTrue(error.getMessage().contains("JSON 对象"), error.getMessage());
    }

    @Test
    void blankContentShouldBeRejected() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> validator.validate(output(OutputFormat.JSON_OBJECT, null), "   "));

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, error.getErrorCode());
    }

    @Test
    void jsonSchemaSatisfyingOutputShouldReturnParsedTree() {
        Object tree = validator.validate(output(OutputFormat.JSON_SCHEMA, SCHEMA), "{\"name\":\"张三\",\"age\":3}");

        Map<?, ?> parsed = assertInstanceOf(Map.class, tree);
        assertEquals("张三", parsed.get("name"));
    }

    @Test
    void jsonSchemaMissingRequiredFieldShouldBeRejected() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> validator.validate(output(OutputFormat.JSON_SCHEMA, SCHEMA), "{\"age\":3}"));

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, error.getErrorCode());
        assertTrue(error.getMessage().contains("不满足 schema"), error.getMessage());
    }

    @Test
    void jsonSchemaTypeMismatchShouldBeRejected() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> validator.validate(output(OutputFormat.JSON_SCHEMA, SCHEMA), "{\"name\":\"张三\",\"age\":\"三\"}"));

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, error.getErrorCode());
        assertTrue(error.getMessage().contains("不满足 schema"), error.getMessage());
    }

    @Test
    void jsonSchemaEnumViolationShouldBeRejected() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> validator.validate(output(OutputFormat.JSON_SCHEMA, ENUM_SCHEMA), "{\"color\":\"blue\"}"));

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, error.getErrorCode());
    }

    @Test
    void jsonSchemaOfArrayRootShouldBeValidated() {
        Object tree = validator.validate(output(OutputFormat.JSON_SCHEMA, ARRAY_SCHEMA), "[1,2]");

        List<?> parsed = assertInstanceOf(List.class, tree);
        assertEquals(2, parsed.size());
    }

    @Test
    void jsonSchemaWithoutSchemaShouldBeRejectedBeforeCall() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> validator.checkBeforeCall(output(OutputFormat.JSON_SCHEMA, "  ")));

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("schema"), error.getMessage());
        KnowledgeException validateError = assertThrows(KnowledgeException.class,
                () -> validator.validate(output(OutputFormat.JSON_SCHEMA, null), "{\"name\":\"张三\"}"));
        assertEquals(ErrorCode.MODEL_BAD_REQUEST, validateError.getErrorCode());
    }

    @Test
    void checkBeforeCallShouldAcceptTextAndJsonObjectAndMissingSpec() {
        assertDoesNotThrow(() -> validator.checkBeforeCall(output(OutputFormat.TEXT, null)));
        assertDoesNotThrow(() -> validator.checkBeforeCall(output(OutputFormat.JSON_OBJECT, null)));
        assertDoesNotThrow(() -> validator.checkBeforeCall(null));
    }

    @Test
    void schemaThatCannotCompileShouldBeRejectedAsBadRequest() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> validator.validate(output(OutputFormat.JSON_SCHEMA, "not a schema"), "{\"name\":\"张三\"}"));

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("无法编译"), error.getMessage());
    }

    /** 输出约束 */
    private OutputSpec output(OutputFormat format, String schema) {
        OutputSpec output = new OutputSpec();
        output.setFormat(format);
        output.setSchema(schema);
        return output;
    }
}
