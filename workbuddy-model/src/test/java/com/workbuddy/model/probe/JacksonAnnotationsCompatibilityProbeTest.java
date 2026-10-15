package com.workbuddy.model.probe;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jackson 混版运行时探针：注解 2.22（根 `pom.xml` 钉版）与 databind 2.15.4（Boot 3.2.5 管理）并存时，
 * 注解类的加载、注解驱动的序列化与反序列化往返逐条真跑一遍。
 *
 * <p>触发本探针的现场：`com.networknt:json-schema-validator:3.0.8` 传递的 `tools.jackson` 3.2.1
 * 在类初始化阶段读 {@code com.fasterxml.jackson.annotation.JsonSerializeAs}，注解停在 2.15.4 时该类不存在，
 * 调 {@code SchemaRegistry.getSchema(...)} 抛 {@code NoClassDefFoundError}。
 *
 * @author cxxl
 */
class JacksonAnnotationsCompatibilityProbeTest {

    /** 校验库类初始化要读的注解类全名（用字符串查，注解未钉版时编译仍通过、运行期给出明确失败） */
    private static final String REQUIRED_ANNOTATION = "com.fasterxml.jackson.annotation.JsonSerializeAs";

    /** 根 `pom.xml` 钉定的注解版本 */
    private static final String EXPECTED_ANNOTATIONS_VERSION = "2.22";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void annotationClassRequiredByValidatorShouldBeLoadable() throws ClassNotFoundException {
        Class<?> type = Class.forName(REQUIRED_ANNOTATION);

        assertTrue(type.isAnnotation(), type.getName());
        assertEquals(EXPECTED_ANNOTATIONS_VERSION, type.getPackage().getImplementationVersion(),
                "注解实现版本取自 " + type.getName() + " 所在 JAR 的清单");
        Set<String> members = Arrays.stream(type.getDeclaredMethods()).map(Method::getName)
                .collect(Collectors.toCollection(HashSet::new));
        assertEquals(Set.of("value", "content", "key"), members, "2.22 新增注解的完整成员");
    }

    @Test
    void databindShouldStayAtBootManagedVersion() {
        Version version = mapper.version();

        assertEquals(2, version.getMajorVersion(), version.toFullString());
        assertEquals(15, version.getMinorVersion(), version.toFullString());
        assertEquals(4, version.getPatchLevel(), version.toFullString());
    }

    @Test
    void roundTripShouldHonorRenameIgnoreIncludeAndFormat() throws IOException {
        Payload payload = new Payload();
        payload.setUserName("张三");
        payload.setInternalToken("secret");
        payload.setBirthday(Date.from(java.time.Instant.parse("2024-05-06T00:00:00Z")));

        String json = mapper.writeValueAsString(payload);

        assertTrue(json.contains("\"user_name\":\"张三\""), json);
        assertFalse(json.contains("internalToken"), json);
        assertFalse(json.contains("secret"), json);
        assertFalse(json.contains("nickname"), json);
        assertTrue(json.contains("\"birthday\":\"2024-05-06\""), json);

        Payload restored = mapper.readValue(json, Payload.class);

        assertEquals("张三", restored.getUserName());
        assertNull(restored.getInternalToken());
        assertNull(restored.getNickname());
        assertEquals(payload.getBirthday(), restored.getBirthday());

        Payload withNickname = new Payload();
        withNickname.setUserName("李四");
        withNickname.setNickname("小李");
        String filled = mapper.writeValueAsString(withNickname);

        assertTrue(filled.contains("\"nickname\":\"小李\""), filled);
        assertEquals("小李", mapper.readValue(filled, Payload.class).getNickname());
    }

    @Test
    void roundTripShouldHonorCustomSerializerAndDeserializer() throws IOException {
        Payload payload = new Payload();
        payload.setUserName("张三");
        payload.setCode("a1");

        String json = mapper.writeValueAsString(payload);

        assertTrue(json.contains("\"code\":\"A1\""), json);
        assertEquals("A1", mapper.readValue(json, Payload.class).getCode());
        assertEquals("B2", mapper.readValue("{\"code\":\"b2\"}", Payload.class).getCode());
    }

    /** 往返载体：字段改名、忽略字段、非空才写出、日期格式、自定义序列化各一处 */
    static class Payload {

        @JsonProperty("user_name")
        private String userName;

        @JsonIgnore
        private String internalToken;

        private String nickname;

        @JsonFormat(pattern = "yyyy-MM-dd", timezone = "UTC")
        private Date birthday;

        @JsonSerialize(using = UpperCaseSerializer.class)
        @JsonDeserialize(using = UpperCaseDeserializer.class)
        private String code;

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public String getInternalToken() {
            return internalToken;
        }

        public void setInternalToken(String internalToken) {
            this.internalToken = internalToken;
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String getNickname() {
            return nickname;
        }

        public void setNickname(String nickname) {
            this.nickname = nickname;
        }

        public Date getBirthday() {
            return birthday;
        }

        public void setBirthday(Date birthday) {
            this.birthday = birthday;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }
    }

    /** 自定义序列化器：写出大写形态 */
    static class UpperCaseSerializer extends JsonSerializer<String> {

        @Override
        public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(value.toUpperCase(Locale.ROOT));
        }
    }

    /** 自定义反序列化器：读入后统一为大写形态 */
    static class UpperCaseDeserializer extends JsonDeserializer<String> {

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return parser.getText().toUpperCase(Locale.ROOT);
        }
    }
}
