package com.workbuddy.biz.service.support.index;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.common.convert.knowledge.PipelineConverter;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.enums.index.IndexVersionStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.indexing.ComboSnapshot;
import com.workbuddy.worker.indexing.IndexRow;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 索引版本状态支撑：版本行读取、组合快照断言、创建/活跃判定、退役与在线切换、发布指针与活账本。
 * 发布、回退、构建共用同一套状态口径，避免状态列与指针语义在多处漂移。
 */
@Component
@RequiredArgsConstructor
public class IndexVersionStateSupport {

    private final WbIndexSetDbService indexSetDbService;
    private final WbIndexVersionDbService indexVersionDbService;

    private final PipelineConverter knowledgeConverter;


    /**
     * 按 ID 读取版本行。
     *
     * @param versionId 版本 ID
     * @return 版本行
     */
    public WbIndexVersion requireVersion(Long versionId) {
        WbIndexVersion version = indexVersionDbService.getById(versionId);
        ThrowUtil.throwIf(NullUtil.isNull(version), ErrorCode.INDEX_VERSION_NOT_FOUND);
        return version;
    }

    /**
     * 读取版本行所属索引集合。
     *
     * @param version 版本行
     * @return 索引集合
     */
    public WbIndexSet requireSetOf(WbIndexVersion version) {
        WbIndexSet set = indexSetDbService.getById(version.getIndexSetId());
        ThrowUtil.throwIf(NullUtil.isNull(set), ErrorCode.INDEX_VERSION_NOT_FOUND);
        return set;
    }

    /**
     * 读取版本组合快照并断言三环节策略维度齐全。
     *
     * @param version 版本行
     * @return 组合快照
     */
    public ComboSnapshot requireCombo(WbIndexVersion version) {
        ComboSnapshot combo = JsonUtil.toObject(version.getComboSnapshot(), ComboSnapshot.class);
        ThrowUtil.throwIf(NullUtil.isNull(combo), ErrorCode.PARAM_INVALID, "版本组合快照缺失");
        combo.requireStageStrategies();
        return combo;
    }

    /**
     * 创建并保存版本行（CREATED；版本号唯一键冲突时重取一次）。
     *
     * @param set         所属索引集合
     * @param comboJson   组合快照 JSON
     * @param chunkCount  初始片数（null 表示不写）
     * @param vectorCount 初始向量数（null 表示不写）
     * @return 已保存的版本行
     */
    public WbIndexVersion createVersion(WbIndexSet set, String comboJson, Integer chunkCount,
                                        Integer vectorCount) {
        WbIndexVersion version = knowledgeConverter.toIndexVersion(set,
                indexVersionDbService.nextVersionNo(set.getId()), comboJson, chunkCount, vectorCount);
        try {
            indexVersionDbService.save(version);
        } catch (DuplicateKeyException e) {
            // uk_set_version 并发兜底：版本号冲突重取一次
            version.setVersionNo(indexVersionDbService.nextVersionNo(set.getId()));
            indexVersionDbService.save(version);
        }
        return version;
    }

    /**
     * 同组合且未终结（CREATED/BUILDING/READY/ONLINE）的版本行。
     *
     * @param indexSetId 索引集合 ID
     * @param comboJson  组合快照 JSON
     * @return 活跃版本；没有则为 null
     */
    public WbIndexVersion findActiveVersion(Long indexSetId, String comboJson) {
        return indexVersionDbService.listByIndexSetId(indexSetId).stream()
                .filter(v -> comboJson.equals(v.getComboSnapshot()))
                .filter(v -> Set.of(IndexVersionStatus.CREATED.name(),
                                IndexVersionStatus.BUILDING.name(),
                                IndexVersionStatus.READY.name(),
                                IndexVersionStatus.ONLINE.name())
                        .contains(v.getStatus()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 退役在线版本（已退役跳过），并落库留痕。
     *
     * @param versionId 版本 ID（可空）
     * @param now       退役时间
     * @return 原版本行；不存在时为 null
     */
    public WbIndexVersion retire(Long versionId, LocalDateTime now) {
        WbIndexVersion version = NullUtil.isNull(versionId) ? null : indexVersionDbService.getById(versionId);
        if (NullUtil.isNotNull(version) && !IndexVersionStatus.RETIRED.name().equals(version.getStatus())) {
            version.setStatus(IndexVersionStatus.RETIRED.name());
            version.setRetiredAt(now);
            indexVersionDbService.updateById(version);
        }
        return version;
    }

    /**
     * 标记版本在线：重记发布时间并清空退役留痕，随后落库。
     *
     * @param version 版本行
     * @param now     发布时间
     */
    public void markOnline(WbIndexVersion version, LocalDateTime now) {
        version.setStatus(IndexVersionStatus.ONLINE.name());
        version.setPublishedAt(now);
        version.setRetiredAt(null);
        version.setRetiredBy(null);
        indexVersionDbService.updateById(version);
    }

    /**
     * 切换集合二级发布指针。
     *
     * @param set       索引集合
     * @param versionId 目标版本 ID
     */
    public void pointTo(WbIndexSet set, Long versionId) {
        indexSetDbService.updatePublishedVersion(set.getId(), versionId);
    }

    /**
     * 版本失败回写（buildError 截断 1024）。
     *
     * @param version    版本行
     * @param buildError 失败原因
     */
    public void markFailed(WbIndexVersion version, String buildError) {
        version.setStatus(IndexVersionStatus.FAILED.name());
        version.setBuildError(StrUtil.maxLength(buildError, 1024));
        indexVersionDbService.updateById(version);
    }

    /**
     * 活账本：追加后累计片数与有效向量数，并清空失败原因。
     *
     * @param version 版本行
     * @param rows    本次追加行
     */
    public void updateLedger(WbIndexVersion version, List<IndexRow> rows) {
        version.setChunkCount(ObjectUtil.defaultIfNull(version.getChunkCount(), 0) + rows.size());
        int vectors = (int) rows.stream()
                .filter(r -> NullUtil.isNotNull(r.getVector()) && !r.getVector().isEmpty()).count();
        version.setVectorCount(ObjectUtil.defaultIfNull(version.getVectorCount(), 0) + vectors);
        version.setBuildError(null);
        indexVersionDbService.updateById(version);
    }

    /**
     * 组合全等判定（范围 × 形态 × stageStrategies）。
     *
     * @param a 组合 A
     * @param b 组合 B
     * @return 全等为 true
     */
    public boolean comboEquals(ComboSnapshot a, ComboSnapshot b) {
        return Objects.equals(a.getShape(), b.getShape())
                && Objects.equals(a.getFileScopeMode(), b.getFileScopeMode())
                && Objects.equals(a.getFileResultIds(), b.getFileResultIds())
                && Objects.equals(a.getStageStrategies(), b.getStageStrategies());
    }
}
