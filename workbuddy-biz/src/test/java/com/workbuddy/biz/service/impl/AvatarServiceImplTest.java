package com.workbuddy.biz.service.impl;

import com.workbuddy.auth.db.WbUserDbService;
import com.workbuddy.biz.service.AvatarService;
import com.workbuddy.common.domain.entity.WbUser;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.SecurityUtil;
import com.workbuddy.filecenter.storage.FileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 头像服务单测。
 *
 * <p>守护四条契约：写路径按文件头判型（Content-Type 可伪造，不作依据）；对象写入后把公开 URL
 * 与三列存储位置一并落库；后端不支持公开读时拒绝上传；移除清空 URL 与位置。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AvatarServiceImplTest {

    private static final byte[] PNG_HEAD = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    private static final byte[] JPG_HEAD = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};

    private static final byte[] WEBP_HEAD = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

    private static final String PUBLIC_URL = "http://127.0.0.1:9000/files/avatar/1/a.png";

    @Mock
    private WbUserDbService userDbService;

    @Mock
    private FileStorage fileStorage;

    private MockedStatic<SecurityUtil> security;

    private AvatarServiceImpl service;

    private WbUser loginUser;

    /**
     * 每个用例重建静态登录上下文（当前用户 ID=1）与被测服务。
     */
    @BeforeEach
    void setUp() {
        security = Mockito.mockStatic(SecurityUtil.class);
        KnowledgeUser principal = new KnowledgeUser();
        principal.setId(1L);
        security.when(SecurityUtil::getUser).thenReturn(principal);

        loginUser = new WbUser();
        loginUser.setId(1L);
        loginUser.setUsername("alice");
        when(userDbService.getById(1L)).thenReturn(loginUser);

        service = new AvatarServiceImpl(userDbService, fileStorage);
    }

    /**
     * 每个用例关闭静态 mock。
     */
    @AfterEach
    void tearDown() {
        security.close();
    }

    /**
     * PNG 头像：写入对象存储，记录公开 URL 与存储位置。
     *
     * <p>守护 {@code upload}：URL 不落库，登录/资料接口就没有可渲染地址。
     */
    @Test
    void uploadShouldStorePublicUrlAndLocation() {
        when(fileStorage.putRaw(anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenReturn(new ObjectRef(5L, StorageType.MINIO, "files", "avatar/1/a.png"));
        when(fileStorage.publicUrl(any(ObjectRef.class))).thenReturn(PUBLIC_URL);

        String url = service.upload(file(PNG_HEAD, "image/png"));

        assertEquals(PUBLIC_URL, url);
        assertEquals(PUBLIC_URL, loginUser.getAvatar());
        assertEquals("minio", loginUser.getAvatarStorageType());
        assertEquals(5L, loginUser.getAvatarStorageSourceId());
        assertEquals("files", loginUser.getAvatarBucket());
        verify(userDbService).updateById(loginUser);
    }

    /**
     * 文件头决定类型：JPG 与 WebP 分别按 jpeg / webp 写入。
     *
     * <p>守护 {@code resolveExtension}：只看 Content-Type 会让伪造文件绕过。
     */
    @Test
    void uploadShouldDetectJpgAndWebpHeads() {
        when(fileStorage.putRaw(anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenReturn(new ObjectRef(5L, StorageType.MINIO, "files", "avatar/1/a.jpg"));
        when(fileStorage.publicUrl(any(ObjectRef.class))).thenReturn(PUBLIC_URL);

        ArgumentCaptor<String> contentType = ArgumentCaptor.forClass(String.class);
        service.upload(file(JPG_HEAD, "application/octet-stream"));
        verify(fileStorage).putRaw(anyString(), any(InputStream.class), anyLong(), contentType.capture());
        assertEquals("image/jpeg", contentType.getValue());

        service.upload(file(WEBP_HEAD, "application/octet-stream"));
        verify(fileStorage, Mockito.times(2)).putRaw(anyString(), any(InputStream.class), anyLong(), contentType.capture());
        assertTrue(contentType.getAllValues().contains("image/webp"));
    }

    /**
     * 未知文件头拒绝上传。
     *
     * <p>守护 {@code resolveExtension} 的兜底分支。
     */
    @Test
    void uploadShouldRejectUnknownHead() {
        KnowledgeException ex = assertThrows(KnowledgeException.class,
                () -> service.upload(file("plain text".getBytes(), "text/plain")));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    /**
     * 超过 2MB 拒绝上传。
     *
     * <p>守护 {@code MAX_BYTES} 上限。
     */
    @Test
    void uploadShouldRejectOversizeFile() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(2L * 1024 * 1024 + 1);

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> service.upload(file));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    /**
     * 空文件拒绝上传。
     *
     * <p>守护空入参分支。
     */
    @Test
    void uploadShouldRejectEmptyFile() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(true);

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> service.upload(file));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    /**
     * 后端不支持公开读（本地磁盘）时拒绝上传，避免落一个不可渲染的地址。
     *
     * <p>守护 {@code upload} 的 URL 兜底：静默保存 key 会让前端显示破图。
     */
    @Test
    void uploadShouldRejectBackendWithoutPublicUrl() {
        when(fileStorage.putRaw(anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenReturn(new ObjectRef(5L, StorageType.LOCAL, "files", "avatar/1/a.png"));
        when(fileStorage.publicUrl(any(ObjectRef.class))).thenReturn(null);

        KnowledgeException ex = assertThrows(KnowledgeException.class,
                () -> service.upload(file(PNG_HEAD, "image/png")));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertNull(loginUser.getAvatar());
    }

    /**
     * 移除头像：URL 与三列存储位置一并清空。
     *
     * <p>守护 {@code remove}：漏清任何一列都会让资料页继续渲染旧图。
     */
    @Test
    void removeShouldClearUrlAndLocationColumns() {
        loginUser.setAvatar(PUBLIC_URL);
        loginUser.setAvatarStorageType("minio");
        loginUser.setAvatarStorageSourceId(5L);
        loginUser.setAvatarBucket("files");

        service.remove();

        assertNull(loginUser.getAvatar());
        assertNull(loginUser.getAvatarStorageType());
        assertNull(loginUser.getAvatarStorageSourceId());
        assertNull(loginUser.getAvatarBucket());
        verify(userDbService).updateById(loginUser);
    }

    /**
     * 匿名调用写接口被拒。
     *
     * <p>守护 {@code currentUser} 的未认证分支。
     */
    @Test
    void uploadShouldRejectAnonymous() {
        security.when(SecurityUtil::getUser).thenReturn(null);

        KnowledgeException ex = assertThrows(KnowledgeException.class,
                () -> service.upload(file(PNG_HEAD, "image/png")));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    /**
     * 构造一个按魔数可判定的上传文件。
     *
     * @param head        文件头
     * @param contentType 客户端声明的类型（服务端不信任）
     * @return 文件替身
     */
    private MultipartFile file(byte[] head, String contentType) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn((long) head.length);
        when(file.getContentType()).thenReturn(contentType);
        try {
            when(file.getInputStream()).thenAnswer(inv -> new ByteArrayInputStream(head));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return file;
    }
}
