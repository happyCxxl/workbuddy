package com.workbuddy.common.convert.knowledge;

import com.workbuddy.common.config.BaseInfoConvertConfig;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStepLog;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.task.StepLogFields;
import com.workbuddy.common.enums.index.IndexVersionStatus;
import com.workbuddy.common.enums.task.PipelineStage;
import org.mapstruct.Mapper;

/**
 * 流水线转换：产物/步骤日志/索引版本 → 落库行。只搬字段，不查库、不算统计。
 */
@Mapper(config = BaseInfoConvertConfig.class)
public interface PipelineConverter {

    /**
     * 流水线产物行：产物引用列取自对象存储位置。
     *
     * @param task               所属任务
     * @param stage              产出环节
     * @param upstreamProductId  上游产物 ID
     * @param capabilitySnapshot 能力/策略版本快照
     * @param ref                产物对象位置
     * @return 产物行（未保存）
     */
    default WbPipelineProduct toProduct(WbPipelineTask task, PipelineStage stage, Long upstreamProductId,
                                        String capabilitySnapshot, ObjectRef ref) {
        WbPipelineProduct product = new WbPipelineProduct();
        product.setTaskId(task.getId());
        product.setFileResultId(task.getFileResultId());
        product.setStage(stage.name());
        product.setUpstreamProductId(upstreamProductId);
        product.setCapabilitySnapshot(capabilitySnapshot);
        product.setArtifactId(ref.objectKey());
        product.setStorageType(ref.storageType().getCode());
        product.setStorageSourceId(ref.sourceId());
        product.setBucket(ref.bucket());
        product.setContentHash(ref.objectKey());
        return product;
    }

    /**
     * 步骤日志行：步骤字段由 {@link StepLogFields#copyFrom} 提供，计数字段与错误截断仍在调用方。
     *
     * @param taskId 任务 ID
     * @param step   步骤日志字段来源
     * @return 步骤日志行（未保存）
     */
    default WbPipelineStepLog toStepLog(Long taskId, StepLogFields step) {
        WbPipelineStepLog stepLog = new WbPipelineStepLog();
        stepLog.copyFrom(step);
        stepLog.setTaskId(taskId);
        return stepLog;
    }

    /**
     * 索引版本行：版本号由调用方取（含并发冲突重取），本方法只做字段搬运。
     *
     * @param set         索引集合
     * @param versionNo   版本号
     * @param comboJson   组合快照 JSON
     * @param chunkCount  切片数
     * @param vectorCount 向量数
     * @return 版本行（未保存）
     */
    default WbIndexVersion toIndexVersion(WbIndexSet set, String versionNo, String comboJson,
                                          Integer chunkCount, Integer vectorCount) {
        WbIndexVersion version = new WbIndexVersion();
        version.setIndexSetId(set.getId());
        version.setVersionNo(versionNo);
        version.setComboSnapshot(comboJson);
        version.setStatus(IndexVersionStatus.CREATED.name());
        version.setChunkCount(chunkCount);
        version.setVectorCount(vectorCount);
        return version;
    }
}
