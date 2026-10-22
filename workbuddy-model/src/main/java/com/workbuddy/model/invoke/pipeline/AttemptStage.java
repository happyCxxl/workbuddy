package com.workbuddy.model.invoke.pipeline;

import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.invoke.ModelErrorClassifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 尝试阶段：按候选顺序逐个调用，失败按分类决定是否换下一个。
 *
 * <p>M1 不含容量/熔断许可与明细落库，M2/M3 在调用前后追加。
 */
@Component
@RequiredArgsConstructor
public class AttemptStage implements CallStage {

    private final ModelErrorClassifier modelErrorClassifier;

    /**
     * 顺序尝试候选，成功即返回；全部失败时抛最后一次错误。
     *
     * @param context 调用上下文
     * @param next    后续阶段链（M1 无后续，保留扩展）
     */
    @Override
    public void apply(CallContext context, StageChain next) {
        List<ModelDescriptor> candidates = context.getCandidates();
        if (NullUtil.isNull(candidates) || candidates.isEmpty()) {
            throw new KnowledgeException(ErrorCode.MODEL_NOT_FOUND, noCandidateMessage(context));
        }
        for (ModelDescriptor candidate : candidates) {
            context.setAttempt(context.getAttempt() + 1);
            try {
                context.setResult(context.getCall().invoke(candidate));
                next.proceed(context);
                return;
            } catch (RuntimeException error) {
                KnowledgeException translated = modelErrorClassifier.translate(error);
                context.setLastError(translated);
                if (!modelErrorClassifier.canFallback(error)) {
                    throw translated;
                }
            }
        }
        throw modelErrorClassifier.translate(context.getLastError());
    }

    /** 无候选时的可读原因 */
    private String noCandidateMessage(CallContext context) {
        String detail = "无可用模型：capability=" + context.getCapability();
        if (NullUtil.isNotNull(context.getModelKeyFilter())) {
            detail += "，model=" + context.getModelKeyFilter();
        }
        return detail;
    }
}
