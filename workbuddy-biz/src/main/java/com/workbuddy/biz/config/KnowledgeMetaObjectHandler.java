package com.workbuddy.biz.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.workbuddy.common.enums.base.DelFlag;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.common.utils.SecurityUtil;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * 公共字段自动填充：插入/更新时填充逻辑删除标记与创建/更新留痕字段。
 *
 * @author cxxl
 */
@Configuration
public class KnowledgeMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        String operator = currentOperator();
        this.strictInsertFill(metaObject, "delFlag", String.class, DelFlag.NORMAL.getCode());
        this.strictInsertFill(metaObject, "createBy", String.class, operator);
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updateBy", String.class, operator);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }

    /**
     * 更新填充：**必须非严格**。
     *
     * <p>「先查出来改几个字段再 updateById」是项目里的主流写法，这种实体自带库里的旧
     * updateTime / updateBy，而 strictUpdateFill 只在字段为空时才填 —— 旧值被原样写进
     * SET 子句，反而让 DDL 上的 {@code ON UPDATE CURRENT_TIMESTAMP} 失效（显式赋值不触发），
     * 表现为"改了信息但更新时间不动"。updateBy 在无认证上下文时为 null，此时保持原值不动。
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        String operator = currentOperator();
        if (NullUtil.isNotNull(operator)) {
            this.fillStrategy(metaObject, "updateBy", operator);
        }
        this.fillStrategy(metaObject, "updateTime", LocalDateTime.now());
    }

    /**
     * 覆盖式填充：MP 默认的 fillStrategy 与 strictUpdateFill 一样是"**有值不覆盖**"，
     * 而这里是"先查出来改几个字段再 updateById"的主流写法 —— 实体自带库里的旧
     * updateTime / updateBy，默认策略会把旧值原样写回 SET 子句，UPDATE 执行成功、
     * 时间却不动（显式赋值还会让 DDL 上的 ON UPDATE CURRENT_TIMESTAMP 也失效）。
     *
     * <p>只影响调用 {@code fillStrategy} 的地方；插入走 {@code strictInsertFill}（另一个方法），
     * 插入语义不变。
     */
    @Override
    public MetaObjectHandler fillStrategy(MetaObject metaObject, String fieldName, Object fieldVal) {
        if (metaObject.hasSetter(fieldName)) {
            metaObject.setValue(fieldName, fieldVal);
        }
        return this;
    }

    /**
     * 当前操作人（无认证上下文返回 null）
     */
    private String currentOperator() {
        KnowledgeUser user = SecurityUtil.getUser();
        return NullUtil.isNull(user) ? null : user.getUsername();
    }
}
