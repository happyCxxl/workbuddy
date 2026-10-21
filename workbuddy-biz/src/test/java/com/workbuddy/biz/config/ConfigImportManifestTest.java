package com.workbuddy.biz.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 配置导入清单口径：主文件 `spring.config.import` 逐行列出的环节文件必须存在、能被 Spring 的 YAML 加载器读出为合法 YAML，
 * 且键落在文件名对应的域内（文件名与内容域一致）。
 *
 * <p>清单条目允许 `optional:` 前缀：这类文件缺失即跳过（本机密钥覆盖文件被 gitignore，别的机器上没有），
 * 存在时才做存在性与 YAML 检查，且不要求登记内容域；`config/` 下的环节文件仍必须存在并登记内容域。
 *
 * <p>新增环节：平台级配置加 `config/workbuddy-<域>.yml`，知识库功能专属配置加 `config/knowledge/knowledge-<域>.yml`，
 * 在 `application.yml` 的清单里加一行，在本用例的 `DOMAINS` 里登记该文件的键前缀。
 */
class ConfigImportManifestTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");

    private static final Path MAIN = RESOURCES.resolve("application.yml");

    private static final Path CONFIG_DIR = RESOURCES.resolve("config");

    /** 知识库功能专属配置的子目录 */
    private static final Path KNOWLEDGE_DIR = CONFIG_DIR.resolve("knowledge");

    private static final String IMPORT = "spring.config.import";

    private static final String CLASSPATH = "classpath:";

    /** 可选导入前缀：这类文件允许缺失（本机密钥覆盖文件被 gitignore，别的机器上没有） */
    private static final String OPTIONAL = "optional:";

    /** 环节配置文件名：平台级 workbuddy-<域>.yml 与知识库功能专属 knowledge-<域>.yml 两种 */
    private static final String CONFIG_FILE_PATTERN = "(workbuddy|knowledge)-.*\\.yml";

    /** 允许整份注释、零属性的环节文件（厂商与路由参数按需再写进来） */
    private static final String EMPTY_ALLOWED = "workbuddy-model.yml";

    /** 文件名 → 允许出现的键前缀（登记的前缀都要在该文件里出现，{@link #EMPTY_ALLOWED} 除外） */
    private static final Map<String, List<String>> DOMAINS = new LinkedHashMap<>();

    static {
        DOMAINS.put("knowledge-parse.yml", List.of("knowledge.parse."));
        DOMAINS.put("knowledge-structure.yml", List.of("knowledge.structure."));
        DOMAINS.put("knowledge-preprocess.yml", List.of("knowledge.preprocess."));
        DOMAINS.put("knowledge-chunk.yml", List.of("knowledge.chunk."));
        DOMAINS.put("knowledge-embed.yml", List.of("knowledge.embed."));
        DOMAINS.put("knowledge-milvus.yml", List.of("knowledge.vector.milvus."));
        DOMAINS.put("workbuddy-auth.yml", List.of("knowledge.auth."));
        DOMAINS.put("workbuddy-file.yml", List.of("knowledge.file."));
        DOMAINS.put("knowledge-task.yml", List.of("knowledge.task."));
        DOMAINS.put("knowledge-retrieval.yml", List.of("retrieval."));
        DOMAINS.put(EMPTY_ALLOWED, List.of("workbuddy.model."));
    }

    @TempDir
    Path tmp;

    /**
     * 守护 {@code importedFilesShouldExist}：该场景行为改动即失败。
     */
    @Test
    void importedFilesShouldExist() throws Exception {
        List<ImportEntry> entries = importEntries();
        assertTrue(!entries.isEmpty(), () -> "主文件缺 spring.config.import 清单: " + MAIN.toAbsolutePath()
                + "；修法：在 spring.config 下补 config.import 段。");
        List<String> declared = new ArrayList<>();
        for (ImportEntry entry : entries) {
            assertTrue(entry.resource().startsWith(CLASSPATH), () -> "导入清单的条目不是 classpath: 资源: "
                    + entry.location() + "（主文件 " + MAIN + "）；修法：写成 classpath:config/workbuddy-<域>.yml、"
                    + "classpath:config/knowledge/knowledge-<域>.yml 或 optional:classpath:<本机覆盖文件>。");
            Path file = resolve(entry.resource());
            if (entry.optional() && !Files.exists(file)) {
                continue;
            }
            assertTrue(Files.exists(file), () -> "导入清单里的文件不存在: " + entry.location() + "（实际查找 "
                    + file.toAbsolutePath() + "）；修法：补上该文件，或改清单那一行，或删掉多余的一行；"
                    + "本机覆盖文件加 optional: 前缀即可允许缺失。");
            declared.add(file.getFileName().toString());
        }
        assertTrue(Files.isDirectory(CONFIG_DIR), () -> "环节文件目录不存在: " + CONFIG_DIR.toAbsolutePath()
                + "；修法：补 config 目录与其中的环节文件。");
        for (Path file : configFiles()) {
            String name = file.getFileName().toString();
            assertTrue(declared.contains(name), () -> "环节文件没进导入清单: " + CONFIG_DIR.relativize(file)
                    + "（主文件 " + MAIN + " 的 spring.config.import）；修法：在该清单里加一行 classpath:config/"
                    + CONFIG_DIR.relativize(file).toString().replace('\\', '/') + "。");
        }
    }

    /** config 目录与 knowledge 子目录下的环节配置文件（两级，不递归更深） */
    private List<Path> configFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path dir : List.of(CONFIG_DIR, KNOWLEDGE_DIR)) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> list = Files.list(dir)) {
                files.addAll(list.filter(path -> path.getFileName().toString().matches(CONFIG_FILE_PATTERN)).toList());
            }
        }
        return files;
    }

    /**
     * 守护 {@code importedFilesShouldBeValidYaml}：该场景行为改动即失败。
     */
    @Test
    void importedFilesShouldBeValidYaml() throws Exception {
        for (ImportEntry entry : importEntries()) {
            Path file = resolve(entry.resource());
            if (!Files.exists(file)) {
                assertTrue(entry.optional(), () -> "导入清单里的文件不存在: " + entry.location() + "（实际查找 "
                        + file.toAbsolutePath() + "）；修法：补上该文件，或改清单那一行，或删掉多余的一行。");
                continue;
            }
            try {
                new YamlPropertySourceLoader().load(file.getFileName().toString(), resource(file));
            } catch (IOException | RuntimeException e) {
                fail("导入文件不是合法 YAML: " + entry.location() + "（" + file.toAbsolutePath() + "）: " + e.getMessage()
                        + "；修法：按 YAML 语法修正该文件。");
            }
        }
    }

    /**
     * 守护 {@code importedFilesShouldMatchTheirDomain}：该场景行为改动即失败。
     */
    @Test
    void importedFilesShouldMatchTheirDomain() throws Exception {
        for (ImportEntry entry : importEntries()) {
            Path file = resolve(entry.resource());
            if (!Files.exists(file)) {
                assertTrue(entry.optional(), () -> "导入清单里的文件不存在: " + entry.location());
                continue;
            }
            String name = file.getFileName().toString();
            List<String> prefixes = DOMAINS.get(name);
            if (prefixes == null) {
                assertTrue(entry.optional(), () -> "新环节文件未登记内容域: " + entry.location() + "；修法：在本用例的 DOMAINS 里登记 "
                        + name + " 允许的键前缀，并在 application.yml 的 spring.config.import 清单里加一行。");
                continue;
            }
            List<String> keys = propertyNames(file);
            for (String key : keys) {
                assertTrue(prefixes.stream().anyMatch(key::startsWith), () -> "键不在文件名对应的域内: " + entry.location()
                        + " 的 " + key + "（该文件只允许 " + String.join(" / ", prefixes)
                        + "）；修法：把键搬到对应域的文件，或在本用例的 DOMAINS 里改该文件的前缀。");
            }
            if (EMPTY_ALLOWED.equals(name)) {
                continue;
            }
            assertTrue(!keys.isEmpty(), () -> "导入文件没有任何属性: " + entry.location() + "（整份注释只允许出现在 " + EMPTY_ALLOWED
                    + "）；修法：把该环节的键写进该文件。");
            for (String prefix : prefixes) {
                assertTrue(keys.stream().anyMatch(key -> key.startsWith(prefix)), () -> "文件名声明的域在文件里没有键: "
                        + entry.location() + " 缺 " + prefix + "；修法：补该域的键，或在本用例的 DOMAINS 里删掉这个前缀。");
            }
        }
    }

    /** 清单条目：去掉可选前缀后的 classpath 资源，与该文件是否允许缺失 */
    private record ImportEntry(String location, String resource, boolean optional) {
    }

    /** 主文件清单里的条目（保持清单顺序），可选前缀在此剥离 */
    private List<ImportEntry> importEntries() throws IOException {
        List<ImportEntry> entries = new ArrayList<>();
        for (String location : importLocations()) {
            boolean optional = location.startsWith(OPTIONAL);
            String resource = optional ? location.substring(OPTIONAL.length()) : location;
            entries.add(new ImportEntry(location, resource, optional));
        }
        return entries;
    }

    /** 主文件清单里的原始条目（保持清单顺序，可选前缀未剥离） */
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
        assertTrue(Files.exists(yml), () -> "找不到配置文件: " + yml.toAbsolutePath());
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

    /** `classpath:` 资源 → 源码树里的文件 */
    private Path resolve(String location) {
        return RESOURCES.resolve(location.replace(CLASSPATH, ""));
    }
}
