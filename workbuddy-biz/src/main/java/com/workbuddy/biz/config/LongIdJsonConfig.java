package com.workbuddy.biz.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.workbuddy.common.utils.NullUtil;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Long 类型的全局 JSON 序列化口径：**超过 JS 安全整数范围才输出字符串**。
 *
 * <h2>判定</h2>
 * 雪花 ID 有 19 位（约 2.1e18），超过 JavaScript 的 {@code Number.MAX_SAFE_INTEGER}
 * （2^53-1，约 9.0e15）：裸数字下发会被 {@code JSON.parse} 抹掉末几位
 * （{@code 2104612193694224386} → {@code 2104612193694220000}）。前端把该 ID 回传时后端查不到行，
 * 报出「知识库不存在」「运行记录不存在」这类与本意无关的错误。
 *
 * <h2>按阈值而非全量</h2>
 * 只有超过安全整数范围的 Long 转字符串；计数 / 字节数 / 耗时 / 分页号都是小数值，保持数字
 * （字符串化后前端做比较与求和会静默出错）。两者的量级天然分开，阈值判定不需要逐字段判断语义。
 *
 * <h2>全局配置而非字段注解</h2>
 * 口径在本类一处实现，不逐字段标 {@code @JsonSerialize(using = ToStringSerializer.class)}：
 * 注解方式会连计数字段一起字符串化，也依赖每处都记得加。
 *
 * <h2>影响范围</h2>
 * 只作用于 Spring MVC 自动配置的那个 {@code ObjectMapper}（HTTP 出入参），
 * 不影响内部自己 new 的 ObjectMapper、也不影响 worker / vector 模块。
 * <p>入参方向不需要额外处理：前端把 ID 当字符串传回来时，Jackson 反序列化到
 * {@code Long} 字段本来就能正确转换（已实测）。
 *
 * @author cxxl
 */
@Configuration
public class LongIdJsonConfig {

    /**
     * JS 安全整数上界：2^53 - 1 = 9007199254740991。
     *
     * <p>超过它的整数在 JS 里无法精确表示，必须转字符串。
     */
    private static final long JS_MAX_SAFE_INTEGER = 9007199254740991L;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longIdToStringCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("long-id-to-string");
            // 同时覆盖包装类型与基本类型：VO 字段用 Long，但集合元素、
            // 以及未来可能的 long 字段都走同一口径
            module.addSerializer(Long.class, new ThresholdLongSerializer());
            module.addSerializer(Long.TYPE, new ThresholdLongSerializer());
            builder.modulesToInstall(module);
        };
    }

    /**
     * 阈值判定序列化器：值在 JS 安全范围内输出数字，超出则输出字符串。
     *
     * <p>负值取绝对值比较（ID 不会为负，但这样更不容易出现意外）。
     */
    static final class ThresholdLongSerializer extends JsonSerializer<Long> {

        @Override
        public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            if (NullUtil.isNull(value)) {
                gen.writeNull();
                return;
            }
            if (Math.abs(value) <= JS_MAX_SAFE_INTEGER) {
                gen.writeNumber(value);
            } else {
                // 不用 writeString(String.valueOf(value))：直接写字符串字面量，
                // 避免任何本地化/格式化介入
                gen.writeString(value.toString());
            }
        }
    }
}
