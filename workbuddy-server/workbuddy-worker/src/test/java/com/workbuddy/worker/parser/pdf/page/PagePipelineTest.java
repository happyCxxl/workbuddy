package com.workbuddy.worker.parser.pdf.page;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 页面流水线启动校验：重名与缺失依赖必须在启动期失败，不能等到运行期。
 */
class PagePipelineTest {

    /** 校验点：{@code PageStep#name()} 重名时，{@code PagePipeline} 构造期直接失败。 */
    @Test
    void shouldRejectDuplicateStepNames() {
        PageStep first = step("same", Set.of());
        PageStep second = step("same", Set.of());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new PagePipeline(List.of(first, second)));

        assertTrue(error.getMessage().contains("重复页面步骤"));
    }

    /** 校验点：{@code PageStep#requires()} 指向的步骤缺失时，{@code PagePipeline} 构造期直接失败。 */
    @Test
    void shouldRejectMissingDependency() {
        PageStep step = step("render", Set.of("classify"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new PagePipeline(List.of(step)));

        assertTrue(error.getMessage().contains("缺少依赖"));
    }

    /** 测试用空步骤：只提供名称与依赖，apply 不做事。 */
    private static PageStep step(String name, Set<String> requires) {
        return new PageStep() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Set<String> requires() {
                return requires;
            }

            @Override
            public void apply(PageContext context) {
            }
        };
    }
}
