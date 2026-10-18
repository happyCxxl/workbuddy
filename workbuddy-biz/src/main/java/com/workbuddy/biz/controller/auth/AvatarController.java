package com.workbuddy.biz.controller.auth;

import com.workbuddy.biz.service.AvatarService;
import com.workbuddy.common.web.R;
import com.workbuddy.common.utils.NullUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

/**
 * 用户头像。
 *
 * <p>写操作（上传 / 移除）只作用于**当前登录用户**，userId 一律取自安全上下文、不接受请求参数；
 * 读取接口免登录（见 SecurityConfig），供 {@code <img src>} 直接渲染——浏览器不会在 img 请求上带
 * Authorization 头，这条读路径必须公开。
 *
 * <p>读取返回对象字节而不是统一 JSON 外壳：响应要带 Content-Type、ETag 与长缓存头，由浏览器直接消费。
 */
@RestController
@RequestMapping("/user/avatar")
@RequiredArgsConstructor
public class AvatarController {

    private final AvatarService avatarService;

    /** 上传头像：按文件头判定类型，成功返回可直接渲染的新地址 */
    @PostMapping
    public R<String> upload(@RequestParam("file") MultipartFile file) {
        return R.ok(avatarService.upload(file), "头像已更新");
    }

    /** 移除头像：四列一并清空（key + 存储类型 + 数据源 + 桶名） */
    @DeleteMapping
    public R<Void> remove() {
        avatarService.remove();
        return R.ok(null, "头像已移除");
    }

    /**
     * 读取头像：未设置头像与用户不存在都回 404，两种情况响应完全一致，
     * 探测不出"某个 userId 是否存在"。
     */
    @GetMapping("/{userId}")
    public ResponseEntity<byte[]> read(@PathVariable Long userId) {
        AvatarService.AvatarContent content = avatarService.load(userId);
        if (NullUtil.isNull(content)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(mediaTypeOf(content.objectKey()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .eTag(content.objectKey())
                .body(content.data());
    }

    private MediaType mediaTypeOf(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        return MediaType.IMAGE_JPEG;
    }
}
