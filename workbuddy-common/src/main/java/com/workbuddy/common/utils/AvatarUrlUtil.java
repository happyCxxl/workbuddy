package com.workbuddy.common.utils;

import cn.hutool.core.util.StrUtil;

/**
 * 头像地址口径：用户记录里存对象存储的公开 URL，对外原样下发。
 *
 * <p>历史数据里可能残留对象 key（非 http 开头）：这类值不再可渲染，统一按“没有头像”处理，
 * 由前端回落姓名首字，等用户重新上传后自然恢复。
 */
public final class AvatarUrlUtil {

    private AvatarUrlUtil() {
    }

    /**
     * 归一化用户记录里的头像值：只放行对象存储的绝对 URL。
     *
     * @param stored 库里存的头像值（公开 URL 或历史对象 key）
     * @return 可渲染的 URL；空值或历史 key 返回 null
     */
    public static String urlOrNull(String stored) {
        return StrUtil.startWith(stored, "http://") || StrUtil.startWith(stored, "https://") ? stored : null;
    }
}
