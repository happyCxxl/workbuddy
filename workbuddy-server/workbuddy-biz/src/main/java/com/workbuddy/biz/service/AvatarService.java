package com.workbuddy.biz.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 用户头像控制面：上传与移除。
 *
 * <p>写操作只作用于当前登录用户，userId 取自安全上下文，不接受请求参数。
 * 头像对象存对象存储，公开读前缀由存储适配器初始化时放开，下发的是对象 URL。
 */
public interface AvatarService {

    /**
     * 上传当前登录用户的头像：按文件头判定类型，写入对象存储并把公开 URL 写回用户记录。
     *
     * @param file 头像文件（PNG / JPG / WebP，不超过 2MB）
     * @return 可直接渲染的对象 URL
     */
    String upload(MultipartFile file);

    /** 移除当前登录用户的头像：URL 与三列存储位置一并清空，已上传的对象留待清理。 */
    void remove();
}
