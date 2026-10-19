package com.workbuddy.filecenter.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.common.domain.entity.WbFileObject;
import com.workbuddy.filecenter.mapper.WbFileObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件档案数据访问单测。
 *
 * <p>守护两条口径：按 fileId 取单行（不存在为 null）、批量取对空入参短路且按 IN 查询。
 */
class WbFileObjectDbServiceImplTest {

    private WbFileObjectMapper mapper;

    private WbFileObjectDbServiceImpl dbService;

    /**
     * 初始化实体元信息（Lambda 条件拼装需要）。
     */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WbFileObject.class);
    }

    /**
     * 每个用例重建服务并注入 Mapper 替身。
     */
    @BeforeEach
    void setUp() {
        mapper = mock(WbFileObjectMapper.class);
        dbService = new WbFileObjectDbServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(dbService, "baseMapper", mapper);
    }

    /**
     * 按 fileId 取行：命中返回该行，未命中返回 null。
     *
     * <p>守护 {@code getByFileId}：恒 null 会让档案永远查不到。
     */
    @Test
    void getByFileIdShouldReturnRowOrNull() {
        WbFileObject row = new WbFileObject();
        row.setId(1L);
        org.mockito.Mockito.lenient().when(mapper.selectOne(any())).thenReturn(row);
        assertEquals(1L, dbService.getByFileId("f1").getId());

        org.mockito.Mockito.lenient().when(mapper.selectOne(any())).thenReturn(null);
        assertNull(dbService.getByFileId("f1"));
    }

    /**
     * 批量取档案：空入参短路不查库，非空返回列表。
     *
     * <p>守护 {@code listByFileIds} 的空入参兜底与结果口径。
     */
    @Test
    void listByFileIdsShouldShortCircuitEmptyAndReturnRows() {
        assertTrue(dbService.listByFileIds(null).isEmpty());
        assertTrue(dbService.listByFileIds(List.of()).isEmpty());
        verify(mapper, org.mockito.Mockito.never()).selectList(any());

        WbFileObject row = new WbFileObject();
        row.setFileId("f1");
        when(mapper.selectList(any())).thenReturn(List.of(row));

        assertEquals(1, dbService.listByFileIds(List.of("f1")).size());
        ArgumentCaptor<Wrapper<WbFileObject>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("file_id"), captor.getValue().getSqlSegment());
    }
}
