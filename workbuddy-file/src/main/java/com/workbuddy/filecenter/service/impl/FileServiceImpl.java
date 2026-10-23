package com.workbuddy.filecenter.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.workbuddy.common.domain.input.FileMetadata;
import com.workbuddy.common.domain.input.FileValidationResult;
import com.workbuddy.common.enums.input.FileFormat;
import com.workbuddy.common.enums.input.FileValidationFailReason;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.config.FileProperties;
import com.workbuddy.filecenter.service.FileService;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.filecenter.util.FileUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 文件服务实现：上传（校验后落库）、提交校验与下载。校验流程在服务内编排，判定原语在 {@link FileUtil}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final FileStorage fileStorage;
    private final FileProperties fileProperties;

    /**
     * 上传文件：校验（扩展名白名单 → 大小 → 空文件 → 魔数）不通过抛 WorkbuddyException（40422，原因入 detail），
     * 通过后落库并返回 fileId。
     *
     * @param file 上传文件
     * @return 新建的 fileId
     */
    @Override
    public String upload(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        List<String> enabled = ObjectUtil.defaultIfNull(fileProperties.getEnabledFormats(), List.of());

        // ① 扩展名白名单（廉价拦截，不读流）
        FileFormat declared = FileFormat.ofExtension(FileUtil.extensionOf(fileName));
        ThrowUtil.throwIf(NullUtil.isNull(declared) || !FileUtil.whitelisted(declared, enabled),
                ErrorCode.FILE_UPLOAD_REJECTED, FileValidationFailReason.FORMAT_NOT_ALLOWED.label());
        // ② 大小统计与上限（单遍读流）
        long size;
        try (InputStream in = file.getInputStream()) {
            size = FileUtil.count(in, fileProperties.getMaxSize());
        } catch (IOException e) {
            throw new WorkbuddyException(ErrorCode.FILE_UPLOAD_REJECTED,
                    FileValidationFailReason.FILE_CORRUPTED.label());
        }
        ThrowUtil.throwIf(size > fileProperties.getMaxSize(),
                ErrorCode.FILE_UPLOAD_REJECTED, FileValidationFailReason.FILE_TOO_LARGE.label());
        // ③ 空文件
        ThrowUtil.throwIf(FileUtil.isEmpty(size),
                ErrorCode.FILE_UPLOAD_REJECTED, FileValidationFailReason.FILE_CORRUPTED.label());
        // ④ 魔数识别与白名单（不信扩展名）
        String mediaType;
        try (InputStream in = file.getInputStream()) {
            mediaType = FileUtil.detect(in);
        } catch (IOException e) {
            throw new WorkbuddyException(ErrorCode.FILE_UPLOAD_REJECTED,
                    FileValidationFailReason.FILE_CORRUPTED.label());
        }
        ThrowUtil.throwIf(FileUtil.isProtected(mediaType),
                ErrorCode.FILE_UPLOAD_REJECTED, FileValidationFailReason.FILE_ENCRYPTED.label());
        ThrowUtil.throwIf(FileUtil.isReservedImage(mediaType),
                ErrorCode.FILE_UPLOAD_REJECTED, FileValidationFailReason.IMAGE_OCR_RESERVED.label());
        FileFormat real = FileUtil.ofFormat(mediaType);
        ThrowUtil.throwIf(NullUtil.isNull(real) || !FileUtil.whitelisted(real, enabled),
                ErrorCode.FILE_UPLOAD_REJECTED, FileValidationFailReason.FORMAT_NOT_ALLOWED.label());
        return fileStorage.putFile(file);
    }

    /**
     * 校验已落库文件：单次取流内大小统计与 sha256 → 空文件 → 大小双源比对 → 魔数与白名单 → 损坏/加密试解析。
     * 失败返回携带 failReason（不抛异常，由调用方记提交日志）。
     *
     * @param fileId 文件 ID
     * @return 校验结果（pass 带 format/mimeType/size/sha256；fail 带 failReason）
     */
    @Override
    public FileValidationResult validate(String fileId) {
        long start = System.currentTimeMillis();
        FileMetadata metadata = loadMetadata(fileId);
        if (NullUtil.isNull(metadata)) {
            return FileValidationResult.fail(FileValidationFailReason.FILE_NOT_FOUND);
        }
        log.info("===> FileServiceImpl 开始校验, fileId={}, fileName={}, 元数据大小={}",
                fileId, metadata.getFileName(), metadata.getFileSize());
        FileUtil.StreamBuffer buffer = new FileUtil.StreamBuffer(fileProperties.getTempFileThreshold());
        try (InputStream in = fileStorage.open(fileId)) {
            long size = FileUtil.countAndBuffer(in, fileProperties.getMaxSize(), buffer);
            if (size > fileProperties.getMaxSize()) {
                return FileValidationResult.fail(FileValidationFailReason.FILE_TOO_LARGE);
            }
            if (FileUtil.isEmpty(size)) {
                return FileValidationResult.fail(FileValidationFailReason.FILE_CORRUPTED);
            }
            // 大小双源比对：档案元数据 vs 流式统计
            Long metadataSize = metadata.getFileSize();
            if (NullUtil.isNotNull(metadataSize) && !metadataSize.equals(size)) {
                log.warn("===> FileServiceImpl 双源不一致, fileId={}, metadataSize={}, streamSize={}",
                        fileId, metadataSize, size);
                return FileValidationResult.fail(FileValidationFailReason.METADATA_MISMATCH);
            }
            // 魔数识别与白名单
            String mediaType;
            try (InputStream probeIn = buffer.openStream()) {
                mediaType = FileUtil.detect(probeIn);
            } catch (IOException e) {
                log.warn("===> FileServiceImpl Tika 探测读取失败, fileId={}", fileId, e);
                return FileValidationResult.fail(FileValidationFailReason.FILE_CORRUPTED);
            }
            if (FileUtil.isProtected(mediaType)) {
                return FileValidationResult.fail(FileValidationFailReason.FILE_ENCRYPTED);
            }
            if (FileUtil.isReservedImage(mediaType)) {
                return FileValidationResult.fail(FileValidationFailReason.IMAGE_OCR_RESERVED);
            }
            FileFormat format = FileUtil.ofFormat(mediaType);
            if (NullUtil.isNull(format) || !FileUtil.whitelisted(format, fileProperties.getEnabledFormats())) {
                return FileValidationResult.fail(FileValidationFailReason.FORMAT_NOT_ALLOWED);
            }
            // 损坏/加密试解析探测
            FileValidationFailReason probeReason;
            try (InputStream probeIn = buffer.openStream()) {
                probeReason = FileUtil.probe(probeIn);
            } catch (Exception e) {
                probeReason = FileValidationFailReason.FILE_CORRUPTED;
            }
            if (NullUtil.isNotNull(probeReason)) {
                log.warn("===> FileServiceImpl 试解析失败（损坏判定）, fileId={}, mediaType={}, reason={}",
                        fileId, mediaType, probeReason);
                return FileValidationResult.fail(probeReason);
            }
            return FileValidationResult.pass(format, mediaType, size, buffer.sha256Hex());
        } catch (IOException e) {
            log.warn("===> FileServiceImpl 读取文件流失败, fileId={}", fileId, e);
            return FileValidationResult.fail(FileValidationFailReason.FILE_CORRUPTED);
        } finally {
            buffer.close();
            log.info("===> FileServiceImpl 校验结束, fileId={}, 总耗时={}ms", fileId, System.currentTimeMillis() - start);
        }
    }

    /**
     * 下载文件：以附件形式写进响应流，文件名按 UTF-8 编码。
     *
     * @param fileId   文件 ID
     * @param response 响应
     * @throws IOException 读对象或写响应失败
     */
    @Override
    public void download(String fileId, HttpServletResponse response) throws IOException {
        FileMetadata metadata = fileStorage.metadata(fileId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(metadata.getFileName(), StandardCharsets.UTF_8)
                .build();
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        if (NullUtil.isNotNull(metadata.getFileSize())) {
            response.setContentLengthLong(metadata.getFileSize());
        }
        try (InputStream in = fileStorage.open(fileId)) {
            in.transferTo(response.getOutputStream());
        }
    }

    /** 读文件档案；文件不存在返回 null（记 FAIL 日志口径） */
    private FileMetadata loadMetadata(String fileId) {
        try {
            return fileStorage.metadata(fileId);
        } catch (WorkbuddyException e) {
            if (e.getErrorCode() == ErrorCode.FILE_NOT_FOUND) {
                log.warn("===> FileServiceImpl 文件不存在, fileId={}", fileId);
                return null;
            }
            throw e;
        }
    }
}
