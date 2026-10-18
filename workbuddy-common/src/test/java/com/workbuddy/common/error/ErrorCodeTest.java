package com.workbuddy.common.error;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.workbuddy.common.error.ErrorCode.MODEL_AUTH_FAILED;
import static com.workbuddy.common.error.ErrorCode.MODEL_BAD_REQUEST;
import static com.workbuddy.common.error.ErrorCode.MODEL_MODALITY_UNSUPPORTED;
import static com.workbuddy.common.error.ErrorCode.MODEL_NOT_FOUND;
import static com.workbuddy.common.error.ErrorCode.MODEL_OUTPUT_INVALID;
import static com.workbuddy.common.error.ErrorCode.MODEL_RATE_LIMITED;
import static com.workbuddy.common.error.ErrorCode.MODEL_SERVER_ERROR;
import static com.workbuddy.common.error.ErrorCode.MODEL_TIMEOUT;
import static com.workbuddy.common.error.ErrorCode.MODEL_UNREACHABLE;
import static com.workbuddy.common.error.ErrorCode.MODEL_VENDOR_UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错误码单测：模型调用分区的十个码值与文案、号段连续性与全仓唯一性。
 */
class ErrorCodeTest {

    /** 模型调用分区：未支持模态、未知厂商、鉴权失败、参数或内容被拒、模型不存在、限流、超时、服务端错误、网络不可达、输出结构校验失败 */
    private static final List<ErrorCode> MODEL_CODES = List.of(MODEL_MODALITY_UNSUPPORTED, MODEL_VENDOR_UNKNOWN,
            MODEL_AUTH_FAILED, MODEL_BAD_REQUEST, MODEL_NOT_FOUND, MODEL_RATE_LIMITED, MODEL_TIMEOUT, MODEL_SERVER_ERROR,
            MODEL_UNREACHABLE, MODEL_OUTPUT_INVALID);

    /** 与 MODEL_CODES 顺序一一对应的文案 */
    private static final List<String> MODEL_MESSAGES = List.of("模型未支持该模态", "模型厂商未配置", "模型鉴权失败",
            "模型请求参数或内容被拒", "模型不存在", "模型请求被限流", "模型请求超时", "模型服务端错误", "模型服务不可达",
            "模型输出结构校验失败");

    @Test
    void modelCodesShouldSitInOneContinuousBlock() {
        List<Integer> expected = new ArrayList<>();
        for (int code = 40460; code <= 40469; code++) {
            expected.add(code);
        }
        List<Integer> actual = new ArrayList<>();
        for (ErrorCode errorCode : MODEL_CODES) {
            actual.add(errorCode.getCode());
        }

        assertEquals(expected, actual);
        assertEquals(10, MODEL_CODES.size());
    }

    @Test
    void modelCodesShouldStayUniqueAgainstTheWholeRegistry() {
        Set<Integer> codes = new LinkedHashSet<>();
        for (ErrorCode errorCode : ErrorCode.values()) {
            codes.add(errorCode.getCode());
        }

        assertEquals(ErrorCode.values().length, codes.size());
    }

    @Test
    void modelCodeMessagesShouldMatchTheirPhrases() {
        List<String> actual = new ArrayList<>();
        for (ErrorCode errorCode : MODEL_CODES) {
            actual.add(errorCode.getMessage());
        }

        assertEquals(MODEL_MESSAGES, actual);
    }

    @Test
    void modelCodeMessagesShouldBeChinesePhrases() {
        for (ErrorCode errorCode : MODEL_CODES) {
            assertTrue(errorCode.getMessage().startsWith("模型"), errorCode.name());
        }
    }
}
