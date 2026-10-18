package com.workbuddy.common.utils;

import cn.hutool.core.util.StrUtil;

/**
 * 头像地址口径：库里只存对象 key，对外一律用后端接口的相对路径。
 *
 * <p>不下发对象存储的绝对地址：那是部署细节（容器里的主机名浏览器解析不了），私有桶还需要会过期的
 * 预签名地址。走本接口的相对路径后，开发期由 Vite 代理、生产由反向代理转发，与存储地址解耦。
 */
public final class AvatarUrlUtil {

    /** 对象 key 前缀；与文档对象同桶、靠前缀区隔 */
    public static final String KEY_PREFIX = "avatar/";

    /** 头像接口路径前缀 */
    private static final String PATH_PREFIX = "/user/avatar/";

    private AvatarUrlUtil() {
    }

    /**
     * 读接口地址：未设置头像时返回 null。
     *
     * @param userId 用户主键
     * @param key    对象 key
     * @return 形如 {@code /user/avatar/123?v=a3f9c2d1}；key 为空时返回 null
     */
    public static String readUrl(Long userId, String key) {
        if (NullUtil.isNull(userId) || StrUtil.isBlank(key)) {
            return null;
        }
        return PATH_PREFIX + userId + "?v=" + version(key);
    }

    /**
     * 取 key 的版本段（文件名去扩展名）。路径每次上传都变，它只用于让浏览器认出是新图。
     *
     * @param key 对象 key
     * @return 版本段；无法解析时返回空串
     */
    public static String version(String key) {
        if (StrUtil.isBlank(key)) {
            return "";
        }
        String fileName = key.substring(key.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
