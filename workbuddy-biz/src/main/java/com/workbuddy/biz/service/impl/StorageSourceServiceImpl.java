package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.StorageSourceService;
import com.workbuddy.biz.service.db.KbAuditLogDbService;
import com.workbuddy.biz.service.db.KbPipelineTaskDbService;
import com.workbuddy.biz.service.db.KbStorageSourceDbService;
import com.workbuddy.biz.service.support.ChainStorageSupport;
import com.workbuddy.common.domain.entity.KbPipelineTask;
import com.workbuddy.common.domain.entity.KbStorageSource;
import com.workbuddy.common.dto.response.setting.StorageSourceVO;
import com.workbuddy.common.dto.response.setting.StorageSwitchPreviewVO;
import com.workbuddy.common.dto.response.setting.StorageSwitchResultVO;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.knowledge.AuditObjectType;
import com.workbuddy.common.enums.storage.StorageSourceStatus;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.config.FileCenterConfig;
import com.workbuddy.filecenter.config.FileCenterDefaults;
import com.workbuddy.filecenter.provider.StorageProviderFactory;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.provider.StorageSourceDef;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储数据源服务实现：数据源清单与当前启用切换。
 *
 * <p>启动初始化在本类：表为空按 config/workbuddy-file.yml 的 file-center 段 seed 两行，未停用的行注册进存储路由，
 * is_current 那一行标记为当前启用（探活与启动日志都在 StorageRouter）。
 *
 * <p>设为当前启用按「连接测试 → 先清后置 is_current → 回写探测结论 → 落审计 → 替换运行时当前启用」
 * 推进，连接测试不通过就抛出，库与运行时都不留半截状态。连接探测的结论落在数据源行的
 * last_probe_ok / last_probe_at 两列，列表接口只读这两列、不做实时探测。
 *
 * @author cxxl
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageSourceServiceImpl implements StorageSourceService {

    /** 审计对象类型 */
    private static final String AUDIT_OBJECT_TYPE = AuditObjectType.STORAGE_SOURCE.key();

    /** 影响面统计的任务状态：未落终态、切换后不可继续执行的任务 */
    private static final List<PipelineTaskStatus> IMPACT_STATUSES =
            List.of(PipelineTaskStatus.QUEUED, PipelineTaskStatus.RUNNING);

    private final KbStorageSourceDbService storageSourceDbService;
    private final KbPipelineTaskDbService pipelineTaskDbService;
    private final KbAuditLogDbService kbAuditLogDbService;
    private final StorageRouter storageRouter;
    private final ChainStorageSupport chainStorageSupport;
    private final FileCenterConfig fileCenterConfig;

    /** 启动初始化：表为空按配置 seed 两行，注册未停用的行，标记当前启用的那一行 */
    @PostConstruct
    public void initialize() {
        List<KbStorageSource> rows = storageSourceDbService.listAll();
        if (rows.isEmpty()) {
            rows = seedRows();
        }
        List<KbStorageSource> enabled = rows.stream().filter(this::isEnabled).toList();
        enabled.forEach(this::registerRow);
        KbStorageSource current = enabled.stream()
                .filter(this::isCurrent)
                .filter(row -> NullUtil.isNotNull(StorageType.of(row.getStorageType())))
                .findFirst()
                .orElse(null);
        if (NullUtil.isNull(current)) {
            registerFallback();
            return;
        }
        markCurrentWithProbe(current.getId());
        log.info("===> StorageSourceServiceImpl initialize 启动初始化当前启用数据源, id={}, name={}, type={}",
                current.getId(), current.getName(), current.getStorageType());
    }

    /**
     * 标记当前启用的数据源并回写这次探活的结论。
     *
     * <p>探活失败向上抛（启动即失败）：失败结论先落库再抛，页面能看到当前启用那一行的连接状态。
     *
     * @param sourceId 数据源 ID
     */
    private void markCurrentWithProbe(Long sourceId) {
        try {
            storageRouter.markCurrent(sourceId);
        } catch (RuntimeException e) {
            recordProbeQuietly(sourceId, false);
            throw e;
        }
        recordProbeQuietly(sourceId, true);
    }

    @Override
    public List<StorageSourceVO> list() {
        return storageSourceDbService.listAll().stream().map(this::toVO).toList();
    }

    @Override
    public StorageSwitchPreviewVO previewCurrent(Long id) {
        KbStorageSource target = requireSwitchable(id);
        return toPreview(target, countImpact(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StorageSwitchResultVO switchCurrent(Long id, String operator) {
        KbStorageSource target = requireSwitchable(id);
        Long beforeId = storageRouter.currentSourceId();
        if (id.equals(beforeId)) {
            return toResult(target, beforeId, new long[] {0, 0});
        }
        long[] impact = countImpact(id);
        StorageSourceDef def = defOf(target);
        storageRouter.register(def);
        probe(def);
        // 审计与库值先落：审计写失败时事务回滚库值，此时还没替换内存里的当前启用，切换等于整体没发生
        storageSourceDbService.clearCurrent();
        storageSourceDbService.markCurrent(id);
        // 探活已通过：结论随这次切换一起落库
        storageSourceDbService.recordProbe(id, true, LocalDateTime.now());
        audit(id, "当前启用 " + sourceName(beforeId),
                "当前启用 " + target.getName() + "；不可继续执行的任务：排队中 " + impact[0]
                        + " 个、执行中 " + impact[1] + " 个");
        storageRouter.markCurrent(id);
        log.info("===> StorageSourceServiceImpl switchCurrent 切换当前启用数据源, before={}, after={}, "
                        + "queued={}, running={}, operator={}",
                sourceName(beforeId), target.getName(), impact[0], impact[1], operator);
        return toResult(target, beforeId, impact);
    }

    // ---------------- 影响面与切换结果 ----------------

    /**
     * 影响面：属于其他数据源的排队中与执行中任务数。
     *
     * <p>任务按状态一次批量取回，涉及的文件结果去重后一次批量取链的数据源 ID。
     *
     * @param targetSourceId 目标数据源 ID
     * @return 长度为 2 的计数数组：0 为排队中，1 为执行中
     */
    private long[] countImpact(Long targetSourceId) {
        List<KbPipelineTask> tasks = pipelineTaskDbService.listByStatuses(IMPACT_STATUSES);
        Map<Long, Long> chainSourceIds = chainStorageSupport.chainSourceIdsOf(tasks.stream()
                .map(KbPipelineTask::getFileResultId)
                .filter(NullUtil::isNotNull)
                .distinct()
                .toList());
        long queued = 0;
        long running = 0;
        for (KbPipelineTask task : tasks) {
            Long chainSourceId = chainSourceIds.get(task.getFileResultId());
            if (NullUtil.isNull(chainSourceId) || chainSourceId.equals(targetSourceId)) {
                continue;
            }
            if (PipelineTaskStatus.QUEUED.name().equals(task.getStatus())) {
                queued++;
            } else if (PipelineTaskStatus.RUNNING.name().equals(task.getStatus())) {
                running++;
            }
        }
        return new long[] {queued, running};
    }

    private StorageSwitchPreviewVO toPreview(KbStorageSource target, long[] impact) {
        StorageSwitchPreviewVO vo = new StorageSwitchPreviewVO();
        fillTarget(vo, target);
        fillImpact(vo, impact);
        vo.setMessage("切换后属于 " + sourceName(storageRouter.currentSourceId()) + " 的 " + impact[0]
                + " 个排队中、" + impact[1] + " 个执行中任务不可继续执行");
        return vo;
    }

    private StorageSwitchResultVO toResult(KbStorageSource target, Long beforeId, long[] impact) {
        StorageSwitchResultVO vo = new StorageSwitchResultVO();
        fillTarget(vo, target);
        fillImpact(vo, impact);
        vo.setSwitchedSourceId(target.getId());
        // 切换响应按切换后的生效值回填当前值，影响面数字沿用切换前算出的那一份
        vo.setCurrentSourceId(target.getId());
        vo.setCurrentName(target.getName());
        vo.setCurrentType(target.getStorageType());
        vo.setMessage(target.getId().equals(beforeId)
                ? "当前启用的已经是 " + target.getName()
                : "已切换到 " + target.getName() + "；属于 " + sourceName(beforeId) + " 的 " + impact[0]
                        + " 个排队中、" + impact[1] + " 个执行中任务不可继续执行");
        return vo;
    }

    /** 目标数据源与切换前的当前数据源 */
    private void fillTarget(StorageSwitchPreviewVO vo, KbStorageSource target) {
        Long currentId = storageRouter.currentSourceId();
        vo.setTargetSourceId(target.getId());
        vo.setTargetName(target.getName());
        vo.setTargetType(target.getStorageType());
        vo.setCurrentSourceId(currentId);
        vo.setCurrentName(sourceName(currentId));
        vo.setCurrentType(typeCodeOf(currentId));
    }

    /** 影响面计数（长度为 2：0 为排队中，1 为执行中） */
    private void fillImpact(StorageSwitchPreviewVO vo, long[] impact) {
        vo.setQueuedCount(impact[0]);
        vo.setRunningCount(impact[1]);
    }

    // ---------------- 视图组装 ----------------

    private StorageSourceVO toVO(KbStorageSource row) {
        StorageType type = StorageType.of(row.getStorageType());
        Map<String, String> params = paramsOf(row);
        StorageSourceVO vo = new StorageSourceVO();
        vo.setId(row.getId());
        vo.setName(row.getName());
        vo.setStorageType(row.getStorageType());
        vo.setStorageTypeName(NullUtil.isNull(type) ? row.getStorageType() : ChainStorageSupport.storageName(type));
        vo.setStatus(row.getStatus());
        vo.setCurrent(isCurrent(row));
        vo.setRegistered(storageRouter.registered(row.getId()));
        vo.setConfigured(NullUtil.isNotNull(type) && StorageSourceDef.missingKeys(type, params).isEmpty());
        vo.setProbeOk(row.getLastProbeOk());
        vo.setProbeAt(probeAtText(row.getLastProbeAt()));
        vo.setParams(params);
        return vo;
    }

    /** 连接探测时间的下发口径：ISO-8601 文本，与实体时间字段的序列化结果一致；从未探测为空 */
    private String probeAtText(LocalDateTime probeAt) {
        return NullUtil.isNull(probeAt) ? null : probeAt.toString();
    }

    // ---------------- 数据源定义与行 ----------------

    /** 行 → 运行时定义（主键与名称都取自库里那一行） */
    private StorageSourceDef defOf(KbStorageSource row) {
        StorageType type = StorageType.of(row.getStorageType());
        ThrowUtil.throwIf(NullUtil.isNull(type), ErrorCode.STORAGE_BACKEND_UNCONFIGURED,
                "存储数据源类型无法识别: " + row.getStorageType());
        return new StorageSourceDef(row.getId(), row.getName(), type, paramsOf(row));
    }

    /** 行里的连接参数：config_json 解析后只留字符串值 */
    private Map<String, String> paramsOf(KbStorageSource row) {
        Map<String, String> params = new LinkedHashMap<>();
        JsonUtil.toMap(row.getConfigJson()).forEach((key, value) ->
                params.put(key, NullUtil.isNull(value) ? null : String.valueOf(value)));
        return StorageSourceDef.normalize(params);
    }

    /** 注册一条数据源行：类型码值无法识别时告警跳过，不拦住启动 */
    private void registerRow(KbStorageSource row) {
        StorageType type = StorageType.of(row.getStorageType());
        if (NullUtil.isNull(type)) {
            log.warn("===> StorageSourceServiceImpl registerRow 存储类型码值无法识别，跳过注册, id={}, storageType={}",
                    row.getId(), row.getStorageType());
            return;
        }
        storageRouter.register(new StorageSourceDef(row.getId(), row.getName(), type, paramsOf(row)));
    }

    /** 表为空时的首次初始化：按 config/workbuddy-file.yml 的 file-center 段落两行，配置指明的类型置为当前启用 */
    private List<KbStorageSource> seedRows() {
        StorageType seedType = fileCenterConfig.defaultType();
        List<StorageSourceDef> defs = FileCenterDefaults.seedDefs(fileCenterConfig);
        List<KbStorageSource> rows = new ArrayList<>(defs.size());
        for (StorageSourceDef def : defs) {
            KbStorageSource row = new KbStorageSource();
            row.setId(def.id());
            row.setName(def.name());
            row.setStorageType(def.type().getCode());
            row.setConfigJson(JsonUtil.toJsonStr(def.params()));
            row.setIsCurrent(def.type() == seedType
                    ? KbStorageSourceDbService.CURRENT_YES : KbStorageSourceDbService.CURRENT_NO);
            row.setStatus(StorageSourceStatus.ENABLED.getCode());
            storageSourceDbService.save(row);
            rows.add(row);
        }
        log.info("===> StorageSourceServiceImpl seedRows 数据源表为空，已按 file-center 配置初始化 {} 行, 当前启用 = {}",
                rows.size(), seedType.getCode());
        return rows;
    }

    /** 表里没有启用的数据源：按 file-center.storage-type 回落注册一条内置定义，保证应用可启动（内置定义不落库，探测结论无处可写） */
    private void registerFallback() {
        StorageType type = fileCenterConfig.defaultType();
        StorageSourceDef def = FileCenterDefaults.defOfType(fileCenterConfig, type);
        storageRouter.register(def);
        storageRouter.markCurrent(def.id());
        log.warn("===> StorageSourceServiceImpl registerFallback 表里没有启用的数据源，已按 file-center.storage-type "
                + "回落注册内置定义, id={}, type={}", def.id(), type.getCode());
    }

    // ---------------- 校验与留痕 ----------------

    /** 连接测试：临时构造提供者并初始化（建桶或建存储根目录） */
    private void probe(StorageSourceDef def) {
        try {
            StorageProviderFactory.create(def).initialize();
        } catch (Exception e) {
            throw new KnowledgeException(ErrorCode.STORAGE_SOURCE_PROBE_FAILED,
                    "存储数据源连接失败: " + def.name() + "（" + e.getMessage() + "）");
        }
    }

    /**
     * 回写探测结论：探测已经出结果，结论写不进去不改判定，只告警。
     *
     * @param sourceId 数据源 ID
     * @param probeOk  探测结论
     */
    private void recordProbeQuietly(Long sourceId, boolean probeOk) {
        try {
            storageSourceDbService.recordProbe(sourceId, probeOk, LocalDateTime.now());
        } catch (RuntimeException e) {
            log.warn("===> StorageSourceServiceImpl recordProbeQuietly 探测结论回写失败, id={}, probeOk={}",
                    sourceId, probeOk, e);
        }
    }

    /** 必填参数齐全校验：缺失的参数名逐个报出 */
    private void requireParamsComplete(StorageSourceDef def) {
        List<String> missing = StorageSourceDef.missingKeys(def);
        ThrowUtil.throwIf(!missing.isEmpty(), ErrorCode.PARAM_INVALID,
                "存储数据源参数不完整，缺少: " + String.join(",", missing));
    }

    /** 可切换的目标：行存在、已启用、类型与参数可用 */
    private KbStorageSource requireSwitchable(Long id) {
        KbStorageSource row = requireRow(id);
        ThrowUtil.throwIf(!isEnabled(row), ErrorCode.STORAGE_SOURCE_ILLEGAL,
                "「" + row.getName() + "」已停用，请先启用再切换");
        requireParamsComplete(defOf(row));
        return row;
    }

    /** 取数据源行，不存在按 40457 抛出 */
    private KbStorageSource requireRow(Long id) {
        KbStorageSource row = NullUtil.isNull(id) ? null : storageSourceDbService.getById(id);
        ThrowUtil.throwIf(NullUtil.isNull(row), ErrorCode.STORAGE_SOURCE_NOT_FOUND,
                "存储数据源不存在: id=" + id);
        return row;
    }

    /** 数据源显示名：未注册的 ID 与空 ID 由 ChainStorageSupport 给兜底文案 */
    private String sourceName(Long sourceId) {
        return chainStorageSupport.sourceName(sourceId);
    }

    /** 数据源类型码值：未注册或类型无法识别时为空 */
    private String typeCodeOf(Long sourceId) {
        StorageType type = storageRouter.typeOf(sourceId);
        return NullUtil.isNull(type) ? null : type.getCode();
    }

    private void audit(Long sourceId, String before, String after) {
        kbAuditLogDbService.saveAudit(AuditActionType.STORAGE_SWITCH, AUDIT_OBJECT_TYPE, sourceId, before, after);
    }

    private boolean isEnabled(KbStorageSource row) {
        return StorageSourceStatus.ENABLED == StorageSourceStatus.of(row.getStatus());
    }

    private boolean isCurrent(KbStorageSource row) {
        return NullUtil.isNotNull(row.getIsCurrent())
                && row.getIsCurrent() == KbStorageSourceDbService.CURRENT_YES;
    }
}
