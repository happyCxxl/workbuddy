package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.IndexBuildService;
import com.workbuddy.biz.service.IndexVersionService;
import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.support.IndexVersionStateSupport;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.enums.index.IndexVersionStatus;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.knowledge.AuditObjectType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.indexing.ComboSnapshot;
import com.workbuddy.worker.indexing.MilvusIndexPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 索引版本回退与回收实现：回退在单事务内切指针并调用构建补齐；回收先事务外 drop 集合，再短事务删行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexVersionServiceImpl implements IndexVersionService {

    private static final String AUDIT_OBJECT_TYPE = AuditObjectType.INDEX_VERSION.key();

    private final WbIndexVersionDbService indexVersionDbService;
    private final WbAuditLogDbService kbAuditLogDbService;
    private final MilvusIndexPort milvusIndexPort;
    private final IndexBuildService buildService;
    private final IndexVersionStateSupport stateSupport;
    private final TransactionTemplate transactionTemplate;

    /**
     * 回退到指定索引版本。
     *
     * @param versionId 版本 ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollback(Long versionId) {
        WbIndexVersion target = stateSupport.requireVersion(versionId);
        WbIndexSet set = stateSupport.requireSetOf(target);
        ComboSnapshot targetCombo = stateSupport.requireCombo(target);
        ThrowUtil.throwIf(targetCombo.isListScope(), ErrorCode.INDEX_FROZEN_SCOPE_PUBLISH_FORBIDDEN);
        Long currentId = set.getCurrentPublishedVersionId();
        ThrowUtil.throwIf(NullUtil.isNull(currentId), ErrorCode.INDEX_NOT_PUBLISHED, "当前无在线版本，无法回退");
        ThrowUtil.throwIf(Objects.equals(currentId, versionId), ErrorCode.PARAM_INVALID, "目标版本即当前在线版本");
        ThrowUtil.throwIf(IndexVersionStatus.BUILDING.name().equals(target.getStatus()),
                ErrorCode.INDEX_BUILDING_CONFLICT, "目标版本构建中，无法回退");

        LocalDateTime now = LocalDateTime.now();
        WbIndexVersion current = stateSupport.retire(currentId, now);
        stateSupport.pointTo(set, versionId);
        stateSupport.markOnline(target, now);

        kbAuditLogDbService.saveAudit(AuditActionType.ROLLBACK_INDEX, AUDIT_OBJECT_TYPE, versionId,
                NullUtil.isNull(current) ? null : current.getVersionNo(), target.getVersionNo());
        log.info("===> IndexVersionServiceImpl 索引回退完成（指针切回）, kbId={}, targetVersionNo={}, oldVersionNo={}",
                set.getKnowledgeBaseId(), target.getVersionNo(),
                NullUtil.isNull(current) ? null : current.getVersionNo());
        buildService.compensate(set, target);
    }

    /**
     * 回收索引候选：删除版本与其向量集合。
     *
     * @param versionId 版本 ID
     */
    @Override
    public void recycle(Long versionId) {
        WbIndexVersion version = stateSupport.requireVersion(versionId);
        WbIndexSet set = stateSupport.requireSetOf(version);
        ThrowUtil.throwIf(Objects.equals(versionId, set.getCurrentPublishedVersionId()),
                ErrorCode.INDEX_ONLINE_DELETE_FORBIDDEN);
        ThrowUtil.throwIf(IndexVersionStatus.BUILDING.name().equals(version.getStatus()),
                ErrorCode.INDEX_BUILDING_CONFLICT);
        ThrowUtil.throwIf(IndexVersionStatus.RETIRED.name().equals(version.getStatus()),
                ErrorCode.PARAM_INVALID, "已退役版本保留用于回退，探索期不回收");

        // 先回收 Milvus 集合（外部 IO，事务外）：回收失败版本行保留可重试；
        // 回收成功后再用短事务删版本行并写审计，DB 失败时版本行仍在。
        milvusIndexPort.drop(MilvusIndexPort.collectionName(set.getKnowledgeBaseId(), version.getVersionNo()));
        transactionTemplate.executeWithoutResult(status -> {
            kbAuditLogDbService.saveAudit(AuditActionType.RECYCLE_INDEX, AUDIT_OBJECT_TYPE, versionId,
                    version.getVersionNo(), null);
            indexVersionDbService.removeById(versionId);
        });
        log.info("===> IndexVersionServiceImpl 索引候选回收完成, kbId={}, versionId={}, versionNo={}",
                set.getKnowledgeBaseId(), versionId, version.getVersionNo());
    }
}
