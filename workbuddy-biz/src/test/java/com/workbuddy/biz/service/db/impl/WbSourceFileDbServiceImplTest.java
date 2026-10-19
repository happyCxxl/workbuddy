package com.workbuddy.biz.service.db.impl;

import com.workbuddy.biz.mapper.WbSourceFileMapper;
import com.workbuddy.common.domain.entity.WbSourceFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 来源文件数据访问单测。
 *
 * <p>守护三条口径：按 fileId 取行命中即返回、批量取档案对空入参短路（不落 SQL）、
 * 批量命中时返回真实列表；恒 null/恒空会让文件类型与来源信息整页缺失。
 */
@ExtendWith(MockitoExtension.class)
class WbSourceFileDbServiceImplTest {

    @Mock
    private WbSourceFileMapper mapper;

    private WbSourceFileDbServiceImpl impl;

    /**
     * 每个用例重建被测服务并注入 Mapper 替身。
     */
    @BeforeEach
    void setUp() {
        impl = new WbSourceFileDbServiceImpl();
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);
    }

    /**
     * 按 fileId 命中时返回档案行。
     *
     * <p>守护 {@code findByFileId} 的返回口径：恒 null 会让来源信息永远查不到。
     */
    @Test
    void findByFileIdShouldReturnHitRow() {
        WbSourceFile row = new WbSourceFile();
        row.setId(7L);
        row.setFileId("file-1");
        when(mapper.selectOne(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(row);

        assertSame(row, impl.findByFileId("file-1"));
    }

    /**
     * 按 fileId 未命中时返回 null（不是抛异常）。
     *
     * <p>守护 {@code findByFileId} 的缺失口径。
     */
    @Test
    void findByFileIdShouldReturnNullWhenAbsent() {
        when(mapper.selectOne(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(null);

        assertNull(impl.findByFileId("file-x"));
    }

    /**
     * 批量取档案：空入参短路不查库，非空返回命中列表。
     *
     * <p>守护 {@code listByFileIds} 的空入参兜底与返回口径。
     */
    @Test
    void listByFileIdsShouldShortCircuitEmptyAndReturnHitRows() {
        assertTrue(impl.listByFileIds(null).isEmpty());
        assertTrue(impl.listByFileIds(List.of()).isEmpty());
        verify(mapper, never()).selectList(any());

        WbSourceFile row = new WbSourceFile();
        row.setFileId("file-1");
        when(mapper.selectList(any())).thenReturn(List.of(row));

        assertEquals(1, impl.listByFileIds(List.of("file-1")).size());
    }
}
