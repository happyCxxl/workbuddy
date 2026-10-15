package com.workbuddy.common.utils;

import cn.hutool.core.util.ObjectUtil;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * 空值判定（项目统一入口）：语义与 Hutool ObjectUtil.isNull/isNotNull 一致。
 * 参数标注可空，SpotBugs 才能识别"传进来的可能就是 null"，被静态证明为 null 的位置不再报空指针类告警；
 * Hutool 判空方法的参数没有可空注解，本类作为判空边界把这一处矛盾就地抑制，其余代码无需任何豁免。
 *
 * @author cxxl
 */
public final class NullUtil {

    private NullUtil() {
    }

    /** 是否为 null */
    @SuppressFBWarnings(value = "NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE",
            justification = "Hutool 判空方法参数未标注可空，入参可空是事实；本类即判空边界")
    public static boolean isNull(@Nullable Object obj) {
        return ObjectUtil.isNull(obj);
    }

    /** 是否不为 null */
    @SuppressFBWarnings(value = "NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE",
            justification = "Hutool 判空方法参数未标注可空，入参可空是事实；本类即判空边界")
    public static boolean isNotNull(@Nullable Object obj) {
        return ObjectUtil.isNotNull(obj);
    }
}
