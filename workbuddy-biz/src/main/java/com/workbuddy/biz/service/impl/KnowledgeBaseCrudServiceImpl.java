package com.workbuddy.biz.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.biz.service.KnowledgeBaseCrudService;
import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.support.access.KnowledgeBaseAccessSupport;
import com.workbuddy.biz.service.support.access.KnowledgeBaseBindingSupport;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.rules.KnowledgeBaseRules;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseCreateDTO;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseQueryDTO;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseUpdateDTO;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseStatsVO;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseVO;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.knowledge.AuditObjectType;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseSort;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseStatus;
import com.workbuddy.common.convert.knowledge.KnowledgeBaseConverter;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库管理用例实现：创建/更新/详情/分页/启停/逻辑删除与总览统计。
 *
 * <p>写操作在同一事务内落库并写审计；分页与统计统一按当前用户归属收口
 * （见 {@link KnowledgeBaseAccessSupport#visibleOwnerId()}）。列表额外回填文档数、
 * 已发布索引版本与三类策略绑定摘要，均按整页批量查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseCrudServiceImpl implements KnowledgeBaseCrudService {

    /** 审计对象类型 */
    private static final String AUDIT_OBJECT_TYPE = AuditObjectType.KNOWLEDGE_BASE.key();

    private final WbKnowledgeBaseDbService knowledgeBaseDbService;

    private final WbAuditLogDbService kbAuditLogDbService;

    private final WbFileResultDbService kbFileResultDbService;

    private final WbIndexSetDbService indexSetDbService;

    private final WbIndexVersionDbService indexVersionDbService;

    private final KnowledgeBaseAccessSupport accessSupport;

    private final KnowledgeBaseBindingSupport bindingSupport;

    private final KnowledgeBaseConverter knowledgeBaseConverter;

    /**
     * 新建知识库（默认启用状态），并写 CREATE 审计。
     *
     * @param dto 新建请求
     * @return 新知识库 ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(KnowledgeBaseCreateDTO dto) {
        WbKnowledgeBase kb = knowledgeBaseConverter.toEntity(dto);
        kb.setStatus(KnowledgeBaseStatus.ACTIVE.getCode());
        kb.setUserId(accessSupport.requireCurrentUserId());
        knowledgeBaseDbService.save(kb);
        kbAuditLogDbService.saveAudit(AuditActionType.CREATE, AUDIT_OBJECT_TYPE, kb.getId(), null,
                JsonUtil.toJsonStr(kb));
        log.info("===> KnowledgeBaseCrudServiceImpl create 创建知识库, id={}, name={}", kb.getId(), kb.getName());
        return kb.getId();
    }

    /**
     * 修改知识库基本信息，并写 UPDATE 审计。
     *
     * @param dto 修改请求
     * @return 更新成功为 true
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean update(KnowledgeBaseUpdateDTO dto) {
        WbKnowledgeBase kb = accessSupport.getAccessibleById(dto.getId());
        String beforeJson = JsonUtil.toJsonStr(kb);
        kb.setName(dto.getName());
        kb.setDescription(dto.getDescription());
        kb.setStrategyBindingEnabled(dto.getStrategyBindingEnabled());
        knowledgeBaseDbService.updateById(kb);
        kbAuditLogDbService.saveAudit(AuditActionType.UPDATE, AUDIT_OBJECT_TYPE, kb.getId(),
                beforeJson, JsonUtil.toJsonStr(kb));
        log.info("===> KnowledgeBaseCrudServiceImpl update 更新知识库, id={}, name={}", kb.getId(), kb.getName());
        return true;
    }

    /**
     * 知识库详情：回填文档数与三类策略绑定摘要。
     *
     * @param id 知识库 ID
     * @return 知识库详情
     */
    @Override
    public KnowledgeBaseVO detail(Long id) {
        KnowledgeBaseVO vo = knowledgeBaseConverter.toVO(accessSupport.getAccessibleById(id));
        bindingSupport.fillStrategyBindings(vo);
        vo.setDocumentCount(kbFileResultDbService.countByKb(id));
        return vo;
    }

    /**
     * 分页查知识库：按当前用户可见范围过滤并回填整页的文档数、已发布索引版本与策略绑定摘要。
     *
     * @param query 查询条件
     * @return 知识库分页
     */
    @Override
    public IPage<KnowledgeBaseVO> page(KnowledgeBaseQueryDTO query) {
        // 可见范围在服务端定：普通用户只查自己创建的，管理员不限（见 KnowledgeBaseRules.visibleOwnerId）
        IPage<WbKnowledgeBase> page = knowledgeBaseDbService.pageByCondition(query.currentOrDefault(),
                query.sizeOrDefault(), query.getName(), query.getStatus(), KnowledgeBaseSort.of(query.getSort()),
                accessSupport.visibleOwnerId());
        IPage<KnowledgeBaseVO> voPage = page.convert(knowledgeBaseConverter::toVO);
        List<KnowledgeBaseVO> records = voPage.getRecords();
        bindingSupport.fillStrategyBindings(records);
        fillDocumentCounts(records);
        fillPublishedIndexVersions(records);
        return voPage;
    }

    /**
     * 知识库总览统计：库数、启用数与文档数使用同一可见范围。
     *
     * @return 统计视图
     */
    @Override
    public KnowledgeBaseStatsVO stats() {
        KnowledgeBaseStatsVO vo = new KnowledgeBaseStatsVO();
        // 口径与列表页一致：普通用户只数自己创建的库，管理员数全部
        Long ownerId = accessSupport.visibleOwnerId();
        vo.setKnowledgeBaseCount(knowledgeBaseDbService.countByStatus(null, ownerId));
        vo.setEnabledCount(knowledgeBaseDbService.countByStatus(KnowledgeBaseStatus.ACTIVE.getCode(), ownerId));
        // 文档数与「知识库」必须同一范围：文档表只有 knowledge_base_id，没有归属列，
        // 先取可见库 ID 再按集合计数
        vo.setDocumentCount(
                kbFileResultDbService.countByKbIds(knowledgeBaseDbService.listIdsByOwner(ownerId)));
        return vo;
    }

    /**
     * 停用知识库并写 DISABLE 审计。
     *
     * @param id 知识库 ID
     * @return 变更成功为 true
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean disable(Long id) {
        WbKnowledgeBase kb = accessSupport.getAccessibleById(id);
        KnowledgeBaseRules.checkCanDisable(kb);
        String beforeJson = JsonUtil.toJsonStr(kb);
        kb.setStatus(KnowledgeBaseStatus.DISABLED.getCode());
        knowledgeBaseDbService.updateById(kb);
        kbAuditLogDbService.saveAudit(AuditActionType.DISABLE, AUDIT_OBJECT_TYPE, id,
                beforeJson, JsonUtil.toJsonStr(kb));
        log.info("===> KnowledgeBaseCrudServiceImpl disable 停用知识库, id={}", id);
        return true;
    }

    /**
     * 启用知识库并写 ENABLE 审计。
     *
     * @param id 知识库 ID
     * @return 变更成功为 true
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean enable(Long id) {
        WbKnowledgeBase kb = accessSupport.getAccessibleById(id);
        KnowledgeBaseRules.checkCanEnable(kb);
        String beforeJson = JsonUtil.toJsonStr(kb);
        kb.setStatus(KnowledgeBaseStatus.ACTIVE.getCode());
        knowledgeBaseDbService.updateById(kb);
        kbAuditLogDbService.saveAudit(AuditActionType.ENABLE, AUDIT_OBJECT_TYPE, id,
                beforeJson, JsonUtil.toJsonStr(kb));
        log.info("===> KnowledgeBaseCrudServiceImpl enable 启用知识库, id={}", id);
        return true;
    }

    /**
     * 逻辑删除知识库并写 DELETE 审计。
     *
     * @param id 知识库 ID
     * @return 删除成功为 true
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long id) {
        WbKnowledgeBase kb = accessSupport.getAccessibleById(id);
        knowledgeBaseDbService.removeById(id);
        kbAuditLogDbService.saveAudit(AuditActionType.DELETE, AUDIT_OBJECT_TYPE, id, JsonUtil.toJsonStr(kb), null);
        log.info("===> KnowledgeBaseCrudServiceImpl delete 逻辑删除知识库, id={}", id);
        return true;
    }

    /**
     * 批量回填当页知识库的已发布索引版本号（两次查询覆盖整页，避免逐行查询）。
     *
     * <p>链路：wb_knowledge_base.published_index_set_id → wb_knowledge_index_set.current_published_version_id
     * → wb_knowledge_index_version.version_no。
     *
     * @param records 当页 VO（原地回填 publishedIndexVersion）
     */
    private void fillPublishedIndexVersions(List<KnowledgeBaseVO> records) {
        List<Long> kbIds = records.stream()
                .filter(vo -> NullUtil.isNotNull(vo.getPublishedIndexSetId()))
                .map(KnowledgeBaseVO::getId)
                .collect(Collectors.toList());
        if (CollUtil.isEmpty(kbIds)) {
            return;
        }
        Map<Long, WbIndexSet> setByKb = indexSetDbService.listByKbIds(kbIds);
        List<Long> versionIds = setByKb.values().stream()
                .map(WbIndexSet::getCurrentPublishedVersionId)
                .filter(NullUtil::isNotNull)
                .distinct()
                .collect(Collectors.toList());
        if (CollUtil.isEmpty(versionIds)) {
            return;
        }
        Map<Long, String> versionNoById = indexVersionDbService.listByIds(versionIds).stream()
                .collect(Collectors.toMap(WbIndexVersion::getId, WbIndexVersion::getVersionNo, (a, b) -> a));
        for (KnowledgeBaseVO vo : records) {
            WbIndexSet set = setByKb.get(vo.getId());
            if (NullUtil.isNull(set)) {
                continue;
            }
            vo.setPublishedIndexVersion(versionNoById.get(set.getCurrentPublishedVersionId()));
        }
    }

    /**
     * 批量回填当页知识库的文档数（一次分组查询，避免逐行查询）。
     *
     * @param records 当页 VO（原地回填 documentCount）
     */
    private void fillDocumentCounts(List<KnowledgeBaseVO> records) {
        if (CollUtil.isEmpty(records)) {
            return;
        }
        List<Long> ids = records.stream().map(KnowledgeBaseVO::getId).collect(Collectors.toList());
        Map<Long, Long> counts = kbFileResultDbService.countGroupByKb(ids);
        for (KnowledgeBaseVO vo : records) {
            vo.setDocumentCount(counts.getOrDefault(vo.getId(), 0L));
        }
    }
}
