package com.workbuddy.worker.input.impl.check;

import cn.hutool.core.util.ObjectUtil;
import com.workbuddy.common.enums.input.FileCheckStepName;
import com.workbuddy.common.enums.input.FileFormat;
import com.workbuddy.common.enums.input.FileValidationFailReason;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.input.check.FileCheck;
import com.workbuddy.worker.input.FileCheckContext;
import com.workbuddy.common.domain.input.FileCheckResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 魔数识别步骤：Tika 真实格式识别（不信扩展名）→ 加密探测（ooxml-protected）→
 * 图片 OCR 预留判定 → 格式白名单。识别结果（mediaType/format）写入上下文供后续步骤与结果组装使用。
 */
@Slf4j
@Component
public class MagicTypeCheck implements FileCheck {

    private final Tika tika = new Tika();

    @Override
    public int order() {
        return 40;
    }

    @Override
    public FileCheckStepName name() {
        return FileCheckStepName.MAGIC_TYPE;
    }

    @Override
    public FileCheckResult check(FileCheckContext context) {
        String mediaType;
        try (InputStream probeIn = context.getSpill().openStream()) {
            mediaType = tika.detect(probeIn);
        } catch (IOException e) {
            log.warn("Tika 探测读取失败, fileId={}", context.getFileId(), e);
            return FileCheckResult.fail(FileValidationFailReason.FILE_CORRUPTED);
        }
        if (isProtected(mediaType)) {
            return FileCheckResult.fail(FileValidationFailReason.FILE_ENCRYPTED);
        }
        if (isReservedImage(mediaType)) {
            return FileCheckResult.fail(FileValidationFailReason.IMAGE_OCR_RESERVED);
        }
        FileFormat format = mapFormat(mediaType);
        if (NullUtil.isNull(format) || !isEnabled(format, context)) {
            return FileCheckResult.fail(FileValidationFailReason.FORMAT_NOT_ALLOWED);
        }
        context.setMediaType(mediaType);
        context.setFormat(format);
        return FileCheckResult.pass();
    }

    /** Office OOXML 受保护文档判定（Tika detect 返回 x-tika-ooxml-protected）。 */
    private boolean isProtected(String mediaType) {
        return NullUtil.isNotNull(mediaType) && mediaType.contains("ooxml-protected");
    }

    /** JPG/PNG/TIFF/BMP 可识别但 OCR 未开放。 */
    private boolean isReservedImage(String mediaType) {
        if (NullUtil.isNull(mediaType)) {
            return false;
        }
        return switch (mediaType) {
            case "image/jpeg", "image/png", "image/tiff", "image/bmp" -> true;
            default -> false;
        };
    }

    /** Tika 媒体类型 → FileFormat 字典映射（魔数真实识别结果）；未知类型返回 null。 */
    private FileFormat mapFormat(String mediaType) {
        if (NullUtil.isNull(mediaType)) {
            return null;
        }
        return FileFormat.ofMimeType(mediaType);
    }

    /** 白名单判定：FileFormat 枚举名在 knowledge.file.enabled-formats 配置内。 */
    private boolean isEnabled(FileFormat format, FileCheckContext context) {
        List<String> enabled = ObjectUtil.defaultIfNull(context.getProperties().getEnabledFormats(), List.of());
        Set<String> whitelist = new HashSet<>(enabled);
        return whitelist.contains(format.name());
    }
}
