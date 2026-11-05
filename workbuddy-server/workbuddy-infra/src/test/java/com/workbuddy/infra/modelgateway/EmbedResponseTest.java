package com.workbuddy.infra.modelgateway;

import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量化网关响应直译解析：缺失/形态错误按 MODEL_SERVER_ERROR，正常数据按序直译。
 */
class EmbedResponseTest {

    /**
     * 缺 vectors 字段按 MODEL_SERVER_ERROR。
     */
    @Test
    void fromShouldFailWhenVectorsMissing() {
        WorkbuddyException error = assertThrows(WorkbuddyException.class, () -> EmbedResponse.from(Map.of()));
        assertEquals(ErrorCode.MODEL_SERVER_ERROR.getCode(), error.getCode());
    }

    /**
     * 行非数组按 MODEL_SERVER_ERROR。
     */
    @Test
    void fromShouldFailWhenRowNotList() {
        WorkbuddyException error = assertThrows(WorkbuddyException.class,
                () -> EmbedResponse.from(Map.of("vectors", List.of("a"))));
        assertEquals(ErrorCode.MODEL_SERVER_ERROR.getCode(), error.getCode());
    }

    /**
     * 值非数字按 MODEL_SERVER_ERROR。
     */
    @Test
    void fromShouldFailWhenValueNotNumber() {
        WorkbuddyException error = assertThrows(WorkbuddyException.class,
                () -> EmbedResponse.from(Map.of("vectors", List.of(List.of("a")))));
        assertEquals(ErrorCode.MODEL_SERVER_ERROR.getCode(), error.getCode());
    }

    /**
     * 空 vectors 与缺失 meta：空列表 + null meta。
     */
    @Test
    void fromShouldKeepEmptyAndNullMeta() {
        EmbedResponse response = EmbedResponse.from(Map.of("vectors", List.of()));
        assertTrue(response.vectors().isEmpty());
        assertNull(response.meta());
    }
}
