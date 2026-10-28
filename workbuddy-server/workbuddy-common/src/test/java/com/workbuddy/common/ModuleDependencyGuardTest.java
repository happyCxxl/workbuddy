package com.workbuddy.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 依赖方向守卫：common 是最底层，不得依赖任何 workbuddy-* 模块。
 *
 * <p>随 {@code mvn verify} 一起执行，零新增依赖。
 */
class ModuleDependencyGuardTest {

    /** 依赖声明块：父工程坐标写在 {@code <parent>} 里，不属于模块依赖，不参与判定 */
    private static final Pattern DEPENDENCIES = Pattern.compile("<dependencies>([\\s\\S]*?)</dependencies>");

    /** 匹配 artifactId；只在依赖块内取，避免把父工程与项目坐标算成依赖 */
    private static final Pattern MODULE_ARTIFACT = Pattern.compile("<artifactId>(workbuddy-[a-z-]+)</artifactId>");

    /** 本模块自身：出现在项目坐标处，允许存在；若出现在依赖块内即自依赖 */
    private static final String SELF = "workbuddy-common";

    /**
     * 守护 {@code commonShouldNotDependOnAnyModule}：该场景行为改动即失败。
     */
    @Test
    void commonShouldNotDependOnAnyModule() throws IOException {
        // surefire 的工作目录是模块根目录，故这里读到的是 workbuddy-common/pom.xml
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);

        List<String> modules = declaredModules(pom);

        assertTrue(modules.isEmpty(),
                "common 是最底层模块，不得依赖任何 workbuddy-* 模块，实际发现：" + modules);
    }

    /**
     * 守护 {@code guardShouldSeeTheModuleItself}：该场景行为改动即失败。
     */
    @Test
    void guardShouldSeeTheModuleItself() throws IOException {
        // 反向确认解析逻辑没写坏：项目坐标处必然出现自身 artifactId
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);

        Matcher matcher = MODULE_ARTIFACT.matcher(pom);
        long matched = matcher.results().filter(match -> SELF.equals(match.group(1))).count();

        assertEquals(1L, matched, "未能在 pom.xml 中解析到自身 artifactId，守卫逻辑需要修正");
    }

    /**
     * 取 {@code <dependencies>} 块里声明的 workbuddy-* artifactId。
     *
     * @param pom 模块 pom 的全文
     * @return 依赖块里的模块 artifactId 清单（按声明顺序，可能为空）
     */
    private static List<String> declaredModules(String pom) {
        return DEPENDENCIES.matcher(pom).results()
                .flatMap(block -> MODULE_ARTIFACT.matcher(block.group(1)).results())
                .map(match -> match.group(1))
                .toList();
    }
}
