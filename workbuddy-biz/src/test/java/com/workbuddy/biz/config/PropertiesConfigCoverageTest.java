package com.workbuddy.biz.config;

import com.workbuddy.auth.config.JwtProperties;
import com.workbuddy.auth.config.LoginGuardProperties;
import com.workbuddy.infra.vector.MilvusProperties;
import com.workbuddy.model.config.ModelRouteProperties;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.embedding.EmbedProperties;
import com.workbuddy.filecenter.config.FileProperties;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.preprocessing.PreprocessProperties;
import com.workbuddy.worker.structure.StructureProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置面覆盖口径：主文件与 `spring.config.import` 清单合成一本配置面，登记在此的每个配置类的每个字段，
 * 都必须在配置面里有同名 kebab-case 键。
 *
 * <p>字段缺键时绑定不报错，运行期取 Java 默认值（数值 0、对象 null）——配置面完整是唯一防线，故逐键对账。
 * 缺键的报错带键名与该字段所属类，修法是到 `config/` 下对应的环节文件补键。
 *
 * <p>`ModelVendorProperties` 不登记：厂商整段可缺省（不配即没有厂商进入运行时），键的可选性由
 * `ModelVendorPropertiesTest` 的绑定用例守。新增配置类时在此登记前缀与类型。
 */
class PropertiesConfigCoverageTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");

    private static final Path MAIN = RESOURCES.resolve("application.yml");

    private static final String IMPORT = "spring.config.import";

    private static final String CLASSPATH = "classpath:";

    /** 可选导入前缀：这类文件缺失即跳过（本机密钥覆盖文件被 gitignore，别的机器上没有） */
    private static final String OPTIONAL = "optional:";

    /** 键前缀 → 配置类（登记顺序即报错顺序） */
    private static final Map<String, Class<?>> TARGETS = new LinkedHashMap<>();

    static {
        TARGETS.put("knowledge.parse", ParseProperties.class);
        TARGETS.put("knowledge.structure", StructureProperties.class);
        TARGETS.put("knowledge.preprocess", PreprocessProperties.class);
        TARGETS.put("knowledge.chunk", ChunkProperties.class);
        TARGETS.put("knowledge.embed", EmbedProperties.class);
        TARGETS.put("knowledge.vector.milvus", MilvusProperties.class);
        TARGETS.put("workbuddy.file", FileProperties.class);
        TARGETS.put("knowledge.task", TaskQueueProperties.class);
        TARGETS.put("workbuddy.auth.jwt", JwtProperties.class);
        TARGETS.put("workbuddy.auth.guard", LoginGuardProperties.class);
        TARGETS.put("retrieval.capabilities", RetrievalCapabilityProperties.class);
        TARGETS.put("retrieval.run", RetrievalRunProperties.class);
        TARGETS.put("workbuddy.model.route", ModelRouteProperties.class);
    }

    @TempDir
    Path tmp;

    /**
     * 守护 {@code everyPropertiesFieldShouldHaveConfigKey}：该场景行为改动即失败。
     */
    @Test
    void everyPropertiesFieldShouldHaveConfigKey() throws Exception {
        Set<String> keys = configFaceKeys();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Class<?>> entry : TARGETS.entrySet()) {
            Class<?> type = entry.getValue();
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String key = entry.getKey() + "." + camelToKebab(field.getName());
                if (!covered(keys, key)) {
                    missing.add(key + "（" + type.getSimpleName() + "." + field.getName() + "）");
                }
            }
        }
        assertTrue(missing.isEmpty(), () -> "配置面缺配置项: " + String.join("、", missing)
                + "；修法：在 config/ 下对应的环节文件里补上这些键（缺键时绑定不报错，运行期取 0 或 null）。");
    }

    /** 键是否被配置面覆盖：叶子键本身，或它下面有子键（映射/列表字段） */
    private boolean covered(Set<String> keys, String key) {
        return keys.contains(key) || keys.stream().anyMatch(name -> name.startsWith(key + "."));
    }

    /** 配置面：主文件与导入清单里各文件的键并集（键存在性只看并集，覆盖顺序不影响判定） */
    private Set<String> configFaceKeys() throws IOException {
        Set<String> keys = new LinkedHashSet<>(propertyNames(MAIN));
        for (String location : importLocations()) {
            boolean optional = location.startsWith(OPTIONAL);
            String resource = optional ? location.substring(OPTIONAL.length()) : location;
            Path file = RESOURCES.resolve(resource.replace(CLASSPATH, ""));
            if (optional && !Files.exists(file)) {
                continue;
            }
            assertTrue(Files.exists(file), () -> "导入清单里的文件不存在: " + location
                    + "（实际查找 " + file.toAbsolutePath() + "）；修法：补上该文件，或改清单那一行。");
            keys.addAll(propertyNames(file));
        }
        return keys;
    }

    /** `spring.config.import` 清单（单个与列表两种写法都收） */
    private List<String> importLocations() throws IOException {
        List<String> locations = new ArrayList<>();
        for (EnumerablePropertySource<?> source : sources(MAIN)) {
            for (String name : source.getPropertyNames()) {
                if (name.equals(IMPORT) || name.startsWith(IMPORT + "[")) {
                    locations.add(String.valueOf(source.getProperty(name)));
                }
            }
        }
        return locations;
    }

    /** 一份 yml 的全部键（加载器已把嵌套段拍平成点分键） */
    private List<String> propertyNames(Path yml) throws IOException {
        List<String> names = new ArrayList<>();
        for (EnumerablePropertySource<?> source : sources(yml)) {
            names.addAll(List.of(source.getPropertyNames()));
        }
        return names;
    }

    /** 一份 yml 的属性源（与启动时同一条 Spring 加载路径） */
    private List<EnumerablePropertySource<?>> sources(Path yml) throws IOException {
        List<EnumerablePropertySource<?>> sources = new ArrayList<>();
        for (PropertySource<?> loaded :
                new YamlPropertySourceLoader().load(yml.getFileName().toString(), resource(yml))) {
            assertTrue(loaded instanceof EnumerablePropertySource, () -> "加载器返回的属性源不可枚举: " + yml);
            sources.add((EnumerablePropertySource<?>) loaded);
        }
        return sources;
    }

    /** 资源形态：读文本、替换 Maven 资源过滤占位符（裸 `@name@` 不是合法 YAML 标量），落临时文件后交给加载器 */
    private Resource resource(Path yml) throws IOException {
        String text = Files.readString(yml, StandardCharsets.UTF_8);
        Path plain = tmp.resolve(yml.getFileName().toString());
        Files.writeString(plain, text.replaceAll("@[^@\\s]+@", "placeholder"), StandardCharsets.UTF_8);
        return new FileSystemResource(plain);
    }

    /** 驼峰字段名 → yml 的 kebab-case 键名 */
    private String camelToKebab(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }
}
