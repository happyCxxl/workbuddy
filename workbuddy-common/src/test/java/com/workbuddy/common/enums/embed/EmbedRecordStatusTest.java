package com.workbuddy.common.enums.embed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 向量记录状态单测。
 *
 * <p>守护 {@code of} 的状态名解析：账本回溯（复用命中判定）按状态名比对。
 */
class EmbedRecordStatusTest {

    /**
     * 每个状态都能按名字解析回来，且说明非空。
     *
     * <p>守护 {@code of} 与 {@code desc}：状态名解析错即失败（复用判定失真）。
     */
    @Test
    void ofShouldResolveEveryName() {
        for (EmbedRecordStatus status : EmbedRecordStatus.values()) {
            assertEquals(status, EmbedRecordStatus.of(status.name()), () -> status + " 按名字解析失败");
            assertFalse(status.desc().isBlank(), () -> status + " 缺状态说明");
        }
        assertNull(EmbedRecordStatus.of("NO_SUCH_STATUS"));
    }
}
