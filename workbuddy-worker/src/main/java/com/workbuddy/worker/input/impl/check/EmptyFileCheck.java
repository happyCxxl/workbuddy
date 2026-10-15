package com.workbuddy.worker.input.impl.check;

import com.workbuddy.common.enums.input.FileCheckStepName;
import com.workbuddy.common.enums.input.FileValidationFailReason;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.input.check.FileCheck;
import com.workbuddy.worker.input.FileCheckContext;
import com.workbuddy.common.domain.input.FileCheckResult;
import org.springframework.stereotype.Component;

/**
 * 空文件判定步骤：流式统计字节数为 0 视为损坏（空文件无可解析内容）。
 *
 * @author cxxl
 */
@Component
public class EmptyFileCheck implements FileCheck {

    @Override
    public int order() {
        return 20;
    }

    @Override
    public FileCheckStepName name() {
        return FileCheckStepName.EMPTY_FILE;
    }

    @Override
    public FileCheckResult check(FileCheckContext context) {
        if (NullUtil.isNotNull(context.getSize()) && context.getSize() == 0) {
            return FileCheckResult.fail(FileValidationFailReason.FILE_CORRUPTED);
        }
        return FileCheckResult.pass();
    }
}
