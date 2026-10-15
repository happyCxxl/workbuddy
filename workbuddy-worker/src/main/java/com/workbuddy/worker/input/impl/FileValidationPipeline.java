package com.workbuddy.worker.input.impl;

import com.workbuddy.common.domain.input.FileCheckResult;
import com.workbuddy.common.domain.input.FileMetadata;
import com.workbuddy.common.domain.input.FileValidationResult;
import com.workbuddy.common.enums.input.FileValidationFailReason;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.service.FileStorage;
import com.workbuddy.worker.input.FileCheckContext;
import com.workbuddy.worker.input.FileValidationProperties;
import com.workbuddy.worker.input.FileValidatorPort;
import com.workbuddy.worker.input.SpillBuffer;
import com.workbuddy.worker.input.check.FileCheck;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;

/**
 * {@link FileValidatorPort} 实现：校验步骤链的薄编排器。
 * 流程：取文件档案 → 打开单次取流 → 按 {@link FileCheck#order()} 依次执行校验步骤
 * （首个失败即返回对应 failReason）→ 全过组装 pass（format/mimeType/size/sha256）。
 * 校验逻辑全部在 {@link FileCheck} 步骤里（新增校验 = 新增步骤类）；本类只负责资源生命周期与顺序。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileValidationPipeline implements FileValidatorPort {

    private final FileStorage fileStorage;
    private final FileValidationProperties properties;
    private final List<FileCheck> checks;

    /**
     * 校验文件：单次取流内依次完成大小统计 + 流式 sha256、
     * 空文件判定、元数据大小双源比对、Tika 魔数识别、格式白名单、损坏/加密探测。
     *
     * @param fileId 文件 ID
     * @return 校验结果（通过携带 format/mimeType/size/sha256；失败携带 failReason）
     */
    @Override
    public FileValidationResult validate(String fileId) {
        long start = System.currentTimeMillis();
        FileMetadata metadata = loadMetadata(fileId);
        if (NullUtil.isNull(metadata)) {
            return FileValidationResult.fail(FileValidationFailReason.FILE_NOT_FOUND);
        }
        log.info("===> FileValidationPipeline 开始校验, fileId={}, fileName={}, 元数据大小={}",
                fileId, metadata.getFileName(), metadata.getFileSize());
        SpillBuffer spill = new SpillBuffer(properties.getTempFileThreshold());
        FileCheckContext context = new FileCheckContext(fileId, metadata, properties, spill);
        try (InputStream in = fileStorage.open(fileId)) {
            context.setSourceStream(in);
            List<FileCheck> ordered = checks.stream()
                    .sorted(Comparator.comparingInt(FileCheck::order))
                    .toList();
            for (FileCheck check : ordered) {
                long stepStart = System.currentTimeMillis();
                FileCheckResult result = check.check(context);
                // 成功步骤静默（降噪）；失败步骤打完整上下文
                if (!result.passed()) {
                    log.warn("===> FileValidationPipeline 校验失败, fileId={}, step={}, failReason={}, 步骤耗时={}ms, 总耗时={}ms",
                            fileId, check.name(), result.failReason(),
                            System.currentTimeMillis() - stepStart, System.currentTimeMillis() - start);
                    return FileValidationResult.fail(result.failReason());
                }
            }
            String sha256 = spill.sha256Hex();
            FileValidationResult passed = FileValidationResult.pass(context.getFormat(),
                    context.getMediaType(), context.getSize(), sha256);
            log.info("===> FileValidationPipeline 校验通过, fileId={}, format={}, mimeType={}, size={}B, sha256={}, 总耗时={}ms",
                    fileId, context.getFormat(), context.getMediaType(), context.getSize(),
                    sha256.substring(0, Math.min(12, sha256.length())), System.currentTimeMillis() - start);
            return passed;
        } catch (IOException e) {
            log.warn("读取文件流失败, fileId={}", fileId, e);
            return FileValidationResult.fail(FileValidationFailReason.FILE_CORRUPTED);
        } finally {
            spill.close();
        }
    }

    /** 读文件档案；文件不存在返回 null（记 FAIL 日志口径） */
    private FileMetadata loadMetadata(String fileId) {
        try {
            return fileStorage.metadata(fileId);
        } catch (KnowledgeException e) {
            if (e.getErrorCode() == ErrorCode.FILE_NOT_FOUND) {
                log.warn("===> FileValidationPipeline 文件不存在, fileId={}", fileId);
                return null;
            }
            throw e;
        }
    }
}
