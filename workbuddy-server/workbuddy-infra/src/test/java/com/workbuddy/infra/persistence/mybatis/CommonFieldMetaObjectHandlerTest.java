package com.workbuddy.infra.persistence.mybatis;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.common.domain.base.BaseInfo;
import com.workbuddy.common.enums.base.DelFlag;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.SecurityUtil;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * 公共字段自动填充单测。
 *
 * <p>用真实实体（{@link BaseInfo}）承载元对象，守护填充口径：插入走严格填充（删除标记与留痕字段），
 * 更新走覆盖式（更新时间必须动、无认证上下文时 updateBy 保持原值）。
 */
class CommonFieldMetaObjectHandlerTest {

    private static final LocalDateTime OLD_TIME = LocalDateTime.of(2020, 1, 1, 0, 0);

    private MockedStatic<SecurityUtil> security;

    private CommonFieldMetaObjectHandler handler;

    /**
     * 注册实体的表信息，让严格填充能定位到带 fill 注解的字段。
     */
    @BeforeAll
    static void registerTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), BaseInfo.class);
    }

    /**
     * 每个用例重建填充器与登录上下文。
     */
    @BeforeEach
    void setUp() {
        security = mockStatic(SecurityUtil.class);
        handler = new CommonFieldMetaObjectHandler();
    }

    /**
     * 收尾关闭静态桩。
     */
    @AfterEach
    void tearDown() {
        security.close();
    }

    /**
     * 有登录上下文时插入填充写入删除标记、创建/更新人与时间。
     *
     * <p>守护 {@code insertFill}：漏填任一字段、或 delFlag 码值写错即失败。
     */
    @Test
    void insertFillShouldPopulateAuditFields() {
        security.when(SecurityUtil::getUser).thenReturn(principal("admin"));
        BaseInfo entity = new BaseInfo();

        handler.insertFill(metaOf(entity));

        assertEquals(DelFlag.NORMAL.getCode(), entity.getDelFlag());
        assertEquals("admin", entity.getCreateBy());
        assertEquals("admin", entity.getUpdateBy());
        assertNotNull(entity.getCreateTime());
        assertNotNull(entity.getUpdateTime());
    }

    /**
     * 无登录上下文时操作人字段留空，其余字段照填。
     *
     * <p>守护 {@code insertFill} 的空操作人兜底：把 null 操作人写进字段或抛空指针即失败。
     */
    @Test
    void insertFillShouldLeaveOperatorFieldsNullWithoutLogin() {
        security.when(SecurityUtil::getUser).thenReturn(null);
        BaseInfo entity = new BaseInfo();

        handler.insertFill(metaOf(entity));

        assertNull(entity.getCreateBy());
        assertNull(entity.getUpdateBy());
        assertEquals(DelFlag.NORMAL.getCode(), entity.getDelFlag());
        assertNotNull(entity.getCreateTime());
        assertNotNull(entity.getUpdateTime());
    }

    /**
     * 更新覆盖式填充：库里的旧 updateBy / updateTime 必须被覆盖。
     *
     * <p>守护 {@code updateFill} 的覆盖语义：走严格填充（旧值有值即不覆盖）即失败。
     */
    @Test
    void updateFillShouldOverwriteAuditFields() {
        security.when(SecurityUtil::getUser).thenReturn(principal("admin"));
        BaseInfo entity = new BaseInfo();
        entity.setUpdateBy("legacy");
        entity.setUpdateTime(OLD_TIME);

        handler.updateFill(metaOf(entity));

        assertEquals("admin", entity.getUpdateBy());
        assertNotEquals(OLD_TIME, entity.getUpdateTime());
        assertTrue(entity.getUpdateTime().isAfter(OLD_TIME));
    }

    /**
     * 无认证上下文时 updateBy 保持原值，更新时间仍然刷新。
     *
     * <p>守护 {@code updateFill} 的空操作人兜底：null 覆盖旧值、或连时间一起跳过即失败。
     */
    @Test
    void updateFillShouldKeepUpdateByWithoutLogin() {
        security.when(SecurityUtil::getUser).thenReturn(null);
        BaseInfo entity = new BaseInfo();
        entity.setUpdateBy("legacy");
        entity.setUpdateTime(OLD_TIME);

        handler.updateFill(metaOf(entity));

        assertEquals("legacy", entity.getUpdateBy());
        assertNotEquals(OLD_TIME, entity.getUpdateTime());
    }

    /**
     * 覆盖式填充只在字段可写时执行。
     *
     * <p>守护 {@code fillStrategy} 的 hasSetter 守卫：对不可写字段 setValue 即抛异常。
     */
    @Test
    void fillStrategyShouldSkipNonWritableFields() {
        BaseInfo entity = new BaseInfo();
        MetaObject meta = metaOf(entity);

        var result = handler.fillStrategy(meta, "noSuchField", "value");

        assertSame(handler, result);
        assertNull(entity.getUpdateBy());
    }

    /**
     * 元对象辅助：把真实实体包成 MyBatis 元对象。
     *
     * @param entity 实体
     * @return 元对象
     */
    private static MetaObject metaOf(BaseInfo entity) {
        return SystemMetaObject.forObject(entity);
    }

    /**
     * 登录用户辅助。
     *
     * @param username 用户名
     * @return 登录用户
     */
    private static KnowledgeUser principal(String username) {
        KnowledgeUser user = new KnowledgeUser();
        user.setUsername(username);
        return user;
    }
}
