package com.workbuddy.biz.config;

import com.workbuddy.worker.parser.ParseProperties;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析阈值配置口径：配置面（主文件与 `spring.config.import` 清单里的环节文件合成一本）的 `knowledge.parse`
 * 覆盖 ParseProperties 的全部字段，且初始值与代码默认值一致（配置面与代码面不许偏）。
 *
 * @author cxxl
 */
class ParsePropertiesConfigTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");

    private static final Path MAIN = RESOURCES.resolve("application.yml");

    private static final String CLASSPATH = "classpath:";

    /** 可选导入前缀：这类文件缺失即跳过（本机密钥覆盖文件被 gitignore，别的机器上没有） */
    private static final String OPTIONAL = "optional:";

    @Test
    void applicationYmlShouldCoverAllParseProperties() throws Exception {
        Map<String, Object> parse = parseBlock();
        assertPropertiesCovered(parse, new ParseProperties());
    }

    /** 配置项逐个对上代码默认值，且不许缺项 */
    private void assertPropertiesCovered(Map<String, Object> block, Object defaults) throws Exception {
        for (Field field : defaults.getClass().getDeclaredFields()) {
            String key = camelToKebab(field.getName());
            assertTrue(block.containsKey(key), () -> "配置面缺配置项: " + key);
            field.setAccessible(true);
            assertEquals(String.valueOf(field.get(defaults)), String.valueOf(block.get(key)),
                    () -> "配置项与代码默认值不一致: " + key);
        }
    }

    /** 取嵌套段（缺失时返回空表，由缺项断言报出来） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> nested(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    /** `knowledge.parse` 段（取自配置面） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBlock() throws Exception {
        Map<String, Object> root = configFace();
        Map<String, Object> knowledge = (Map<String, Object>) root.get("knowledge");
        assertTrue(knowledge != null && knowledge.containsKey("parse"), () -> "配置面缺 knowledge.parse 段");
        return (Map<String, Object>) knowledge.get("parse");
    }

    /** 配置面：主文件按 `spring.config.import` 清单逐个并入（后导入覆盖先导入，与 Spring 同序） */
    private Map<String, Object> configFace() throws Exception {
        Map<String, Object> face = loadYaml(MAIN);
        List<String> imports = importLocations(face);
        assertTrue(!imports.isEmpty(), () -> "主文件缺 spring.config.import 清单: " + MAIN.toAbsolutePath());
        for (String location : imports) {
            boolean optional = location.startsWith(OPTIONAL);
            String resource = optional ? location.substring(OPTIONAL.length()) : location;
            Path file = RESOURCES.resolve(resource.replace(CLASSPATH, ""));
            if (optional && !Files.exists(file)) {
                continue;
            }
            assertTrue(Files.exists(file), () -> "导入清单里的文件不存在: " + location);
            merge(face, loadYaml(file));
        }
        return face;
    }

    /** `spring.config.import` 清单（单个与列表两种写法都收） */
    @SuppressWarnings("unchecked")
    private List<String> importLocations(Map<String, Object> root) {
        Map<String, Object> spring = (Map<String, Object>) root.get("spring");
        Object declared = spring == null ? null : nested(spring, "config").get("import");
        if (declared instanceof List) {
            return (List<String>) declared;
        }
        return declared instanceof String ? List.of((String) declared) : List.of();
    }

    /** 逐层并入：同键的映射递归合并，叶子取后导入的值 */
    @SuppressWarnings("unchecked")
    private void merge(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object existing = target.get(entry.getKey());
            if (existing instanceof Map && entry.getValue() instanceof Map) {
                merge((Map<String, Object>) existing, (Map<String, Object>) entry.getValue());
            } else {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    /** 读一份 yml（读文本时先替换 Maven 资源过滤占位符，SnakeYAML 不接受裸 @；整份都是注释时按空文档收） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml(Path yml) throws Exception {
        assertTrue(Files.exists(yml), () -> "找不到配置文件: " + yml.toAbsolutePath());
        String text = Files.readString(yml, StandardCharsets.UTF_8).replaceAll("@[^@\\s]+@", "placeholder");
        Map<String, Object> root = new Yaml().load(text);
        return root == null ? Map.of() : root;
    }

    /** 驼峰字段名 → yml 的 kebab-case 键名 */
    private String camelToKebab(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
    }
}
