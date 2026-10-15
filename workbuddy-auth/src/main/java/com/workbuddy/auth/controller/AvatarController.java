package com.workbuddy.auth.controller;

import com.workbuddy.auth.db.UserDbService;
import com.workbuddy.common.domain.entity.User;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.R;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.AvatarUrlUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.common.utils.SecurityUtil;
import com.workbuddy.filecenter.service.FileStorage;
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

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 用户头像。
 *
 * <p>写操作（上传 / 移除）只作用于**当前登录用户**，userId 一律取自安全上下文、不接受请求参数；
 * 读取接口免登录（见 SecurityConfig），供 {@code <img src>} 直接渲染——浏览器不会在 img 请求上带
 * Authorization 头，这条读路径必须公开。
 *
 * @author cxxl
 */
@RestController
@RequestMapping("/user/avatar")
@RequiredArgsConstructor
public class AvatarController {

    /** 头像大小上限 */
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserDbService userDbService;

    private final FileStorage fileStorage;

    /** 上传头像：按文件头判定类型，成功返回可直接渲染的新地址 */
    @PostMapping
    public R<String> upload(@RequestParam("file") MultipartFile file) {
        ThrowUtil.throwIf(NullUtil.isNull(file) || file.isEmpty(), ErrorCode.PARAM_INVALID, "请选择头像文件");
        ThrowUtil.throwIf(file.getSize() > MAX_BYTES, ErrorCode.PARAM_INVALID, "头像不能超过 2MB");
        String extension = resolveExtension(file);
        User user = currentUser();
        // 每次上传换一个新 key：路径不可变，浏览器可长缓存，也天然解决"换了头像还在用旧图"
        String key = AvatarUrlUtil.KEY_PREFIX + user.getId() + "/" + randomSegment() + "." + extension;
        ObjectRef ref;
        try (InputStream in = file.getInputStream()) {
            ref = fileStorage.putRaw(key, in, file.getSize(), contentTypeOf(extension));
        } catch (IOException e) {
            throw new KnowledgeException(ErrorCode.SYSTEM_ERROR, "头像写入失败");
        }
        // key 与所属存储位置一并落库，读取时按记录选后端
        user.setAvatar(ref.objectKey());
        user.setAvatarStorageType(ref.storageType().getCode());
        user.setAvatarStorageSourceId(ref.sourceId());
        user.setAvatarBucket(ref.bucket());
        userDbService.updateById(user);
        return R.ok(AvatarUrlUtil.readUrl(user.getId(), key), "头像已更新");
    }

    /** 移除头像：四列一并清空（key + 存储类型 + 数据源 + 桶名）；已上传的对象留待清理，存储端口暂未提供删除能力 */
    @DeleteMapping
    public R<Void> remove() {
        User user = currentUser();
        user.setAvatar(null);
        user.setAvatarStorageType(null);
        user.setAvatarStorageSourceId(null);
        user.setAvatarBucket(null);
        userDbService.updateById(user);
        return R.ok(null, "头像已移除");
    }

    /**
     * 读取头像：未设置头像与用户不存在都回 404，两种情况响应完全一致，
     * 探测不出"某个 userId 是否存在"。
     *
     * <p>记录里的对象位置不完整、对象已被清掉或存储不可用同样回 404（按没有头像处理）。
     */
    @GetMapping("/{userId}")
    public ResponseEntity<byte[]> read(@PathVariable Long userId) {
        User user = userDbService.getById(userId);
        try {
            ObjectRef ref = ObjectRef.ofAvatar(user);
            if (NullUtil.isNull(ref)) {
                return ResponseEntity.notFound().build();
            }
            byte[] content = fileStorage.getObject(ref);
            return ResponseEntity.ok()
                    .contentType(mediaTypeOf(ref.objectKey()))
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                    .eTag(ref.objectKey())
                    .body(content);
        } catch (RuntimeException e) {
            // 对象被清掉或存储不可用：按"没有头像"处理，前端回落到姓名首字
            return ResponseEntity.notFound().build();
        }
    }

    private User currentUser() {
        Long id = NullUtil.isNull(SecurityUtil.getUser()) ? null : SecurityUtil.getUser().getId();
        ThrowUtil.throwIf(NullUtil.isNull(id), ErrorCode.UNAUTHORIZED);
        User user = userDbService.getById(id);
        ThrowUtil.throwIf(NullUtil.isNull(user), ErrorCode.USER_NOT_FOUND);
        return user;
    }

    /** 按文件头判定类型：Content-Type 由客户端提供、可以伪造 */
    private String resolveExtension(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(12);
            if (head.length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N'
                    && head[3] == 'G') {
                return "png";
            }
            if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8
                    && (head[2] & 0xFF) == 0xFF) {
                return "jpg";
            }
            if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F'
                    && head[3] == 'F' && head[8] == 'W' && head[9] == 'E' && head[10] == 'B'
                    && head[11] == 'P') {
                return "webp";
            }
        } catch (IOException e) {
            throw new KnowledgeException(ErrorCode.PARAM_INVALID, "头像文件读取失败");
        }
        throw new KnowledgeException(ErrorCode.PARAM_INVALID, "只支持 PNG / JPG / WebP 图片");
    }

    private String contentTypeOf(String extension) {
        return "png".equals(extension) ? "image/png"
                : "webp".equals(extension) ? "image/webp" : "image/jpeg";
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

    private String randomSegment() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
