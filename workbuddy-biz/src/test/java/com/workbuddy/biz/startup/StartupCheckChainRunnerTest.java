package com.workbuddy.biz.startup;

import com.workbuddy.common.startup.StartupCheck;
import com.workbuddy.common.startup.StartupCheckChain;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动校验链的推进口径：按 order 排序后逐项执行、失败即止且原异常原样抛出、不推进即短路。
 *
 * @author cxxl
 */
class StartupCheckChainRunnerTest {

    /** 执行轨迹（每个假校验项被调用时记名） */
    private final List<String> calls = new ArrayList<>();

    @Test
    void shouldRunChecksInOrder() {
        new StartupCheckChainRunner(List.of(
                fake("c", StartupCheck.ORDER_ASSEMBLY, StartupCheckChain::proceed),
                fake("a", StartupCheck.ORDER_PARAMETERS, StartupCheckChain::proceed),
                fake("b", StartupCheck.ORDER_CREDENTIALS, StartupCheckChain::proceed)))
                .run(null);

        assertEquals(List.of("a", "b", "c"), calls);
    }

    @Test
    void shouldKeepInputOrderWhenOrderEqual() {
        new StartupCheckChainRunner(List.of(
                fake("first", StartupCheck.ORDER_PARAMETERS, StartupCheckChain::proceed),
                fake("second", StartupCheck.ORDER_PARAMETERS, StartupCheckChain::proceed)))
                .run(null);

        assertEquals(List.of("first", "second"), calls);
    }

    @Test
    void shouldStopAtFirstFailureAndRethrowOriginal() {
        IllegalStateException failure = new IllegalStateException("坏配置");

        RuntimeException thrown = assertThrows(IllegalStateException.class,
                () -> new StartupCheckChainRunner(List.of(
                        fake("a", StartupCheck.ORDER_PARAMETERS, StartupCheckChain::proceed),
                        fake("b", StartupCheck.ORDER_CREDENTIALS, next -> {
                            throw failure;
                        }),
                        fake("c", StartupCheck.ORDER_ASSEMBLY, StartupCheckChain::proceed)))
                        .run(null));

        assertSame(failure, thrown, "原异常须原样抛出，错误码与文案都不变");
        assertEquals(List.of("a", "b"), calls, "失败即止，链上后续校验项不执行");
    }

    @Test
    void shouldShortCircuitWhenCheckDoesNotProceed() {
        new StartupCheckChainRunner(List.of(
                fake("a", StartupCheck.ORDER_PARAMETERS, next -> {
                }),
                fake("b", StartupCheck.ORDER_CREDENTIALS, StartupCheckChain::proceed)))
                .run(null);

        assertEquals(List.of("a"), calls, "不调用 proceed 即中止链上后续校验项");
    }

    @Test
    void shouldTolerateEmptyCheckList() {
        assertDoesNotThrow(() -> new StartupCheckChainRunner(List.of()).run(null));
        assertTrue(calls.isEmpty());
    }

    /** 假校验项：记名后走给定动作（推进 / 抛异常 / 短路） */
    private StartupCheck fake(String label, int order, Consumer<StartupCheckChain> body) {
        return new StartupCheck() {

            @Override
            public int order() {
                return order;
            }

            @Override
            public String name() {
                return label;
            }

            @Override
            public void check(StartupCheckChain next) {
                calls.add(label);
                body.accept(next);
            }
        };
    }
}
