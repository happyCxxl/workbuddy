package com.workbuddy.biz.task.runner.persistence;

import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.support.access.ChainStorageSupport;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 阶段产物落库共用助手（各环节任务执行器共用）：
 * 执行键复用判定（该任务已有产物即复用）→ 存储一致性校验 → JSON 序列化 → 内容寻址写产物存储 →
 * 产物行（task/stage/upstream/能力快照/存储类型与桶名）→ 回写任务产物引用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductPersistence {

    private final WbPipelineProductDbService pipelineProductDbService;
    private final WbPipelineTaskDbService pipelineTaskDbService;
    private final ChainStorageSupport chainStorageSupport;
    private final FileStorage fileStorage;

    /**
     * 落库阶段产物并回写任务产物引用；返回产物行（artifactId = sha256 = contentHash，
     * storageType / storageSourceId / bucket 取自对象位置）。
     *
     * <p>一次执行至多一份产物（唯一键 uk_product_task）：该任务已有产物时**直接复用**既有行，
     * 不重复写对象存储、不新增行、不覆盖任务指针 —— 重复投递或补跑都不会产出第二份产物。
     *
     * <p>写入前校验本条链的数据源与当前启用的数据源一致（{@link ChainStorageSupport}）：排队与执行之间
     * 数据源发生切换、或任务被重新执行时，产物不会落到另一个数据源，任务按存储不一致落 FAILED。
     *
     * @param task              所属任务（回写 productId）
     * @param stage             产出环节
     * @param upstreamProductId 上游产物 ID（可空）
     * @param capabilitySnapshot 能力/策略版本快照（JSON 文本）
     * @param payload           产物本体（JSON 序列化后写产物存储）
     * @return 产物行（同任务重复调用时复用已有行）
     */
    public WbPipelineProduct persist(WbPipelineTask task, PipelineStage stage, Long upstreamProductId,
                                     String capabilitySnapshot, Object payload) {
        chainStorageSupport.requireStorageMatch(task.getFileResultId());
        WbPipelineProduct existing = pipelineProductDbService.getByTaskId(task.getId());
        if (NullUtil.isNotNull(existing)) {
            log.info("===> ProductPersistence 产物已存在，复用既有行, taskId={}, productId={}, artifactId={}",
                    task.getId(), existing.getId(), existing.getArtifactId());
            return existing;
        }
        String json = Objects.requireNonNull(JsonUtil.toJsonStr(Objects.requireNonNull(payload, "产物内容为空")),
                "产物序列化失败");
        byte[] content = json.getBytes(StandardCharsets.UTF_8);
        ObjectRef ref = fileStorage.putObject(content);

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
        pipelineProductDbService.save(product);
        pipelineTaskDbService.updateProductId(task.getId(), product.getId());
        return product;
    }
}
