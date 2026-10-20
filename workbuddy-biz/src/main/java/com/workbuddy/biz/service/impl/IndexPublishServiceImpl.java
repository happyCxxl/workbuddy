package com.workbuddy.biz.service.impl;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.IndexPublishService;
import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.support.IndexVersionStateSupport;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.dto.response.index.IndexVersionVO;
import com.workbuddy.common.enums.index.IndexVersionStatus;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.knowledge.AuditObjectType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.indexing.ComboSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 索引发布与查询实现：发布为单事务三级指针切换；版本列表/详情共用组合快照展开口径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexPublishServiceImpl implements IndexPublishService {

    private static final String AUDIT_OBJECT_TYPE = AuditObjectType.INDEX_VERSION.key();

    private final WbKnowledgeBaseDbService knowledgeBaseDbService;
    private final WbIndexSetDbService indexSetDbService;
    private final WbIndexVersionDbService indexVersionDbService;
    private final WbAuditLogDbService kbAuditLogDbService;
    private final IndexVersionStateSupport stateSupport;

    /**
     * 发布索引版本：原子切换线上版本。
     *
     * @param versionId 版本 ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(Long versionId) {
        WbIndexVersion version = stateSupport.requireVersion(versionId);
        ComboSnapshot versionCombo = stateSupport.requireCombo(version);
        ThrowUtil.throwIf(versionCombo.isListScope(), ErrorCode.INDEX_FROZEN_SCOPE_PUBLISH_FORBIDDEN);
        WbIndexSet set = stateSupport.requireSetOf(version);
        Long currentId = set.getCurrentPublishedVersionId();
        if (Objects.equals(currentId, versionId)) {
            log.info("===> IndexPublishServiceImpl 版本已在线，发布幂等跳过, versionId={}", versionId);
            return;
        }
        ThrowUtil.throwIf(!IndexVersionStatus.READY.name().equals(version.getStatus()),
                ErrorCode.INDEX_BUILDING_CONFLICT, "仅就绪版本可发布");

        LocalDateTime now = LocalDateTime.now();
        WbIndexVersion old = stateSupport.retire(currentId, now);
        stateSupport.pointTo(set, versionId);
        stateSupport.markOnline(version, now);

        WbKnowledgeBase kb = knowledgeBaseDbService.getById(set.getKnowledgeBaseId());
        if (NullUtil.isNotNull(kb) && NullUtil.isNull(kb.getPublishedIndexSetId())) {
            kb.setPublishedIndexSetId(set.getId());
            knowledgeBaseDbService.updateById(kb);
        }

        kbAuditLogDbService.saveAudit(AuditActionType.PUBLISH_INDEX, AUDIT_OBJECT_TYPE, versionId,
                NullUtil.isNull(old) ? null : old.getVersionNo(), version.getVersionNo());
        log.info("===> IndexPublishServiceImpl 索引发布完成, kbId={}, versionId={}, versionNo={}, oldVersionNo={}",
                set.getKnowledgeBaseId(), versionId, version.getVersionNo(),
                NullUtil.isNull(old) ? null : old.getVersionNo());
    }

    /**
     * 当前发布版本（versionNo → 集合名 kb_{kbId}_{versionNo} 直连检索）。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 当前发布的版本；没有则为 null
     */
    @Override
    public WbIndexVersion currentPublished(Long knowledgeBaseId) {
        WbIndexSet set = indexSetDbService.getByKb(knowledgeBaseId);
        if (NullUtil.isNull(set) || NullUtil.isNull(set.getCurrentPublishedVersionId())) {
            return null;
        }
        return indexVersionDbService.getById(set.getCurrentPublishedVersionId());
    }

    /**
     * 列出知识库的索引版本。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 索引版本列表
     */
    @Override
    public List<IndexVersionVO> listVersions(Long knowledgeBaseId) {
        WbIndexSet set = indexSetDbService.getByKb(knowledgeBaseId);
        if (NullUtil.isNull(set)) {
            return List.of();
        }
        return indexVersionDbService.listByIndexSetId(set.getId()).stream()
                .map(v -> toVersionVO(v, Objects.equals(v.getId(), set.getCurrentPublishedVersionId())))
                .toList();
    }

    /**
     * 索引版本详情。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param versionId       版本 ID
     * @return 版本详情
     */
    @Override
    public IndexVersionVO versionDetail(Long knowledgeBaseId, Long versionId) {
        WbIndexSet set = indexSetDbService.getByKb(knowledgeBaseId);
        ThrowUtil.throwIf(NullUtil.isNull(set), ErrorCode.INDEX_VERSION_NOT_FOUND);
        WbIndexVersion version = indexVersionDbService.getById(versionId);
        ThrowUtil.throwIf(NullUtil.isNull(version) || !Objects.equals(set.getId(), version.getIndexSetId()),
                ErrorCode.INDEX_VERSION_NOT_FOUND);
        return toVersionVO(version, Objects.equals(versionId, set.getCurrentPublishedVersionId()));
    }

    /** 版本行 → VO（组合快照结构化展开；无快照字段留空） */
    private IndexVersionVO toVersionVO(WbIndexVersion version, boolean online) {
        IndexVersionVO vo = new IndexVersionVO();
        vo.setId(version.getId());
        vo.setVersionNo(version.getVersionNo());
        if (StrUtil.isNotBlank(version.getComboSnapshot())) {
            ComboSnapshot combo = JsonUtil.toObject(version.getComboSnapshot(), ComboSnapshot.class);
            if (NullUtil.isNotNull(combo)) {
                vo.setFileScopeMode(combo.getFileScopeMode());
                vo.setFileResultIds(combo.getFileResultIds());
                vo.setChunkStrategy(combo.getChunkStrategy());
                vo.setEmbedStrategy(combo.getEmbedStrategy());
                vo.setShape(combo.getShape());
                vo.setStageStrategies(combo.getStageStrategies());
            }
        }
        vo.setChunkCount(version.getChunkCount());
        vo.setVectorCount(version.getVectorCount());
        vo.setStatus(version.getStatus());
        vo.setBuildError(version.getBuildError());
        vo.setTaskId(version.getTaskId());
        vo.setValidatedAt(version.getValidatedAt());
        vo.setPublishedAt(version.getPublishedAt());
        vo.setPublishedBy(version.getPublishedBy());
        vo.setRetiredAt(version.getRetiredAt());
        vo.setRetiredBy(version.getRetiredBy());
        vo.setCreateTime(version.getCreateTime());
        vo.setOnline(online);
        return vo;
    }
}
