package com.workbuddy.worker.testkit;

import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.embedding.EmbedProperties;
import com.workbuddy.worker.input.FileValidationProperties;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.preprocessing.PreprocessProperties;
import com.workbuddy.worker.structure.StructureProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试作用域的环节配置夹具：按配置面的真实取值构造各配置类的实例（不启 Spring 容器，走同一套 YAML 加载与绑定）。
 *
 * <p>配置面集中在 knowledge-biz 的 `config/`（`application.yml` 的 `spring.config.import` 清单载入），
 * 本模块不复制副本，因此夹具按仓库目录结构定位那一份文件：测试工作目录为模块根时向上取一级即可；
 * 布局变动时这里第一个失败（报错带期望的绝对路径）。
 *
 * <p>每次调用返回新实例，用例可继续 set 覆盖个别字段；同一文件的解析结果按路径缓存，不重复读盘。
 */
public final class PropertiesFixtures {

    /** 配置面根目录（相对模块根向上取一级） */
    private static final Path CONFIG_ROOT = Path.of("..", "workbuddy-biz", "src", "main", "resources", "config");

    /** 已解析的配置文件（键 → 值的拍平结果） */
    private static final Map<Path, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private PropertiesFixtures() {
    }

    /**
     * 解析阈值基线（`knowledge.parse`）。
     *
     * @return 按配置面取值构造的实例
     */
    public static ParseProperties parse() {
        return bind(ParseProperties.class, "knowledge.parse", CONFIG_ROOT.resolve("knowledge/knowledge-parse.yml"));
    }

    /**
     * 组装阈值基线（`knowledge.structure`）。
     *
     * @return 按配置面取值构造的实例
     */
    public static StructureProperties structure() {
        return bind(StructureProperties.class, "knowledge.structure",
                CONFIG_ROOT.resolve("knowledge/knowledge-structure.yml"));
    }

    /**
     * 预处理阈值基线（`knowledge.preprocess`）。
     *
     * @return 按配置面取值构造的实例
     */
    public static PreprocessProperties preprocess() {
        return bind(PreprocessProperties.class, "knowledge.preprocess",
                CONFIG_ROOT.resolve("knowledge/knowledge-preprocess.yml"));
    }

    /**
     * 切片参数基线（`knowledge.chunk`）。
     *
     * @return 按配置面取值构造的实例
     */
    public static ChunkProperties chunk() {
        return bind(ChunkProperties.class, "knowledge.chunk", CONFIG_ROOT.resolve("knowledge/knowledge-chunk.yml"));
    }

    /**
     * 向量化参数基线（`knowledge.embed`）。
     *
     * @return 按配置面取值构造的实例
     */
    public static EmbedProperties embed() {
        return bind(EmbedProperties.class, "knowledge.embed", CONFIG_ROOT.resolve("knowledge/knowledge-embed.yml"));
    }

    /**
     * 文件校验参数基线（`knowledge.file`）。
     *
     * @return 按配置面取值构造的实例
     */
    public static FileValidationProperties fileValidation() {
        return bind(FileValidationProperties.class, "knowledge.file", CONFIG_ROOT.resolve("workbuddy-file.yml"));
    }

    /** 绑定一个前缀：与启动时同一条 YAML 加载 + Binder 路径 */
    private static <T> T bind(Class<T> type, String prefix, Path yml) {
        return new Binder(new MapConfigurationPropertySource(cached(yml)))
                .bind(prefix, type)
                .orElseThrow(() -> new IllegalStateException("配置绑定失败: "
                        + yml.toAbsolutePath().normalize() + " 前缀 " + prefix
                        + "（类型 " + type.getSimpleName() + "）"));
    }

    /** 一份配置文件的拍平键值（按路径缓存） */
    private static Map<String, String> cached(Path yml) {
        return CACHE.computeIfAbsent(yml, PropertiesFixtures::load);
    }

    /** 读一份 yml 并拍平成点分键（加载器已把嵌套段拍平） */
    private static Map<String, String> load(Path yml) {
        Path absolute = yml.toAbsolutePath().normalize();
        if (!Files.exists(absolute)) {
            throw new IllegalStateException("找不到环节配置文件: " + absolute
                    + "（夹具按模块根定位配置面，测试工作目录须为模块根）");
        }
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (PropertySource<?> source : new YamlPropertySourceLoader()
                    .load(absolute.getFileName().toString(), new FileSystemResource(absolute))) {
                if (source instanceof EnumerablePropertySource<?> enumerable) {
                    for (String name : enumerable.getPropertyNames()) {
                        values.put(name, String.valueOf(enumerable.getProperty(name)));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取环节配置文件失败: " + absolute, e);
        }
        return Map.copyOf(values);
    }
}
