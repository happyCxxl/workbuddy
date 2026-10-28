package com.workbuddy.biz.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Long 阈值序列化口径的契约测试。
 *
 * <p>口径：雪花 ID 以字符串下发（JS 精度上限 2^53 - 1），计数 / 耗时等小数值保持数字。
 *
 * <p>覆盖两个方向：
 * <ol>
 *   <li>序列化器本身的阈值判定（拆箱/装箱两种 Long）；</li>
 *   <li>自定义器装上 builder 之后确实生效（Bean 未被 Spring 扫到时会全绿而线上失效）。</li>
 * </ol>
 */
class LongIdJsonConfigTest {

    /** 2^53 - 1：JS 能精确表示的最大整数 */
    private static final long JS_MAX_SAFE = 9007199254740991L;

    /** 真实雪花 ID（知识库表实测值），远超 JS 安全范围 */
    private static final long SNOWFLAKE_ID = 2104612193694224386L;

    /** 用配置里的自定义器装配一个 ObjectMapper，模拟 Spring MVC 的行为 */
    private ObjectMapper mapperFromConfig() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new LongIdJsonConfig().longIdToStringCustomizer().customize(builder);
        return builder.build();
    }

    /**
     * 守护 {@code shouldSerializeSnowflakeIdAsString}：该场景行为改动即失败。
     */
    @Test
    @DisplayName("雪花 ID 超过 JS 安全整数 → 以字符串下发")
    void shouldSerializeSnowflakeIdAsString() throws Exception {
        String json = mapperFromConfig().writeValueAsString(new Holder(SNOWFLAKE_ID));
        assertEquals("{\"value\":\"" + SNOWFLAKE_ID + "\"}", json);
    }

    /**
     * 守护 {@code shouldKeepSmallNumbersAsNumbers}：该场景行为改动即失败。
     */
    @Test
    @DisplayName("计数 / 耗时等小数值 → 保持数字，不得被字符串化")
    void shouldKeepSmallNumbersAsNumbers() throws Exception {
        // 这几个都是项目里真实存在的小数值 Long：计数、字节数、耗时
        for (long small : new long[] {0L, 7L, 118L, 65536L, JS_MAX_SAFE}) {
            String json = mapperFromConfig().writeValueAsString(new Holder(small));
            assertEquals("{\"value\":" + small + "}", json,
                    "值 " + small + " 应保持数字");
        }
    }

    /**
     * 守护 {@code shouldSwitchExactlyAtJsSafeBoundary}：该场景行为改动即失败。
     */
    @Test
    @DisplayName("阈值边界：2^53 保持数字，2^53+1 转字符串")
    void shouldSwitchExactlyAtJsSafeBoundary() throws Exception {
        // JS_MAX_SAFE 本身仍可精确表示 → 数字
        assertTrue(mapperFromConfig().writeValueAsString(new Holder(JS_MAX_SAFE)).contains(":9007199254740991"));

        // 再加 1 就超出 → 字符串。这正是 KnowledgeBaseVoJsonContractTest 用的探针值
        long beyond = JS_MAX_SAFE + 1;
        String json = mapperFromConfig().writeValueAsString(new Holder(beyond));
        assertEquals("{\"value\":\"" + beyond + "\"}", json);
    }

    /**
     * 守护 {@code shouldSerializeNullAsNull}：该场景行为改动即失败。
     */
    @Test
    @DisplayName("null 仍序列化为 null，不被当成数字或字符串")
    void shouldSerializeNullAsNull() throws Exception {
        assertEquals("{\"value\":null}", mapperFromConfig().writeValueAsString(new Holder(null)));
    }

    /**
     * 守护 {@code shouldApplyToCollectionElements}：该场景行为改动即失败。
     */
    @Test
    @DisplayName("集合元素同样走阈值判定（List<Long> 的 ID 列表）")
    void shouldApplyToCollectionElements() throws Exception {
        String json = mapperFromConfig().writeValueAsString(List.of(7L, SNOWFLAKE_ID));
        assertEquals("[7,\"" + SNOWFLAKE_ID + "\"]", json);
    }

    /**
     * 守护 {@code shouldCoverBothBoxedAndPrimitiveLong}：该场景行为改动即失败。
     */
    @Test
    @DisplayName("装箱与拆箱两种 Long 都被覆盖")
    void shouldCoverBothBoxedAndPrimitiveLong() throws Exception {
        LongIdJsonConfig.ThresholdLongSerializer serializer = new LongIdJsonConfig.ThresholdLongSerializer();

        SimpleModule boxed = new SimpleModule();
        boxed.addSerializer(Long.class, serializer);
        ObjectMapper boxedMapper = new Jackson2ObjectMapperBuilder().modulesToInstall(boxed).build();
        assertTrue(boxedMapper.writeValueAsString(new Holder(SNOWFLAKE_ID)).contains(SNOWFLAKE_ID + "\""));

        SimpleModule primitive = new SimpleModule();
        primitive.addSerializer(Long.TYPE, serializer);
        ObjectMapper primitiveMapper = new Jackson2ObjectMapperBuilder().modulesToInstall(primitive).build();
        assertTrue(primitiveMapper.writeValueAsString(new PrimitiveHolder(SNOWFLAKE_ID)).contains(SNOWFLAKE_ID + "\""));
    }

    /**
     * 守护 {@code shouldRegisterCustomizerAsSpringBean}：该场景行为改动即失败。
     */
    @Test
    @DisplayName("自定义器被 Spring 容器注册为 Bean（证明 @Configuration 会被扫到）")
    void shouldRegisterCustomizerAsSpringBean() {
        // 只测"手工 new 出来的定制器行为"不够：万一本类没被组件扫描到
        // （包路径不对、漏了 @Configuration），单测仍会全绿而线上全炸。
        // 这里真的把本类当配置类注册进一个最小上下文，断言 Bean 确实存在。
        new ApplicationContextRunner()
                .withUserConfiguration(LongIdJsonConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(Jackson2ObjectMapperBuilderCustomizer.class);
                });
    }

    /**
     * 守护 {@code shouldBeAnnotatedAsConfiguration}：该场景行为改动即失败。
     */
    @Test
    @DisplayName("@Configuration 注解存在，且包路径在 Spring Boot 扫描范围内")
    void shouldBeAnnotatedAsConfiguration() {
        assertThat(LongIdJsonConfig.class.isAnnotationPresent(Configuration.class)).isTrue();
        // Spring Boot 主类在 com.workbuddy 下，配置类必须在其子包内才会被扫描到
        assertThat(LongIdJsonConfig.class.getPackageName()).startsWith("com.workbuddy");
    }

    /** 包装类型 Long 的载体 */
    static class Holder {
        private Long value;

        Holder(Long value) {
            this.value = value;
        }

        public Long getValue() {
            return value;
        }

        public void setValue(Long value) {
            this.value = value;
        }
    }

    /** 基本类型 long 的载体（Lombok 未启用，手写 getter 保证 Jackson 能识别字段） */
    static class PrimitiveHolder {
        private long value;

        PrimitiveHolder(long value) {
            this.value = value;
        }

        public long getValue() {
            return value;
        }

        public void setValue(long value) {
            this.value = value;
        }
    }
}
