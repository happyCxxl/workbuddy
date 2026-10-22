package com.workbuddy.biz.service.impl;

import com.workbuddy.auth.db.WbUserDbService;
import com.workbuddy.biz.service.AvatarService;
import com.workbuddy.common.domain.entity.WbUser;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.exception.ThrowUtil;
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
 * 头像服务实现：对象写入对象存储，公开读前缀由存储适配器初始化时放开，
 * 用户记录里直接保存可渲染的对象 URL，应用层不再代理图片字节。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarServiceImpl implements AvatarService {

    /** 头像大小上限 */
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    /** 头像对象 key 前缀；与文档/产物同桶，靠前缀区隔并单独放开匿名读 */
    private static final String KEY_PREFIX = "avatar/";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final WbUserDbService userDbService;

    private final FileStorage fileStorage;

    /**
     * 上传头像：按文件头判定类型，写入对象存储并把公开 URL 写回用户记录。
     *
     * @param file 头像文件（PNG / JPG / WebP，不超过 2MB）
     * @return 可直接渲染的对象 URL
     */
    @Override
    public String upload(MultipartFile file) {
        ThrowUtil.throwIf(NullUtil.isNull(file) || file.isEmpty(), ErrorCode.PARAM_INVALID, "请选择头像文件");
        ThrowUtil.throwIf(file.getSize() > MAX_BYTES, ErrorCode.PARAM_INVALID, "头像不能超过 2MB");
        String extension = resolveExtension(file);
        WbUser user = currentUser();
        // 每次上传换一个新 key：URL 随之变化，浏览器缓存不会命中旧图
        String key = KEY_PREFIX + user.getId() + "/" + randomSegment() + "." + extension;
        ObjectRef ref;
        try (InputStream in = file.getInputStream()) {
            ref = fileStorage.putRaw(key, in, file.getSize(), contentTypeOf(extension));
        } catch (IOException e) {
            log.warn("头像写入失败, key={}", key, e);
            throw new WorkbuddyException(ErrorCode.SYSTEM_ERROR, "头像写入失败");
        }
        String url = fileStorage.publicUrl(ref);
        ThrowUtil.throwIf(NullUtil.isNull(url), ErrorCode.PARAM_INVALID,
                "当前存储后端不支持头像直出，请切换对象存储数据源");
        user.setAvatar(url);
        user.setAvatarStorageType(ref.storageType().getCode());
        user.setAvatarStorageSourceId(ref.sourceId());
        user.setAvatarBucket(ref.bucket());
        userDbService.updateById(user);
        return url;
    }

    /** 移除头像：URL 与三列存储位置一并清空，已上传的对象留待清理。 */
    @Override
    public void remove() {
        WbUser user = currentUser();
        user.setAvatar(null);
        user.setAvatarStorageType(null);
        user.setAvatarStorageSourceId(null);
        user.setAvatarBucket(null);
        userDbService.updateById(user);
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
            log.warn("头像文件读取失败, fileName={}", file.getOriginalFilename(), e);
            throw new WorkbuddyException(ErrorCode.PARAM_INVALID, "头像文件读取失败");
        }
        throw new WorkbuddyException(ErrorCode.PARAM_INVALID, "只支持 PNG / JPG / WebP 图片");
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
