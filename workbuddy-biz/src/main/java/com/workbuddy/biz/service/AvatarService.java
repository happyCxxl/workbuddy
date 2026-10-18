package com.workbuddy.biz.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 用户头像控制面：上传、移除、读取。
 *
 * <p>写操作只作用于当前登录用户，userId 取自安全上下文，不接受请求参数。
 */
public interface AvatarService {

    /**
     * 上传当前登录用户的头像：按文件头判定类型，成功后把对象位置写回用户记录。
     *
     * @param file 头像文件（PNG / JPG / WebP，不超过 2MB）
     * @return 可直接渲染的新地址
     */
    String upload(MultipartFile file);

    /** 移除当前登录用户的头像：对象键与三列存储位置一并清空，已上传的对象留待清理。 */
    void remove();

    /**
     * 读取指定用户的头像。
     *
     * @param userId 用户 ID
     * @return 头像内容；没有头像、记录不完整或对象不可用时返回 {@code null}
     */
    AvatarContent load(Long userId);

    /**
     * 头像内容：对象键用于取 Content-Type 与 ETag，内容为对象字节。
     *
     * @param objectKey 对象键
     * @param data      对象字节
     */
    record AvatarContent(String objectKey, byte[] data) {
    }
}
