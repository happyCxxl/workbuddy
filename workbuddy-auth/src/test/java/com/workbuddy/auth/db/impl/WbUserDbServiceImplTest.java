package com.workbuddy.auth.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.auth.mapper.WbUserMapper;
import com.workbuddy.common.domain.entity.WbUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户数据访问单测。
 *
 * <p>守护四条查询口径：启用态按用户名取行、占用判定不分状态、条件分页只拼非空过滤、
 * 令牌版本按主键取单列；口径错会让停用账号登录或旧令牌继续有效。
 */
class WbUserDbServiceImplTest {

    private WbUserMapper mapper;

    private WbUserDbServiceImpl dbService;

    /**
     * 初始化实体元信息（Lambda 条件拼装需要）。
     */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WbUser.class);
    }

    /**
     * 每个用例重建服务并注入 Mapper 替身。
     */
    @BeforeEach
    void setUp() {
        mapper = mock(WbUserMapper.class);
        dbService = new WbUserDbServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(dbService, "baseMapper", mapper);
    }

    /**
     * 启用态查询：命中返回该行，未命中返回 null。
     *
     * <p>守护 {@code findActiveByUsername}：停用账号不能被当成命中。
     */
    @Test
    void findActiveByUsernameShouldReturnRowOrNull() {
        WbUser row = new WbUser();
        row.setId(1L);
        when(mapper.selectOne(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(row);

        assertEquals(1L, dbService.findActiveByUsername("alice").getId());

        when(mapper.selectOne(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(null);
        assertNull(dbService.findActiveByUsername("alice"));
    }

    /**
     * 占用判定：存在返回 true，不存在返回 false。
     *
     * <p>守护 {@code existsByUsername}：注册时漏判会撞唯一键。
     */
    @Test
    void existsByUsernameShouldReflectExistence() {
        when(mapper.exists(any())).thenReturn(true);
        assertTrue(dbService.existsByUsername("alice"));

        when(mapper.exists(any())).thenReturn(false);
        assertFalse(dbService.existsByUsername("alice"));
    }

    /**
     * 条件分页：非空条件进 SQL，空条件不拼；按 ID 倒序。
     *
     * <p>守护 {@code pageByCondition} 的条件拼装与排序。
     */
    @Test
    void pageByConditionShouldFilterOnlyPresentConditions() {
        Page<WbUser> page = new Page<>(1, 10);
        when(mapper.selectPage(any(), any())).thenReturn(page);

        dbService.pageByCondition(1, 10, "ali", "USER", 1);

        ArgumentCaptor<Wrapper<WbUser>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("username") && sql.contains("role") && sql.contains("status"), sql);
        assertTrue(sql.contains("ORDER BY"), sql);

        dbService.pageByCondition(1, 10, null, " ", null);
        verify(mapper, org.mockito.Mockito.times(2)).selectPage(any(), captor.capture());
        String bare = captor.getValue().getSqlSegment();
        assertFalse(bare.contains("username"), bare);
        assertFalse(bare.contains("role"), bare);
        assertFalse(bare.contains("status"), bare);
    }

    /**
     * 令牌版本：用户存在返回其版本，不存在返回 null。
     *
     * <p>守护 {@code findTokenVersion}：用户被删除后必须查不到版本，旧令牌随之失效。
     */
    @Test
    void findTokenVersionShouldReturnNullWhenUserMissing() {
        WbUser row = new WbUser();
        row.setTokenVersion(3);
        when(mapper.selectOne(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(row);
        assertEquals(3, dbService.findTokenVersion(1L));

        when(mapper.selectOne(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(null);
        assertNull(dbService.findTokenVersion(1L));
    }
}
