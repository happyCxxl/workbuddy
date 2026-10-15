package com.workbuddy.biz.startup;

import com.workbuddy.common.startup.StartupCheck;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动校验接线守卫：全仓库只允许校验链驱动方实现 {@code ApplicationRunner} / {@code CommandLineRunner}，
 * 启动校验一律实现 {@link StartupCheck} 并由链按 order 串起。
 *
 * @author cxxl
 */
class StartupCheckWiringGuardTest {

    /** surefire 的工作目录是模块根目录，仓库根在上一级 */
    private static final Path REPO = Path.of("..");

    /** 类声明里的启动钩子接口 */
    private static final Pattern RUNNER_IMPL =
            Pattern.compile("implements\\s+[^{;]*\\b(ApplicationRunner|CommandLineRunner)\\b");

    @Test
    void onlyChainRunnerShouldImplementApplicationRunner() throws IOException {
        List<String> owners = new ArrayList<>();
        int scanned = 0;
        for (Path file : mainSources()) {
            scanned++;
            String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (RUNNER_IMPL.matcher(code).find()) {
                owners.add(file.getFileName().toString());
            }
        }

        assertTrue(scanned > 0, () -> "未扫到任何源码，先检查目录解析: " + REPO.toAbsolutePath());
        assertEquals(List.of("StartupCheckChainRunner.java"), owners,
                "启动钩子只允许校验链驱动方实现；新增启动校验请实现 StartupCheck 并标 @Component，"
                        + "不要另起一个裸 Runner（否则它绕过 order 排序与统一日志）");
    }

    /** 各模块 `src/main/java` 下的全部源码 */
    private List<Path> mainSources() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path module : moduleDirs()) {
            Path src = module.resolve(Path.of("src", "main", "java"));
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(src)) {
                files.addAll(walk.filter(path -> path.getFileName().toString().endsWith(".java")).toList());
            }
        }
        return files;
    }

    /** 仓库根下的 workbuddy 各模块目录 */
    private List<Path> moduleDirs() throws IOException {
        try (Stream<Path> list = Files.list(REPO)) {
            return list.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("workbuddy-"))
                    .toList();
        }
    }

    /** 去掉块注释与行注释：只在代码上匹配类声明，注释里的字样不算 */
    private String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }
}
