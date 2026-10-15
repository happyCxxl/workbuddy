package com.workbuddy.biz.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.service.KnowledgeBaseService;
import com.workbuddy.biz.service.StrategyVersionService;
import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.db.WbStrategyBindingDbService;
import com.workbuddy.biz.service.db.WbSubmitLogDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.support.InputVoAssembler;
import com.workbuddy.biz.service.support.TaskVoAssembler;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.common.domain.entity.WbStrategyBinding;
import com.workbuddy.common.domain.entity.WbSubmitLog;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.input.FileMetadata;
import com.workbuddy.common.domain.input.FileValidationResult;
import com.workbuddy.common.domain.rules.KnowledgeBaseRules;
import com.workbuddy.common.domain.rules.StageFunnelRules;
import com.workbuddy.common.domain.storage.StorageRef;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.dto.request.input.FileSubmitRequest;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseCreateDto;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseUpdateDto;
import com.workbuddy.common.dto.request.knowledge.StrategyBindingUpdateDto;
import com.workbuddy.common.dto.request.knowledge.StrategyBindingsUpdateRequest;
import com.workbuddy.common.dto.response.input.FileResultVO;
import com.workbuddy.common.dto.response.input.FileSubmitResponse;
import com.workbuddy.common.dto.response.input.SubmitLogVO;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseStatsVO;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseVO;
import com.workbuddy.common.dto.response.knowledge.StrategyBindingVO;
import com.workbuddy.common.dto.response.task.StageStatusVO;
import com.workbuddy.common.enums.base.DelFlag;
import com.workbuddy.common.enums.input.SubmitStatus;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.knowledge.AuditObjectType;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseSort;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseStatus;
import com.workbuddy.common.enums.knowledge.StrategyBindingSwitch;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.common.utils.SecurityUtil;
import com.workbuddy.common.utils.JsonUtil;

import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.service.FileStorage;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import com.workbuddy.worker.embedding.strategy.EmbedStrategy;
import com.workbuddy.worker.input.FileValidatorPort;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 知识库应用服务实现：管理闭环（创建/更新/详情/分页/启停/逻辑删除 + 同事务审计）
 * + 文档输入编排（提交建档、提交记录、文件结果查询）。
 *
 * <p>文档输入链路的实现要点：
 * <ul>
 *   <li>幂等：requestId 唯一约束 + 查询返回已有；并发冲突（DuplicateKey）转幂等回放，不报系统错误；</li>
 *   <li>归属失败（知识库不存在/停用/已删）抛 KnowledgeException（40401/40421），不记提交日志；</li>
 *   <li>文件校验不通过（含文件不存在）只记 wb_submit_log(FAIL)，正常返回 FAIL 日志；</li>
 *   <li>建档三写（wb_source_file / wb_file_result / wb_submit_log）同一 {@code @Transactional}
 *       （手动逐环节口径：不登记任务，解析由页面手动触发）；</li>
 *   <li>同一 fileId 复用 wb_source_file（uk_file_id；并发撞键时重查复用），每次提交新建 wb_file_result。</li>
 * </ul>
 *
 * @author cxxl
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    /** 审计对象类型 */
    private static final String AUDIT_OBJECT_TYPE = AuditObjectType.KNOWLEDGE_BASE.key();

    /** 用户归属默认值（检索强制过滤口径，当前统一 ADMIN） */
    private static final String DEFAULT_OWNER = "ADMIN";

    private final WbKnowledgeBaseDbService knowledgeBaseDbService;

    private final WbAuditLogDbService kbAuditLogDbService;

    private final WbStrategyBindingDbService strategyBindingDbService;

    private final WbPipelineStrategyVersionDbService strategyVersionDbService;

    private final WbFileResultDbService kbFileResultDbService;

    private final WbIndexSetDbService indexSetDbService;

    private final WbIndexVersionDbService indexVersionDbService;

    private final WbSourceFileDbService sourceFileDbService;

    private final WbSubmitLogDbService submitLogDbService;

    private final WbPipelineTaskDbService pipelineTaskDbService;

    private final FileValidatorPort fileValidator;

    private final FileStorage fileStorage;

    private final StorageRouter storageRouter;

    private final InputVoAssembler inputVoAssembler;

    private final TaskVoAssembler taskVoAssembler;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(KnowledgeBaseCreateDto dto) {
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setName(dto.getName());
        kb.setDescription(dto.getDescription());
        kb.setStatus(KnowledgeBaseStatus.ACTIVE.getCode());
        kb.setStrategyBindingEnabled(resolveBindingEnabled(dto.getStrategyBindingEnabled()));
        kb.setUserId(requireCurrentUserId());
        knowledgeBaseDbService.save(kb);
        kbAuditLogDbService.saveAudit(AuditActionType.CREATE, AUDIT_OBJECT_TYPE, kb.getId(), null,
                JsonUtil.toJsonStr(kb));
        log.info("===> KnowledgeBaseServiceImpl create 创建知识库, id={}, name={}", kb.getId(), kb.getName());
        return kb.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean update(KnowledgeBaseUpdateDto dto) {
        WbKnowledgeBase kb = getAccessibleById(dto.getId());
        String beforeJson = JsonUtil.toJsonStr(kb);
        kb.setName(dto.getName());
        kb.setDescription(dto.getDescription());
        kb.setStrategyBindingEnabled(resolveBindingEnabled(dto.getStrategyBindingEnabled()));
        knowledgeBaseDbService.updateById(kb);
        kbAuditLogDbService.saveAudit(AuditActionType.UPDATE, AUDIT_OBJECT_TYPE, kb.getId(),
                beforeJson, JsonUtil.toJsonStr(kb));
        log.info("===> KnowledgeBaseServiceImpl update 更新知识库, id={}, name={}", kb.getId(), kb.getName());
        return true;
    }

    @Override
    public KnowledgeBaseVO detail(Long id) {
        KnowledgeBaseVO vo = toVO(getAccessibleById(id));
        fillStrategyBindings(vo);
        vo.setDocumentCount(kbFileResultDbService.countByKb(id));
        return vo;
    }

    @Override
    public IPage<KnowledgeBaseVO> page(long current, long size, String name, Integer status,
                                       KnowledgeBaseSort sort) {
        // 可见范围在服务端定：普通用户只查自己创建的，管理员不限（见 KnowledgeBaseRules.visibleOwnerId）
        IPage<WbKnowledgeBase> page =
                knowledgeBaseDbService.pageByCondition(current, size, name, status, sort, visibleOwnerId());
        Page<KnowledgeBaseVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        List<KnowledgeBaseVO> records = page.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList());
        fillStrategyBindings(records);
        fillDocumentCounts(records);
        fillPublishedIndexVersions(records);
        voPage.setRecords(records);
        return voPage;
    }

    @Override
    public KnowledgeBaseStatsVO stats() {
        KnowledgeBaseStatsVO vo = new KnowledgeBaseStatsVO();
        // 口径与列表页一致：普通用户只数自己创建的库，管理员数全部
        Long ownerId = visibleOwnerId();
        vo.setKnowledgeBaseCount(knowledgeBaseDbService.countByStatus(null, ownerId));
        vo.setEnabledCount(knowledgeBaseDbService.countByStatus(KnowledgeBaseStatus.ACTIVE.getCode(), ownerId));
        // 文档数与「知识库」必须同一范围：文档表只有 knowledge_base_id，没有归属列，
        // 先取可见库 ID 再按集合计数
        vo.setDocumentCount(
                kbFileResultDbService.countByKbIds(knowledgeBaseDbService.listIdsByOwner(ownerId)));
        return vo;
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean disable(Long id) {
        WbKnowledgeBase kb = getAccessibleById(id);
        KnowledgeBaseRules.checkCanDisable(kb);
        String beforeJson = JsonUtil.toJsonStr(kb);
        kb.setStatus(KnowledgeBaseStatus.DISABLED.getCode());
        knowledgeBaseDbService.updateById(kb);
        kbAuditLogDbService.saveAudit(AuditActionType.DISABLE, AUDIT_OBJECT_TYPE, id,
                beforeJson, JsonUtil.toJsonStr(kb));
        log.info("===> KnowledgeBaseServiceImpl disable 停用知识库, id={}", id);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean enable(Long id) {
        WbKnowledgeBase kb = getAccessibleById(id);
        KnowledgeBaseRules.checkCanEnable(kb);
        String beforeJson = JsonUtil.toJsonStr(kb);
        kb.setStatus(KnowledgeBaseStatus.ACTIVE.getCode());
        knowledgeBaseDbService.updateById(kb);
        kbAuditLogDbService.saveAudit(AuditActionType.ENABLE, AUDIT_OBJECT_TYPE, id,
                beforeJson, JsonUtil.toJsonStr(kb));
        log.info("===> KnowledgeBaseServiceImpl enable 启用知识库, id={}", id);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long id) {
        WbKnowledgeBase kb = getAccessibleById(id);
        knowledgeBaseDbService.removeById(id);
        kbAuditLogDbService.saveAudit(AuditActionType.DELETE, AUDIT_OBJECT_TYPE, id, JsonUtil.toJsonStr(kb), null);
        log.info("===> KnowledgeBaseServiceImpl delete 逻辑删除知识库, id={}", id);
        return true;
    }

    @Override
    public StrategyBindingVO strategyBinding(Long id, String strategyType) {
        WbKnowledgeBase kb = getAccessibleById(id);
        ThrowUtil.throwIf(StrUtil.isBlank(strategyType)
                        || !StrategyVersionService.BINDABLE_TYPES.contains(strategyType),
                ErrorCode.PARAM_INVALID, "未知策略类型: " + strategyType);
        StrategyBindingVO vo = new StrategyBindingVO();
        vo.setStrategyType(strategyType);
        WbStrategyBinding binding = strategyBindingDbService.getByKbAndType(kb.getId(), strategyType);
        if (NullUtil.isNotNull(binding)) {
            WbPipelineStrategyVersion version = strategyVersionDbService.getById(binding.getStrategyVersionId());
            if (NullUtil.isNotNull(version)) {
                vo.setStrategyVersionId(version.getId());
                vo.setStrategyName(version.getName());
                vo.setStrategyVersion(version.getVersion());
            }
        }
        return vo;
    }

    @Override
    public List<StrategyBindingVO> strategyBindings(String strategyType) {
        ThrowUtil.throwIf(StrUtil.isBlank(strategyType)
                        || !StrategyVersionService.BINDABLE_TYPES.contains(strategyType),
                ErrorCode.PARAM_INVALID, "未知策略类型: " + strategyType);

        // knowledgeBaseIds 传 null = 不限库，直接用一条 SQL 取回该类型下全部绑定行
        // （无需先查知识库列表，少一次查询）
        List<WbStrategyBinding> bindings = strategyBindingDbService.listActiveByTypeAndKbIds(strategyType, null);
        if (bindings.isEmpty()) {
            return List.of();
        }

        // 一次批量取版本，避免逐行查（N+1）
        Map<Long, WbPipelineStrategyVersion> versionById = versionsOf(bindings);

        List<StrategyBindingVO> result = new ArrayList<>(bindings.size());
        for (WbStrategyBinding binding : bindings) {
            // 版本被删或已停用时跳过：与单体查询 strategyBinding 的口径一致
            // （它查不到版本时也只返回 strategyType，不返回半截数据）
            WbPipelineStrategyVersion version = versionById.get(binding.getStrategyVersionId());
            if (NullUtil.isNull(version)) {
                continue;
            }
            StrategyBindingVO vo = new StrategyBindingVO();
            vo.setKnowledgeBaseId(binding.getKnowledgeBaseId());
            vo.setStrategyType(strategyType);
            vo.setStrategyVersionId(version.getId());
            vo.setStrategyName(version.getName());
            vo.setStrategyVersion(version.getVersion());
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean bindStrategy(Long id, StrategyBindingUpdateDto dto) {
        WbKnowledgeBase kb = getAccessibleById(id);
        ThrowUtil.throwIf(isBindingDisabled(kb),
                ErrorCode.PARAM_INVALID, "该知识库已关闭策略绑定（评测模式），禁止绑定策略");
        String type = dto.getStrategyType();
        ThrowUtil.throwIf(!StrategyVersionService.BINDABLE_TYPES.contains(type),
                ErrorCode.PARAM_INVALID, "未知策略类型: " + type);

        // 解绑：绑定行逻辑删除（无绑定行时幂等成功）
        if (NullUtil.isNull(dto.getStrategyVersionId())) {
            WbStrategyBinding existing = strategyBindingDbService.getByKbAndType(id, type);
            String before = bindingSummary(existing);
            if (NullUtil.isNotNull(existing)) {
                existing.setDelFlag(DelFlag.DELETED.getCode());
                strategyBindingDbService.updateById(existing);
            }
            kbAuditLogDbService.saveAudit(AuditActionType.BIND, AUDIT_OBJECT_TYPE, id,
                    type + "=" + before, null);
            log.info("===> KnowledgeBaseServiceImpl bindStrategy 解绑知识库策略, id={}, type={}", id, type);
            return true;
        }

        WbPipelineStrategyVersion version = strategyVersionDbService.getById(dto.getStrategyVersionId());
        ThrowUtil.throwIf(NullUtil.isNull(version), ErrorCode.PARAM_INVALID, "策略版本不存在");
        ThrowUtil.throwIf(!type.equals(version.getType()), ErrorCode.PARAM_INVALID, "策略类型与所选版本不匹配");
        ThrowUtil.throwIf(!RowStatus.ACTIVE.name().equals(version.getStatus()),
                ErrorCode.PARAM_INVALID, "策略已停用，请先启用后再绑定");

        // 绑定/重绑：复用原行（含已逻辑删除行），uk 不被占用
        WbStrategyBinding existing = strategyBindingDbService.getAnyByKbAndType(id, type);
        String before = bindingSummary(existing);
        if (NullUtil.isNull(existing)) {
            WbStrategyBinding binding = new WbStrategyBinding();
            binding.setKnowledgeBaseId(id);
            binding.setStrategyType(type);
            binding.setStrategyVersionId(dto.getStrategyVersionId());
            strategyBindingDbService.save(binding);
        } else {
            existing.setDelFlag(DelFlag.NORMAL.getCode());
            existing.setStrategyVersionId(dto.getStrategyVersionId());
            strategyBindingDbService.updateById(existing);
        }
        String after = version.getName() + "-" + version.getVersion();
        kbAuditLogDbService.saveAudit(AuditActionType.BIND, AUDIT_OBJECT_TYPE, id,
                type + "=" + before, type + "=" + after);
        log.info("===> KnowledgeBaseServiceImpl bindStrategy 绑定知识库策略, id={}, type={}, versionId={}",
                id, type, dto.getStrategyVersionId());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean bindStrategies(Long id, StrategyBindingsUpdateRequest request) {
        WbKnowledgeBase kb = getAccessibleById(id);
        ThrowUtil.throwIf(isBindingDisabled(kb),
                ErrorCode.PARAM_INVALID, "该知识库已关闭策略绑定（评测模式），禁止绑定策略");
        List<StrategyBindingsUpdateRequest.StrategyBindItem> bindings = request.getBindings();
        ThrowUtil.throwIf(NullUtil.isNull(bindings) || bindings.isEmpty(),
                ErrorCode.PARAM_INVALID, "绑定列表不能为空");
        // 白名单 + 请求内类型不重复
        Set<String> seen = new HashSet<>();
        for (StrategyBindingsUpdateRequest.StrategyBindItem item : bindings) {
            String type = item.getStrategyType();
            ThrowUtil.throwIf(!StrategyVersionService.BINDABLE_TYPES.contains(type),
                    ErrorCode.PARAM_INVALID, "未知策略类型: " + type);
            ThrowUtil.throwIf(!seen.add(type), ErrorCode.PARAM_INVALID, "策略类型重复: " + type);
        }
        // 本接口是「设置整套绑定」：必须一次给全三件套，且每项都要有版本
        // （允许"开关开着但还没绑"的中间态由不调用本接口来表达；一旦调用就必须给全）
        ThrowUtil.throwIf(!seen.containsAll(StrategyVersionService.BINDABLE_TYPES),
                ErrorCode.PARAM_INVALID, "需一次设置全部可绑定策略（预处理 / 切片 / 向量化）");
        for (StrategyBindingsUpdateRequest.StrategyBindItem item : bindings) {
            ThrowUtil.throwIf(NullUtil.isNull(item.getStrategyVersionId()),
                    ErrorCode.PARAM_INVALID, "策略版本不能为空: " + item.getStrategyType());
        }
        // 局部更新：逐类型复用单类型绑定逻辑（校验/upsert/审计）；外层事务覆盖，任一项失败整体回滚
        for (StrategyBindingsUpdateRequest.StrategyBindItem item : bindings) {
            StrategyBindingUpdateDto dto = new StrategyBindingUpdateDto();
            dto.setStrategyType(item.getStrategyType());
            dto.setStrategyVersionId(item.getStrategyVersionId());
            bindStrategy(id, dto);
        }
        log.info("===> KnowledgeBaseServiceImpl bindStrategies 批量绑定知识库策略集合, id={}, count={}",
                id, bindings.size());
        return true;
    }

    /**
     * 提交文件：幂等检查 → 归属校验 → 文件校验 → 建档三写。
     *
     * <p>五步时序：
     * <ol>
     *   <li>幂等：requestId 已存在直接回放已有记录；</li>
     *   <li>归属校验：知识库未删除 → **当前用户可访问**（非本人/非管理员按不存在处理）→ 启用；</li>
     *   <li>文件校验：单次取流内完成 sha256 + Tika 魔数识别 + 大小双源比对 + 加密探测；</li>
     *   <li>建档三写：sourceFile 按 fileId 复用，fileResult 每次提交新建，submitLog 记录流水；</li>
     *   <li>组装响应返回。</li>
     * </ol>
     *
     * <p>边界：本方法不登记处理任务——解析由页面在文件结果页手动触发；方法整体同一事务。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param request         提交请求（fileId + requestId）
     * @return 提交响应：新建提交 pipelineTaskId 为 null；
     *         幂等回放时回填该文件结果已有的解析任务 ID（可能为 null）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileSubmitResponse submit(Long knowledgeBaseId, FileSubmitRequest request) {
        ThrowUtil.throwIf(StrUtil.isBlank(request.getRequestId()), ErrorCode.REQUEST_ID_MISSING);

        // ① 幂等：同一请求重复到达返回已有记录
        WbSubmitLog existing = submitLogDbService.getByRequestId(request.getRequestId());
        if (NullUtil.isNotNull(existing)) {
            log.info("===> KnowledgeBaseServiceImpl submit 幂等回放, requestId={}", request.getRequestId());
            return inputVoAssembler.toSubmitResponse(existing, findParseTaskId(existing.getFileResultId()));
        }

        // ② 校验：存在/未删除 → 归属（非本人可访问的库按"不存在"处理，且先于状态校验，
        //    避免用错误码差异反推出别人某个库是启用还是停用）→ 启用
        WbKnowledgeBase kb = getAccessibleById(knowledgeBaseId);
        KnowledgeBaseRules.checkCanSubmit(kb);

        // ③ 文件校验：失败只记 FAIL 日志，不建结果、不建任务
        long validationStart = System.currentTimeMillis();
        FileValidationResult validationResult = fileValidator.validate(request.getFileId());
        if (!validationResult.isPassed()) {
            return recordFailAndReturn(knowledgeBaseId, request, validationResult.getFailReason().name());
        }
        log.info("===> KnowledgeBaseServiceImpl submit 文件校验通过, kbId={}, fileId={}, format={}, size={}B, sha256={}, 校验耗时={}ms",
                knowledgeBaseId, request.getFileId(), validationResult.getFormat(),
                validationResult.getSize(), validationResult.getSha256(),
                System.currentTimeMillis() - validationStart);

        // ④ 建档三写（同一事务；不登记任务——手动逐环节口径：解析由页面触发）
        long buildStart = System.currentTimeMillis();
        FileMetadata metadata = fileStorage.metadata(request.getFileId());
        Long userId = currentUserId();

        WbSourceFile sourceFile = findOrCreateSourceFile(request, validationResult, metadata, userId);

        WbFileResult fileResult = new WbFileResult();
        fileResult.setKnowledgeBaseId(kb.getId());
        fileResult.setOwner(DEFAULT_OWNER);
        fileResult.setSourceFileId(sourceFile.getId());
        fileResult.setUserId(userId);
        kbFileResultDbService.save(fileResult);

        WbSubmitLog submitLog = new WbSubmitLog();
        submitLog.setKnowledgeBaseId(kb.getId());
        submitLog.setRequestId(request.getRequestId());
        submitLog.setFileId(request.getFileId());
        submitLog.setSha256(validationResult.getSha256());
        submitLog.setFileName(metadata.getFileName());
        submitLog.setFileResultId(fileResult.getId());
        submitLog.setStatus(SubmitStatus.PASS.name());
        submitLog.setUserId(userId);
        try {
            submitLogDbService.save(submitLog);
        } catch (DuplicateKeyException e) {
            // 并发下同一 requestId 已被处理：回滚本事务，回放已有记录
            markRollbackOnly();
            WbSubmitLog raced = submitLogDbService.getByRequestId(request.getRequestId());
            if (NullUtil.isNull(raced)) {
                log.warn("===> KnowledgeBaseServiceImpl submit 幂等冲突重查为空, requestId={}（唯一键冲突但未查到已有记录）",
                        request.getRequestId());
                throw e;
            }
            log.info("===> KnowledgeBaseServiceImpl submit 幂等冲突回放, requestId={}", request.getRequestId());
            return inputVoAssembler.toSubmitResponse(raced, findParseTaskId(raced.getFileResultId()));
        }

        log.info("===> KnowledgeBaseServiceImpl submit 建档完成（待手动解析）, kbId={}, fileId={}, fileResultId={}, 建档耗时={}ms",
                kb.getId(), request.getFileId(), fileResult.getId(), System.currentTimeMillis() - buildStart);
        return inputVoAssembler.toSubmitResponse(submitLog, null);
    }

    /**
     * 提交记录分页查询：按知识库分页取 wb_submit_log 并转换为 VO（PASS/FAIL 均含，失败原因随行返回）。
     *
     * @param current        当前页，从 1 开始
     * @param size           每页条数
     * @param knowledgeBaseId 知识库 ID
     * @param status         提交结果筛选（PASS/FAIL，可选）
     * @param fileName       文件名模糊筛选（可选）
     * @return 提交记录分页 VO
     */
    @Override
    public IPage<SubmitLogVO> pageSubmitLogs(long current, long size, Long knowledgeBaseId, String status, String fileName) {
        return submitLogDbService.pageByKb(current, size, knowledgeBaseId, status, fileName)
                .convert(inputVoAssembler::toSubmitLogVO);
    }

    /**
     * 文件结果分页查询：分页取 wb_file_result，批量装配来源文件信息、存储口径
     * 与五环节（PARSE/STRUCTURE/PREPROCESS/CHUNK/EMBED）最新任务状态。
     *
     * <p>stage 参数经 {@link StageFunnelRules#resolveUpstreamStage(String)} 做合法性校验（非法值 40001）；
     * 按上游产物过滤在解析环节（产物表）落地后启用。
     *
     * @param current        当前页，从 1 开始
     * @param size           每页条数
     * @param knowledgeBaseId 知识库 ID
     * @param stage          环节（可选）
     * @return 文件结果分页 VO（含来源文件信息、所属数据源与当前启用的数据源及其显示名、各环节状态列表）
     */
    @Override
    public IPage<FileResultVO> pageFileResults(long current, long size, Long knowledgeBaseId, String stage) {
        StageFunnelRules.resolveUpstreamStage(stage);
        IPage<WbFileResult> page = kbFileResultDbService.pageByKb(current, size, knowledgeBaseId);
        List<Long> sourceIds = page.getRecords().stream()
                .map(WbFileResult::getSourceFileId)
                .distinct()
                .toList();
        Map<Long, WbSourceFile> sourceMap = sourceIds.isEmpty() ? Map.of()
                : sourceFileDbService.listByIds(sourceIds).stream()
                        .collect(Collectors.toMap(WbSourceFile::getId, Function.identity()));
        IPage<FileResultVO> voPage = page.convert(result -> inputVoAssembler.toFileResultVO(result, sourceMap.get(result.getSourceFileId())));

        // 存储口径：所属数据源按来源文件的 fileId 批量取档案，当前启用的数据源取运行时生效值
        List<String> chainFileIds = sourceMap.values().stream()
                .map(WbSourceFile::getFileId)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        Map<String, StorageRef> chainRefs = chainFileIds.isEmpty() ? Map.of()
                : fileStorage.refsOf(chainFileIds);
        Long currentSourceId = storageRouter.currentSourceId();
        StorageType currentType = storageRouter.writeType();
        String currentStorageType = NullUtil.isNull(currentType) ? null : currentType.getCode();
        String currentStorageSourceName = storageRouter.currentName();
        voPage.getRecords().forEach(vo -> {
            StorageRef ref = NullUtil.isNull(vo.getFileId()) ? null : chainRefs.get(vo.getFileId());
            Long archiveSourceId = NullUtil.isNull(ref) ? null : ref.sourceId();
            vo.setStorageType(NullUtil.isNull(ref) ? null : ref.storageType());
            vo.setStorageSourceId(archiveSourceId);
            vo.setStorageSourceName(storageSourceName(archiveSourceId));
            vo.setCurrentStorageSourceId(currentSourceId);
            vo.setCurrentStorageType(currentStorageType);
            vo.setCurrentStorageSourceName(currentStorageSourceName);
        });

        // 环节状态：列表行附各环节最新任务状态（PARSE→STRUCTURE→PREPROCESS→CHUNK→EMBED 五环节）
        List<Long> resultIds = voPage.getRecords().stream().map(FileResultVO::getId).toList();
        // ① 每环节一次 in 查询（id 倒序；按 fileResultId 去重取首条即最新任务）
        List<String> statusStages = List.of(PipelineStage.PARSE.name(), PipelineStage.STRUCTURE.name(),
                PipelineStage.PREPROCESS.name(), PipelineStage.CHUNK.name(), PipelineStage.EMBED.name());
        Map<String, Map<Long, WbPipelineTask>> stageTaskMaps = new HashMap<>();
        for (String statusStage : statusStages) {
            Map<Long, WbPipelineTask> byFileResult = resultIds.isEmpty() ? Map.of()
                    : pipelineTaskDbService.listByFileResultIdsAndStage(resultIds, statusStage).stream()
                            .collect(Collectors.toMap(WbPipelineTask::getFileResultId, Function.identity(),
                                    (first, second) -> first));
            stageTaskMaps.put(statusStage, byFileResult);
        }
        // ② 按环节顺序装配状态列表（无任务的环节不出现在列表中）
        voPage.getRecords().forEach(vo -> {
            List<StageStatusVO> statuses = new ArrayList<>();
            for (String statusStage : statusStages) {
                WbPipelineTask task = stageTaskMaps.get(statusStage).get(vo.getId());
                if (NullUtil.isNotNull(task)) {
                    statuses.add(taskVoAssembler.toStageStatusVO(task));
                }
            }
            if (!statuses.isEmpty()) {
                vo.setStageStatuses(statuses);
            }
        });
        return voPage;
    }

    /**
     * 数据源显示名：取注册表里的名称，取不到（ID 为空或数据源未注册）返回 null。
     *
     * @param sourceId 数据源 ID（可空）
     * @return 数据源显示名；ID 为空或未注册时为 null
     */
    private String storageSourceName(Long sourceId) {
        return NullUtil.isNull(sourceId) ? null : storageRouter.nameOf(sourceId);
    }

    /** 按 fileId 复用来源文件；并发撞 uk_file_id 时重查复用（同文件不重复建档）。 */
    private WbSourceFile findOrCreateSourceFile(FileSubmitRequest request, FileValidationResult validationResult,
                                                FileMetadata metadata, Long userId) {
        WbSourceFile sourceFile = sourceFileDbService.findByFileId(request.getFileId());
        if (NullUtil.isNotNull(sourceFile)) {
            log.info("===> KnowledgeBaseServiceImpl submit 复用来源文件, fileId={}, sourceFileId={}",
                    request.getFileId(), sourceFile.getId());
            return sourceFile;
        }
        sourceFile = new WbSourceFile();
        sourceFile.setFileId(request.getFileId());
        sourceFile.setSha256(validationResult.getSha256());
        sourceFile.setFileName(metadata.getFileName());
        sourceFile.setMimeType(validationResult.getMimeType());
        sourceFile.setFileSize(validationResult.getSize());
        sourceFile.setInputSnapshot(JsonUtil.toJsonStr(metadata));
        sourceFile.setUserId(userId);
        try {
            sourceFileDbService.save(sourceFile);
        } catch (DuplicateKeyException e) {
            WbSourceFile raced = sourceFileDbService.findByFileId(request.getFileId());
            if (NullUtil.isNull(raced)) {
                throw e;
            }
            log.info("===> KnowledgeBaseServiceImpl submit 并发复用来源文件, fileId={}, sourceFileId={}",
                    request.getFileId(), raced.getId());
            return raced;
        }
        return sourceFile;
    }

    /** 校验失败：只记 FAIL 日志一条，不建结果；文件名尽力取，取不到存空串（列 NOT NULL）。 */
    private FileSubmitResponse recordFailAndReturn(Long knowledgeBaseId, FileSubmitRequest request, String failReason) {
        String fileName = "";
        try {
            FileMetadata metadata = fileStorage.metadata(request.getFileId());
            fileName = StrUtil.blankToDefault(metadata.getFileName(), "");
        } catch (Exception e) {
            // 文件不存在等场景元数据不可得，fileName 留空
            log.debug("元数据不可得, 文件名留空, fileId={}", request.getFileId());
        }
        WbSubmitLog failLog = new WbSubmitLog();
        failLog.setKnowledgeBaseId(knowledgeBaseId);
        failLog.setRequestId(request.getRequestId());
        failLog.setFileId(request.getFileId());
        failLog.setFileName(fileName);
        // sha256 列 NOT NULL 无默认值；校验失败（尤其文件不存在）算不出指纹，存空串占位
        failLog.setSha256("");
        failLog.setStatus(SubmitStatus.FAIL.name());
        failLog.setFailReason(failReason);
        failLog.setUserId(currentUserId());
        submitLogDbService.save(failLog);
        log.info("===> KnowledgeBaseServiceImpl submit 校验失败, kbId={}, fileId={}, fileName={}, failReason={}",
                knowledgeBaseId, request.getFileId(), fileName, failReason);
        return inputVoAssembler.toSubmitResponse(failLog, null);
    }

    /** 绑定摘要（审计用；null/已删 → 无） */
    private String bindingSummary(WbStrategyBinding binding) {
        if (NullUtil.isNull(binding) || DelFlag.isDeleted(binding.getDelFlag())) {
            return "无";
        }
        WbPipelineStrategyVersion version = strategyVersionDbService.getById(binding.getStrategyVersionId());
        return NullUtil.isNull(version) ? "失效" : version.getName() + "-" + version.getVersion();
    }

    /** 详情：填充预处理/切片/向量化策略绑定摘要 */
    private void fillStrategyBindings(KnowledgeBaseVO vo) {
        fillBinding(vo, PreprocessStrategy.TYPE);
        fillBinding(vo, ChunkStrategy.TYPE);
        fillBinding(vo, EmbedStrategy.TYPE);
    }

    /** 分页：批量填充预处理/切片/向量化策略绑定摘要（避免逐行查询） */
    private void fillStrategyBindings(List<KnowledgeBaseVO> records) {
        if (records.isEmpty()) {
            return;
        }
        List<Long> kbIds = records.stream().map(KnowledgeBaseVO::getId).toList();
        fillBindingForType(records, kbIds, PreprocessStrategy.TYPE);
        fillBindingForType(records, kbIds, ChunkStrategy.TYPE);
        fillBindingForType(records, kbIds, EmbedStrategy.TYPE);
    }

    private void fillBindingForType(List<KnowledgeBaseVO> records, List<Long> kbIds, String type) {
        Map<Long, WbStrategyBinding> bindingByKb = strategyBindingDbService
                .listActiveByTypeAndKbIds(type, kbIds).stream()
                .collect(Collectors.toMap(WbStrategyBinding::getKnowledgeBaseId, Function.identity(), (a, b) -> a));
        if (bindingByKb.isEmpty()) {
            return;
        }
        Map<Long, WbPipelineStrategyVersion> versionById = versionsOf(bindingByKb.values());
        for (KnowledgeBaseVO vo : records) {
            WbStrategyBinding binding = bindingByKb.get(vo.getId());
            if (NullUtil.isNull(binding)) {
                continue;
            }
            WbPipelineStrategyVersion version = versionById.get(binding.getStrategyVersionId());
            if (NullUtil.isNull(version)) {
                continue;
            }
            applyBinding(vo, version);
        }
    }

    private void fillBinding(KnowledgeBaseVO vo, String type) {
        WbStrategyBinding binding = strategyBindingDbService.getByKbAndType(vo.getId(), type);
        WbPipelineStrategyVersion version = NullUtil.isNull(binding)
                ? null : strategyVersionDbService.getById(binding.getStrategyVersionId());
        if (NullUtil.isNull(version)) {
            return;
        }
        applyBinding(vo, version);
    }

    /** 批量取策略版本并按 id 建索引（绑定行只取一次版本，避免逐行查的 N+1） */
    private Map<Long, WbPipelineStrategyVersion> versionsOf(Collection<WbStrategyBinding> bindings) {
        List<Long> versionIds = bindings.stream()
                .map(WbStrategyBinding::getStrategyVersionId)
                .distinct()
                .toList();
        return strategyVersionDbService.listByIds(versionIds).stream()
                .collect(Collectors.toMap(WbPipelineStrategyVersion::getId, Function.identity(), (a, b) -> a));
    }

    /** 绑定摘要字段分发：按策略版本行类型落 VO 字段 */
    private void applyBinding(KnowledgeBaseVO vo, WbPipelineStrategyVersion version) {
        String fullVersion = version.getName() + "-" + version.getVersion();
        if (ChunkStrategy.TYPE.equals(version.getType())) {
            vo.setChunkStrategyVersionId(version.getId());
            vo.setChunkStrategyVersion(fullVersion);
        } else if (EmbedStrategy.TYPE.equals(version.getType())) {
            vo.setEmbedStrategyVersionId(version.getId());
            vo.setEmbedStrategyVersion(fullVersion);
        } else {
            vo.setPreprocessStrategyVersionId(version.getId());
            vo.setPreprocessStrategyVersion(fullVersion);
        }
    }

    /** 绑定开关是否关闭（null 视为开启，兼容存量数据） */
    private boolean isBindingDisabled(WbKnowledgeBase kb) {
        return NullUtil.isNotNull(kb) && StrategyBindingSwitch.isOff(kb.getStrategyBindingEnabled());
    }

    private KnowledgeBaseVO toVO(WbKnowledgeBase kb) {
        KnowledgeBaseVO vo = new KnowledgeBaseVO();
        vo.setId(kb.getId());
        vo.setName(kb.getName());
        vo.setDescription(kb.getDescription());
        vo.setStatus(kb.getStatus());
        vo.setStrategyBindingEnabled(resolveBindingEnabled(kb.getStrategyBindingEnabled()));
        vo.setPublishedIndexSetId(kb.getPublishedIndexSetId());
        vo.setUserId(kb.getUserId());
        vo.setCreateBy(kb.getCreateBy());
        vo.setCreateTime(kb.getCreateTime());
        vo.setUpdateBy(kb.getUpdateBy());
        vo.setUpdateTime(kb.getUpdateTime());
        return vo;
    }

    /** 开关归一化：null/非 0 一律视为开启（1） */
    private Integer resolveBindingEnabled(Integer value) {
        return StrategyBindingSwitch.isOn(value) ? StrategyBindingSwitch.ON.getCode() : StrategyBindingSwitch.OFF.getCode();
    }

    /**
     * 当前登录用户 ID（**必须有值**）。
     *
     * <p>知识库必须带归属：可见范围就是按 `user_id` 划分的（见
     * {@link KnowledgeBaseRules#visibleOwnerId}），落一条没有归属的库等于建了一条
     * 普通用户谁也看不见、只有管理员能碰的孤儿数据。这里无认证上下文直接拒绝，
     * 不静默落 NULL —— 存量 NULL 数据是早期占位，不该再新增。
     *
     * @return 当前用户 ID
     * @throws com.workbuddy.common.exception.KnowledgeException 未认证（UNAUTHORIZED 40101）
     */
    private Long requireCurrentUserId() {
        KnowledgeUser user = SecurityUtil.getUser();
        ThrowUtil.throwIf(NullUtil.isNull(user) || NullUtil.isNull(user.getId()), ErrorCode.UNAUTHORIZED);
        return user.getId();
    }

    /**
     * 当前登录用户 ID（无登录态返回 null）——用于提交链路的落库字段，不做认证拦截
     * （认证/归属拦截已在 {@link #submit} 的校验步骤完成）。
     *
     * <p>与 {@link #requireCurrentUserId()} 的区别：那个是"必须取到，取不到就拒绝"（创建知识库用），
     * 这个是"取不到就留空"（提交记录/来源文件的 user_id 允许为空）。
     */
    private Long currentUserId() {
        KnowledgeUser user = SecurityUtil.getUser();
        return NullUtil.isNull(user) ? null : user.getId();
    }

    /** 事务内捕获异常后返回：显式标记回滚，避免孤儿三写提交。 */
    private void markRollbackOnly() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    /** 查文件结果已有的 PARSE 任务 ID（幂等回放响应用；fileResultId 为空或尚无任务返回 null）。 */
    private Long findParseTaskId(Long fileResultId) {
        if (NullUtil.isNull(fileResultId)) {
            return null;
        }
        WbPipelineTask task = pipelineTaskDbService.getByFileResultIdAndStage(fileResultId, PipelineStage.PARSE.name());
        return NullUtil.isNull(task) ? null : task.getId();
    }

    /**
     * 按 ID 取「当前用户可访问的」知识库：存在性校验 + 归属校验一次完成。
     *
     * <p>本类**所有按 ID 的读写都必须走这里**。漏一处就是一个越权口子：
     * detail 漏了等于全部可读，update/disable/delete 漏了等于全部可改，
     * 而且漏了不会有任何报错，只会静默放行。
     *
     * @param id 知识库 ID
     * @return 知识库实体
     * @throws com.workbuddy.common.exception.KnowledgeException 不存在/已删除（40401）或非本人可访问（40401）
     */
    private WbKnowledgeBase getAccessibleById(Long id) {
        WbKnowledgeBase kb = knowledgeBaseDbService.getActiveById(id);
        KnowledgeBaseRules.checkAccessible(kb, SecurityUtil.getUser());
        return kb;
    }

    /**
     * 当前用户的知识库可见范围（查询条件用）。
     *
     * @return 普通用户返回自己的用户 ID；管理员返回 null（不过滤）
     */
    private Long visibleOwnerId() {
        return KnowledgeBaseRules.visibleOwnerId(SecurityUtil.getUser());
    }
}
