package com.workbuddy.worker.input.impl.check;

import com.workbuddy.common.enums.input.FileCheckStepName;
import com.workbuddy.common.enums.input.FileValidationFailReason;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.input.check.FileCheck;
import com.workbuddy.worker.input.FileCheckContext;
import com.workbuddy.common.domain.input.FileCheckResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Locale;

/**
 * 损坏/加密探测步骤：试解析文件结构（只探测，不取全文）；
 * 异常链含 encrypt/password → FILE_ENCRYPTED，其余异常 → FILE_CORRUPTED。
 * 深层损坏（试解析未暴露）由解析环节失败回写，不在入口拦截。
 */
@Slf4j
@Component
public class CorruptionProbeCheck implements FileCheck {

    /** 试解析输出上限（只探测结构，不取全文；1MB 足量） */
    private static final int PROBE_CHAR_LIMIT = 1024 * 1024;

    /**
     * 校验顺序。
     *
     * @return 顺序号（小的先执行）
     */
    @Override
    public int order() {
        return 50;
    }

    /**
     * 校验步骤名。
     *
     * @return 步骤枚举
     */
    @Override
    public FileCheckStepName name() {
        return FileCheckStepName.CORRUPTION_PROBE;
    }

    /**
     * 探测文件是否可读且结构完整：读流失败或结构异常按文件损坏处理。
     *
     * @param context 校验上下文（共享流与已识别事实）
     * @return 校验结果
     */
    @Override
    public FileCheckResult check(FileCheckContext context) {
        try (InputStream probeIn = context.getSpill().openStream()) {
            new AutoDetectParser().parse(probeIn, new BodyContentHandler(PROBE_CHAR_LIMIT), new Metadata());
        } catch (Exception e) {
            if (isEncryptedException(e)) {
                return FileCheckResult.fail(FileValidationFailReason.FILE_ENCRYPTED);
            }
            log.warn("Tika 试解析失败（损坏判定）, fileId={}, mediaType={}",
                    context.getFileId(), context.getMediaType(), e);
            return FileCheckResult.fail(FileValidationFailReason.FILE_CORRUPTED);
        }
        return FileCheckResult.pass();
    }

    /** 加密判定启发式：异常链（深度上限 10）消息含 encrypt/password 即视为加密。 */
    private boolean isEncryptedException(Exception e) {
        Throwable t = e;
        int depth = 0;
        while (NullUtil.isNotNull(t) && depth++ < 10) {
            String message = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("encrypt") || message.contains("password")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
