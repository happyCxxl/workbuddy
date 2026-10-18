package com.workbuddy.worker.input.impl.check;

import com.workbuddy.common.enums.input.FileCheckStepName;
import com.workbuddy.common.enums.input.FileValidationFailReason;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.input.check.FileCheck;
import com.workbuddy.worker.input.FileCheckContext;
import com.workbuddy.common.domain.input.FileCheckResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 大小双源比对步骤：文件档案元数据大小 vs 流式统计字节数，不一致 = METADATA_MISMATCH
 * （拦截传输截断/元数据漂移）。元数据缺省（null）跳过比对。
 */
@Slf4j
@Component
public class MetadataMatchCheck implements FileCheck {

    /**
     * 校验顺序。
     *
     * @return 顺序号（小的先执行）
     */
    @Override
    public int order() {
        return 30;
    }

    /**
     * 校验步骤名。
     *
     * @return 步骤枚举
     */
    @Override
    public FileCheckStepName name() {
        return FileCheckStepName.METADATA_MATCH;
    }

    /**
     * 校验文件元数据与真实格式是否一致（扩展名、媒体类型）。
     *
     * @param context 校验上下文（共享流与已识别事实）
     * @return 校验结果
     */
    @Override
    public FileCheckResult check(FileCheckContext context) {
        Long metadataSize = context.getMetadata().getFileSize();
        // 成功静默（降噪）；不一致时打完整比对数值
        if (NullUtil.isNotNull(metadataSize) && !metadataSize.equals(context.getSize())) {
            log.warn("===> MetadataMatchCheck 双源不一致, fileId={}, metadataSize={}, streamSize={}",
                    context.getFileId(), metadataSize, context.getSize());
            return FileCheckResult.fail(FileValidationFailReason.METADATA_MISMATCH);
        }
        return FileCheckResult.pass();
    }
}
