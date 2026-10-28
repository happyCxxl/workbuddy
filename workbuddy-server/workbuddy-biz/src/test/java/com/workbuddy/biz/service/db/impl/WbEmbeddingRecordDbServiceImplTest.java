package com.workbuddy.biz.service.db.impl;

import com.workbuddy.biz.mapper.WbEmbeddingRecordMapper;
import com.workbuddy.common.domain.entity.WbEmbeddingRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 向量记录数据访问单测。
 *
 * <p>守护 {@code listByEmbeddingSetId} 的返回口径：恒空列表会让向量段详情永远空白。
 */
@ExtendWith(MockitoExtension.class)
class WbEmbeddingRecordDbServiceImplTest {

    @Mock
    private WbEmbeddingRecordMapper mapper;

    private WbEmbeddingRecordDbServiceImpl impl;

    /**
     * 每个用例重建被测服务并注入 Mapper 替身。
     */
    @BeforeEach
    void setUp() {
        impl = new WbEmbeddingRecordDbServiceImpl();
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);
    }

    /**
     * 按向量集合取记录：命中返回真实列表。
     *
     * <p>守护 {@code listByEmbeddingSetId} 的返回：恒空会让详情丢记录。
     */
    @Test
    void listByEmbeddingSetIdShouldReturnRows() {
        WbEmbeddingRecord row = new WbEmbeddingRecord();
        row.setId(4L);
        when(mapper.selectList(any())).thenReturn(List.of(row));

        assertEquals(1, impl.listByEmbeddingSetId(3L).size());
    }

    /**
     * 空集合：查询无记录时返回空列表（不是 null）。
     *
     * <p>守护 {@code listByEmbeddingSetId} 的空结果口径。
     */
    @Test
    void listByEmbeddingSetIdShouldReturnEmptyWhenNoRows() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertTrue(impl.listByEmbeddingSetId(3L).isEmpty());
    }
}
