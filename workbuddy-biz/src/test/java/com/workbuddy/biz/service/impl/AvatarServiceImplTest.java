package com.workbuddy.biz.service.impl;

import com.workbuddy.auth.db.WbUserDbService;
import com.workbuddy.biz.service.AvatarService;
import com.workbuddy.common.domain.entity.WbUser;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.AvatarUrlUtil;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * <p>守护三条契约：写路径按文件头判型（Content-Type 可伪造，不作依据）、
 * 档案四列（对象键 + 类型 + 数据源 + 桶）一并落库、
 * 读路径把「没有头像 / 记录不完整 / 对象不可用」统一降级为 null。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AvatarServiceImplTest {

    private static final byte[] PNG_HEAD = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    private static final byte[] JPG_HEAD = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};

    private static final byte[] WEBP_HEAD = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

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
        service = new AvatarServiceImpl(userDbService, fileStorage);
    }

    /**
     * 让当前登录用户在档案表可查。
     */
    private void stubLoginUserArchive() {
        when(userDbService.getById(1L)).thenReturn(loginUser);
    }

    /**
     * 收尾关闭静态桩，避免污染其他用例。
     */
    @AfterEach
    void tearDown() {
        security.close();
    }

    /**
     * PNG 头判型为 png：对象键挂在用户名下、档案四列落库、返回可渲染地址。
     *
     * <p>守护 {@code upload} 的判型与落库：扩展名按 Content-Type 判、或漏存档案列即失败。
     */
    @Test
    void uploadShouldDetectPngAndArchiveObjectLocation() {
        stubLoginUserArchive();
        stubPutRawEcho();
        MultipartFile file = file(PNG_HEAD, "image/fake");

        String url = service.upload(file);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileStorage).putRaw(keyCaptor.capture(), any(InputStream.class), eq((long) PNG_HEAD.length),
                eq("image/png"));
        String key = keyCaptor.getValue();
        assertTrue(key.startsWith("avatar/1/") && key.endsWith(".png"), () -> "对象键异常: " + key);

        assertEquals(key, loginUser.getAvatar());
        assertEquals("minio", loginUser.getAvatarStorageType());
        assertEquals(5L, loginUser.getAvatarStorageSourceId());
        assertEquals("files", loginUser.getAvatarBucket());
        verify(userDbService).updateById(loginUser);
        assertNotNull(url);
        // 返回的是渲染地址（/user/avatar/{id}?v=键文件名去扩展名），含本次上传的版本段
        assertEquals("/user/avatar/1?v=" + AvatarUrlUtil.version(key), url);
    }

    /**
     * JPG 头判型为 jpg，Content-Type 对应 image/jpeg。
     *
     * <p>守护 {@code resolveExtension} 的 JPG 分支：判成 png 即失败（浏览器拒渲染）。
     */
    @Test
    void uploadShouldDetectJpgHead() {
        stubLoginUserArchive();
        stubPutRawEcho();

        service.upload(file(JPG_HEAD, "application/octet-stream"));

        ArgumentCaptor<String> contentType = ArgumentCaptor.forClass(String.class);
        verify(fileStorage).putRaw(anyString(), any(InputStream.class), anyLong(), contentType.capture());
        assertEquals("image/jpeg", contentType.getValue());
    }

    /**
     * WebP 头判型为 webp（RIFF 容器 + WEBP 标记），Content-Type 对应 image/webp。
     *
     * <p>守护 {@code resolveExtension} 的 WebP 分支：判成 jpg 即失败。
     */
    @Test
    void uploadShouldDetectWebpHead() {
        stubLoginUserArchive();
        stubPutRawEcho();

        service.upload(file(WEBP_HEAD, "application/octet-stream"));

        ArgumentCaptor<String> contentType = ArgumentCaptor.forClass(String.class);
        verify(fileStorage).putRaw(anyString(), any(InputStream.class), anyLong(), contentType.capture());
        assertEquals("image/webp", contentType.getValue());
    }

    /**
     * 文件头不认识的类型按参数错误拒绝（不落库）。
     *
     * <p>守护 {@code resolveExtension} 的拒绝分支：把未知类型当图片收下即失败。
     */
    @Test
    void uploadShouldRejectUnknownHead() {
        stubLoginUserArchive();
        KnowledgeException ex = assertThrows(KnowledgeException.class,
                () -> service.upload(file("plain text".getBytes(), "text/plain")));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(userDbService, org.mockito.Mockito.never()).updateById(any());
    }

    /**
     * 超过 2MB 拒绝。
     *
     * <p>守护 {@code upload} 的大小上限（错误码 40001）。
     */
    @Test
    void uploadShouldRejectOversizeFile() {
        stubLoginUserArchive();
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(2L * 1024 * 1024 + 1);

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> service.upload(file));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    /**
     * 空文件拒绝。
     *
     * <p>守护 {@code upload} 的空文件口径（错误码 40001）。
     */
    @Test
    void uploadShouldRejectEmptyFile() {
        stubLoginUserArchive();
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(true);

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> service.upload(file));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    /**
     * 移除头像清空对象键与三列存储位置。
     *
     * <p>守护 {@code remove}：漏清任一列即失败（读取侧仍会尝试取对象）。
     */
    @Test
    void removeShouldClearArchiveColumns() {
        stubLoginUserArchive();
        loginUser.setAvatar("avatar/1/old.png");
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
     * 有头像且对象可用时返回对象键与内容。
     *
     * <p>守护 {@code load} 的正常读取。
     */
    @Test
    void loadShouldReturnContentWhenObjectAvailable() {
        WbUser user = new WbUser();
        user.setId(2L);
        user.setAvatar("avatar/2/k.png");
        user.setAvatarStorageType("local");
        user.setAvatarStorageSourceId(5L);
        user.setAvatarBucket("files");
        when(userDbService.getById(2L)).thenReturn(user);
        byte[] data = "png-bytes".getBytes();
        when(fileStorage.getObject(any(ObjectRef.class))).thenReturn(data);

        AvatarService.AvatarContent content = service.load(2L);

        assertEquals("avatar/2/k.png", content.objectKey());
        assertArrayEquals(data, content.data());
    }

    /**
     * 没有头像时返回 null。
     *
     * <p>守护 {@code load} 的空头像口径。
     */
    @Test
    void loadShouldReturnNullWhenNoAvatar() {
        when(userDbService.getById(2L)).thenReturn(new WbUser());

        assertNull(service.load(2L));
    }

    /**
     * 对象被清掉或存储不可用时统一按「没有头像」处理，不向上抛。
     *
     * <p>守护 {@code load} 的降级口径：把存储异常上抛即失败（前端回落失效）。
     */
    @Test
    void loadShouldDegradeToObjectFailure() {
        WbUser user = new WbUser();
        user.setId(2L);
        user.setAvatar("avatar/2/k.png");
        user.setAvatarStorageType("local");
        user.setAvatarStorageSourceId(5L);
        user.setAvatarBucket("files");
        when(userDbService.getById(2L)).thenReturn(user);
        when(fileStorage.getObject(any(ObjectRef.class))).thenThrow(new IllegalStateException("down"));

        assertNull(service.load(2L));
    }

    /**
     * 档案不完整（存储类型码无法识别）同样按没有头像处理。
     *
     * <p>守护 {@code load} 对脏数据的容忍。
     */
    @Test
    void loadShouldDegradeWhenArchiveIncomplete() {
        WbUser user = new WbUser();
        user.setId(2L);
        user.setAvatar("avatar/2/k.png");
        user.setAvatarStorageType("unknown-code");
        user.setAvatarStorageSourceId(5L);
        user.setAvatarBucket("files");
        when(userDbService.getById(2L)).thenReturn(user);

        assertNull(service.load(2L));
    }

    /**
     * 恰好 8 字节的 PNG 头也能判型（签名本身就是 8 字节）。
     *
     * <p>守护 {@code resolveExtension} 的 {@code head.length >= 8} 边界：写成大于会拒掉合法 PNG。
     */
    @Test
    void uploadShouldAcceptExactEightBytePngHead() {
        stubLoginUserArchive();
        stubPutRawEcho();
        byte[] exactHead = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

        service.upload(file(exactHead, "image/png"));

        ArgumentCaptor<String> contentType = ArgumentCaptor.forClass(String.class);
        verify(fileStorage).putRaw(anyString(), any(InputStream.class), anyLong(), contentType.capture());
        assertEquals("image/png", contentType.getValue());
    }

    /**
     * 恰好 2MB 放行，超 1 字节才拒绝。
     *
     * <p>守护 {@code upload} 的 {@code size > MAX_BYTES} 边界：写成大于等于会误拒临界文件。
     */
    @Test
    void uploadShouldAcceptExactlyMaxSize() {
        stubLoginUserArchive();
        stubPutRawEcho();
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(2L * 1024 * 1024);
        when(file.isEmpty()).thenReturn(false);
        try {
            when(file.getInputStream()).thenReturn(new ByteArrayInputStream(PNG_HEAD));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        assertNotNull(service.upload(file));
    }

    /**
     * 匿名上传按 40101 拒绝；登录用户档案缺失按 404xx 拒绝。
     *
     * <p>守护 {@code currentUser} 的两道校验：漏一道会拿 null 用户继续写库。
     */
    @Test
    void currentUserShouldRejectAnonymousAndMissingArchive() {
        security.when(SecurityUtil::getUser).thenReturn(null);
        KnowledgeException anonymous = assertThrows(KnowledgeException.class, () -> service.remove());
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), anonymous.getCode());

        KnowledgeUser principal = new KnowledgeUser();
        principal.setId(1L);
        security.when(SecurityUtil::getUser).thenReturn(principal);
        when(userDbService.getById(1L)).thenReturn(null);
        KnowledgeException missing = assertThrows(KnowledgeException.class, () -> service.remove());
        assertEquals(ErrorCode.USER_NOT_FOUND.getCode(), missing.getCode());
    }

    /**
     * 每次上传的对象键都带一组随机十六进制段（不是空串、也不是全零）。
     *
     * <p>守护 {@code randomSegment}：不取随机字节会让同一用户的头像键永远相同、长缓存失效。
     */
    @Test
    void uploadShouldGenerateRandomSegment() {
        stubLoginUserArchive();
        stubPutRawEcho();

        service.upload(file(PNG_HEAD, "image/png"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileStorage).putRaw(keyCaptor.capture(), any(InputStream.class), anyLong(), anyString());
        String key = keyCaptor.getValue();
        assertTrue(key.matches("avatar/1/[0-9a-f]{8}\\.png"), key);
        assertFalse(key.contains("/00000000."), key);
    }

    /**
     * 构造指定文件头的上传文件。
     *
     * @param head        文件头（12 字节内可判型）
     * @param contentType 声明的类型（可伪造）
     * @return 上传文件
     */
    private MultipartFile file(byte[] head, String contentType) {
        MultipartFile file = mock(MultipartFile.class);
        try {
            when(file.getInputStream()).thenReturn(new ByteArrayInputStream(head));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        when(file.getSize()).thenReturn((long) head.length);
        when(file.getContentType()).thenReturn(contentType);
        when(file.getOriginalFilename()).thenReturn("avatar");
        stubLoginUserArchive();
        stubPutRawEcho();
        return file;
    }

    /**
     * 写入替身按传入键原样回填对象位置（与真实语义一致：objectKey 即传入键）。
     */
    private void stubPutRawEcho() {
        when(fileStorage.putRaw(anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenAnswer(invocation -> new ObjectRef(5L, StorageType.MINIO, "files",
                        invocation.getArgument(0, String.class)));
    }
}
