package com.workbuddy.biz.service.impl;

import com.workbuddy.auth.db.WbUserDbService;
import com.workbuddy.biz.service.AvatarService;
import com.workbuddy.common.domain.entity.WbUser;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.AvatarUrlUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.common.utils.SecurityUtil;
import com.workbuddy.filecenter.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 头像服务实现：写路径按文件头判定类型（Content-Type 由客户端提供、可以伪造），
 * 读路径把不可用的对象统一降级为「没有头像」，让调用方不必区分失败原因。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarServiceImpl implements AvatarService {

    /** 头像大小上限 */
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final WbUserDbService userDbService;

    private final FileStorage fileStorage;

    @Override
    public String upload(MultipartFile file) {
        ThrowUtil.throwIf(NullUtil.isNull(file) || file.isEmpty(), ErrorCode.PARAM_INVALID, "请选择头像文件");
        ThrowUtil.throwIf(file.getSize() > MAX_BYTES, ErrorCode.PARAM_INVALID, "头像不能超过 2MB");
        String extension = resolveExtension(file);
        WbUser user = currentUser();
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
        return AvatarUrlUtil.readUrl(user.getId(), key);
    }

    @Override
    public void remove() {
        WbUser user = currentUser();
        user.setAvatar(null);
        user.setAvatarStorageType(null);
        user.setAvatarStorageSourceId(null);
        user.setAvatarBucket(null);
        userDbService.updateById(user);
    }

    @Override
    public AvatarContent load(Long userId) {
        WbUser user = userDbService.getById(userId);
        try {
            ObjectRef ref = ObjectRef.ofAvatar(user);
            if (NullUtil.isNull(ref)) {
                return null;
            }
            return new AvatarContent(ref.objectKey(), fileStorage.getObject(ref));
        } catch (RuntimeException e) {
            // 记录不完整、对象被清掉或存储不可用：按"没有头像"处理，前端回落到姓名首字
            log.debug("头像对象不可用，按没有头像处理, userId={}", userId, e);
            return null;
        }
    }

    private WbUser currentUser() {
        Long id = NullUtil.isNull(SecurityUtil.getUser()) ? null : SecurityUtil.getUser().getId();
        ThrowUtil.throwIf(NullUtil.isNull(id), ErrorCode.UNAUTHORIZED);
        WbUser user = userDbService.getById(id);
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

    private String randomSegment() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
