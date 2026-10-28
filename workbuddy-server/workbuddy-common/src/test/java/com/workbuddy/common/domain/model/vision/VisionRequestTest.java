package com.workbuddy.common.domain.model.vision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视觉请求：字节与引用二选一的判定，以及字节清理。
 */
class VisionRequestTest {

    /** 无字节、有字节、清理后三种状态（{@code shouldDetectBytes}）。 */
    @Test
    void shouldDetectBytes() {
        VisionRequest request = new VisionRequest();
        assertFalse(request.hasBytes());

        request.setImageBytes(new byte[]{1, 2});
        assertTrue(request.hasBytes());

        request.clearBytes();
        assertFalse(request.hasBytes());
    }
}
