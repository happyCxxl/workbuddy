package com.workbuddy.biz.controller.auth;

import com.workbuddy.biz.service.AvatarService;
import com.workbuddy.common.web.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户头像。
 *
 * <p>写操作（上传 / 移除）只作用于<b>当前登录用户</b>，userId 一律取自安全上下文、不接受请求参数。
 * 头像对象存对象存储的 `avatar/*` 公开读前缀，上传响应与用户资料里下发的都是对象 URL，
 * 浏览器 {@code <img src>} 直连存储，应用服务不再提供读接口或代理图片字节。
 */
@RestController
@RequestMapping("/user/avatar")
@RequiredArgsConstructor
public class AvatarController {

    private final AvatarService avatarService;

    /**
     * 上传头像：按文件头判定类型，成功返回可直接渲染的对象 URL。
     *
     * @param file 头像文件（PNG / JPG / WebP，不超过 2MB）
     * @return 对象 URL
     */
    @PostMapping
    public R<String> upload(@RequestParam("file") MultipartFile file) {
        return R.ok(avatarService.upload(file), "头像已更新");
    }

    /**
     * 移除头像：URL 与三列存储位置一并清空。
     *
     * @return 恒为成功响应
     */
    @DeleteMapping
    public R<Void> remove() {
        avatarService.remove();
        return R.ok(null, "头像已移除");
    }
}
