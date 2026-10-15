package com.workbuddy.model.validate;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.workbuddy.common.domain.model.OutputFormat;
import com.workbuddy.common.domain.model.OutputSpec;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.NullUtil;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 输出结构校验：按 {@link OutputSpec} 的格式约束校验模型输出，校验通过时给出解析后的对象树（供出参回填）。
 *
 * <p>三类口径：{@code TEXT} 不校验；{@code JSON_OBJECT} 校「能否解析成 JSON 对象」；
 * {@code JSON_SCHEMA} 先解析再按 schema 做严格校验（类型 / 必填 / 枚举等约束），schema 自带 {@code $schema}
 * 声明时以自带声明为准，未声明时用默认方言。解析口径覆盖 JSON 对象与数组，与结构化输出的根结构约束一致。
 *
 * <p>失败一律抛 {@link KnowledgeException}：输出侧问题取「模型输出结构校验失败」，
 * schema 缺失或无法编译取「模型请求参数或内容被拒」。
 *
 * @author cxxl
 */
@Component
public class OutputSchemaValidator {

    /** 输出摘要长度上限（detail 里定位问题用） */
    private static final int BRIEF_LENGTH = 80;

    /** schema 编译入口：命中同文本 schema 时由注册表内部复用编译结果 */
    private final SchemaRegistry schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    /**
     * 调用前检查：{@code JSON_SCHEMA} 的输出约束缺 schema 即拒绝，请求不发出。
     *
     * @param output 输出约束（可空，空即按不校验处理）
     */
    public void checkBeforeCall(OutputSpec output) {
        if (OutputFormat.JSON_SCHEMA == formatOf(output) && StrUtil.isBlank(output.getSchema())) {
            throw new KnowledgeException(ErrorCode.MODEL_BAD_REQUEST, "JSON_SCHEMA 输出缺 schema");
        }
    }

    /**
     * 调用后校验：通过即返回解析后的对象树，{@code TEXT} 返回 null。
     *
     * @param output  输出约束（可空，空即按不校验处理）
     * @param content 模型输出的原始文本
     * @return 解析后的对象树；未做校验的路径为 null
     */
    public Object validate(OutputSpec output, String content) {
        OutputFormat format = formatOf(output);
        if (OutputFormat.TEXT == format) {
            return null;
        }
        Object tree = parse(content);
        if (OutputFormat.JSON_OBJECT == format) {
            if (!(tree instanceof JSONObject)) {
                throw outputInvalid("输出不是 JSON 对象：" + brief(content), null);
            }
            return tree;
        }
        validateAgainstSchema(output.getSchema(), content);
        return tree;
    }

    /** 解析成通用树：解析不出来即按输出结构错误拒绝 */
    private Object parse(String content) {
        if (StrUtil.isBlank(content)) {
            throw outputInvalid("输出为空", null);
        }
        try {
            return JSONUtil.parse(content);
        } catch (RuntimeException e) {
            throw outputInvalid("输出不是合法 JSON：" + brief(content), e);
        }
    }

    /** 按 schema 严格校验：schema 编译失败归请求参数问题，校验不通过归输出结构错误 */
    private void validateAgainstSchema(String schema, String content) {
        Schema compiled = compile(schema);
        List<Error> errors;
        try {
            errors = compiled.validate(content, InputFormat.JSON);
        } catch (RuntimeException e) {
            throw outputInvalid("输出不是合法 JSON：" + brief(content), e);
        }
        if (!errors.isEmpty()) {
            throw outputInvalid("输出不满足 schema：" + errors.getFirst().getMessage() + "（共 " + errors.size() + " 处）",
                    null);
        }
    }

    /** 编译 schema：文本自身不合法即按请求参数问题拒绝（业务侧写的约束有问题） */
    private Schema compile(String schema) {
        if (StrUtil.isBlank(schema)) {
            throw new KnowledgeException(ErrorCode.MODEL_BAD_REQUEST, "JSON_SCHEMA 输出缺 schema");
        }
        try {
            return schemaRegistry.getSchema(schema);
        } catch (RuntimeException e) {
            throw new KnowledgeException(ErrorCode.MODEL_BAD_REQUEST, "JSON_SCHEMA 无法编译：" + e.getMessage());
        }
    }

    /** 输出约束的格式：约束缺失或格式未标注按不校验处理 */
    private OutputFormat formatOf(OutputSpec output) {
        if (NullUtil.isNull(output) || NullUtil.isNull(output.getFormat())) {
            return OutputFormat.TEXT;
        }
        return output.getFormat();
    }

    /** 输出结构错误：detail 带失败原因，原始异常挂到 cause */
    private KnowledgeException outputInvalid(String detail, RuntimeException cause) {
        KnowledgeException error = new KnowledgeException(ErrorCode.MODEL_OUTPUT_INVALID, detail);
        if (NullUtil.isNotNull(cause)) {
            error.initCause(cause);
        }
        return error;
    }

    /** 输出摘要：超长即截断 */
    private String brief(String content) {
        if (NullUtil.isNull(content) || content.length() <= BRIEF_LENGTH) {
            return content;
        }
        return content.substring(0, BRIEF_LENGTH) + "...";
    }
}
