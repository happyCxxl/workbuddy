package com.workbuddy.worker.input.impl.check;

import com.workbuddy.common.enums.input.FileCheckStepName;
import com.workbuddy.common.enums.input.FileValidationFailReason;
import com.workbuddy.worker.input.check.FileCheck;
import com.workbuddy.worker.input.FileCheckContext;
import com.workbuddy.common.domain.input.FileCheckResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
/**
 * 大小统计步骤：单遍读流统计字节数 + 更新 sha256 摘要 + 写入校验缓冲；
 * 超过大小上限即停（不再继续读取），返回 FILE_TOO_LARGE。字节数写入上下文供后续步骤使用。
 *
 * @author cxxl
 */
@Slf4j
@Component
public class SizeLimitCheck implements FileCheck {

    /** 超限哨兵：pump 返回 -1 表示超过大小上限 */
    private static final long OVER_LIMIT = -1L;

    /** 读流缓冲 8KB */
    private static final int BUFFER_SIZE = 8192;

    @Override
    public int order() {
        return 10;
    }

    @Override
    public FileCheckStepName name() {
        return FileCheckStepName.SIZE_LIMIT;
    }

    @Override
    public FileCheckResult check(FileCheckContext context) {
        try {
            long total = pump(context.getSourceStream(), context);
            if (total == OVER_LIMIT) {
                return FileCheckResult.fail(FileValidationFailReason.FILE_TOO_LARGE);
            }
            context.setSize(total);
            return FileCheckResult.pass();
        } catch (IOException e) {
            log.warn("读取文件流失败, fileId={}", context.getFileId(), e);
            return FileCheckResult.fail(FileValidationFailReason.FILE_CORRUPTED);
        }
    }

    /**
     * 单遍读流：统计字节数 + 写入缓冲（同步更新 sha256）+ 超限即停（返回 OVER_LIMIT）。
     */
    private long pump(InputStream in, FileCheckContext context) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > context.getProperties().getMaxSize()) {
                return OVER_LIMIT;
            }
            context.getSpill().write(buffer, 0, n);
        }
        return total;
    }
}
