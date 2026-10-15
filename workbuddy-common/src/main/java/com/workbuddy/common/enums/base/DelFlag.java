package com.workbuddy.common.enums.base;

import com.workbuddy.common.utils.NullUtil;
import lombok.Getter;

/**
 * 逻辑删除标记：全表通用 `del_flag`（VARCHAR(1)）的码值。
 * 0 正常（NORMAL）/ 1 已删（DELETED）；删除是 UPDATE 置 1，物理行仍在。
 *
 * <p>码值写入取 {@link #getCode()}，判定走 {@link #isDeleted(String)}。
 * 实体字段是 `String`（{@code BaseInfo.delFlag}）：{@code @TableLogic}、MP 自动填充、
 * {@code LambdaQueryWrapper.in(...)} 都按字符串工作。
 *
 * <p>本枚举只管"这一行还在不在"；业务启停（ACTIVE / INACTIVE）见
 * {@link com.workbuddy.common.enums.task.RowStatus}。
 *
 * @author cxxl
 */
@Getter
public enum DelFlag {

    /** 正常（未删除） */
    NORMAL(Code.NORMAL),

    /** 已删（逻辑删除），无恢复接口 */
    DELETED(Code.DELETED);

    /**
     * 码值常量（编译期常量）：`"0"` / `"1"` 的唯一定义处，
     * {@link DelFlag} 枚举常量与 {@code @TableLogic} 注解属性引用同一份字面量。
     */
    public static final class Code {

        /** 正常码值 */
        public static final String NORMAL = "0";

        /** 已删码值 */
        public static final String DELETED = "1";

        private Code() {
        }
    }

    private final String code;

    DelFlag(String code) {
        this.code = code;
    }

    /**
     * 按码值转枚举；null 与未知码值都返回 null。
     *
     * <p>与 {@link com.workbuddy.common.enums.knowledge.KnowledgeBaseStatus#of(Integer)} 不同，
     * 这里**不抛异常**：del_flag 是基础设施列，脏值不该把读路径打挂。
     */
    public static DelFlag of(String code) {
        if (NullUtil.isNull(code)) {
            return null;
        }
        for (DelFlag item : values()) {
            if (item.code.equals(code)) {
                return item;
            }
        }
        return null;
    }

    /** 是否已删除：只有显式 "1" 才算已删（null 与未知值一律按未删除处理） */
    public static boolean isDeleted(String code) {
        return of(code) == DELETED;
    }
}
