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

    /** 匹配 pom 里声明的模块 artifactId */
    private static final Pattern MODULE_ARTIFACT = Pattern.compile("<artifactId>(workbuddy-[a-z-]+)</artifactId>");

    /** 本模块自身，允许出现（parent 的 artifactId 是 workbuddy，不带短横线，不会命中上面的模式） */
    private static final String SELF = "workbuddy-common";

    /**
     * 守护 {@code commonShouldNotDependOnAnyModule}：该场景行为改动即失败。
     */
    @Test
    void commonShouldNotDependOnAnyModule() throws IOException {
        // surefire 的工作目录是模块根目录，故这里读到的是 workbuddy-common/pom.xml
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);

        List<String> modules = MODULE_ARTIFACT.matcher(pom).results()
                .map(match -> match.group(1))
                .filter(artifactId -> !SELF.equals(artifactId))
                .toList();

        assertTrue(modules.isEmpty(),
                "common 是最底层模块，不得依赖任何 workbuddy-* 模块，实际发现：" + modules);
    }

    /**
     * 守护 {@code guardShouldSeeTheModuleItself}：该场景行为改动即失败。
     */
    @Test
    void guardShouldSeeTheModuleItself() throws IOException {
        // 反向确认解析逻辑没写坏：pom 里必然出现自身 artifactId
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);

        Matcher matcher = MODULE_ARTIFACT.matcher(pom);
        long matched = matcher.results().filter(match -> SELF.equals(match.group(1))).count();

        assertEquals(1L, matched, "未能在 pom.xml 中解析到自身 artifactId，守卫逻辑需要修正");
    }
}
