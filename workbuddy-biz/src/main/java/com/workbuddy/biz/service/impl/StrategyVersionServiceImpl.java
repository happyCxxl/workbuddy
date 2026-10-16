package com.workbuddy.biz.service.impl;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.StrategyVersionService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbStrategyBindingDbService;
import com.workbuddy.biz.service.support.RetrievalRuleResolver;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.dto.request.strategy.StrategyVersionCreateDto;
import com.workbuddy.common.dto.request.strategy.StrategyVersionUpdateDto;
import com.workbuddy.common.dto.response.strategy.StrategyVersionVO;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.chunking.strategy.ChunkAlgorithmSpec;
import com.workbuddy.worker.embedding.strategy.EmbedAlgorithmSpec;
import com.workbuddy.worker.preprocessing.strategy.PreprocessAlgorithmSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 策略版本服务实现（管理 + 查询）。
 * 口径：策略版本行不可变——编辑 = 复制新行（校验新版本号不撞唯一键 → 插入新行，旧行原样保留）；
 * 停用代替删除（有绑定引用禁物理删除）；启停保留（运营开关，不改策略内容）。
 *
 * @author cxxl
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyVersionServiceImpl implements StrategyVersionService {

    private final WbPipelineStrategyVersionDbService strategyVersionDbService;

    private final WbStrategyBindingDbService strategyBindingDbService;

    private final RetrievalRuleResolver retrievalRuleResolver;

    @Override
    public List<StrategyVersionVO> list(String type, boolean includeInactive) {
        ThrowUtil.throwIf(StrUtil.isBlank(type) || !SUPPORTED_TYPES.contains(type),
                ErrorCode.PARAM_INVALID, "未知策略类型: " + type);
        List<WbPipelineStrategyVersion> rows = includeInactive
                ? strategyVersionDbService.listByType(type)
                : strategyVersionDbService.listEnabledByType(type);
        return rows.stream().map(this::toVO).toList();
    }

    @Override
    public StrategyVersionVO create(StrategyVersionCreateDto dto) {
        ThrowUtil.throwIf(!SUPPORTED_TYPES.contains(dto.getType()),
                ErrorCode.PARAM_INVALID, "未知策略类型: " + dto.getType());
        return register(dto.getType(), dto.getName(), dto.getVersion(), dto.getConfigSnapshot());
    }

    @Override
    public StrategyVersionVO update(Long id, StrategyVersionUpdateDto dto) {
        // 策略版本行不可变：编辑 = 复制新行（旧行原样保留；新版本号撞唯一键 → 40001）
        WbPipelineStrategyVersion old = requireById(id);
        StrategyVersionVO vo = register(old.getType(), dto.getName(), dto.getVersion(), dto.getConfigSnapshot());
        log.info("===> StrategyVersionServiceImpl 策略编辑=复制新版本, oldId={}, oldVersion={}, newName={}, newVersion={}",
                old.getId(), old.getVersion(), vo.getName(), vo.getVersion());
        return vo;
    }

    /** 注册新版本行（默认 ACTIVE；撞唯一键 40001） */
    private StrategyVersionVO register(String type, String name, String version, String configSnapshot) {
        String snapshot = resolveConfigSnapshot(type, configSnapshot);
        WbPipelineStrategyVersion row = new WbPipelineStrategyVersion();
        row.setType(type);
        row.setName(name.trim());
        row.setVersion(version.trim());
        row.setConfigSnapshot(snapshot);
        row.setStatus(RowStatus.ACTIVE.name());
        try {
            strategyVersionDbService.save(row);
        } catch (DuplicateKeyException e) {
            throw new KnowledgeException(ErrorCode.PARAM_INVALID, "同环节同名版本已存在");
        }
        return toVO(row);
    }

    @Override
    public StrategyVersionVO enable(Long id) {
        WbPipelineStrategyVersion row = requireById(id);
        row.setStatus(RowStatus.ACTIVE.name());
        strategyVersionDbService.updateById(row);
        return toVO(row);
    }

    @Override
    public StrategyVersionVO disable(Long id) {
        WbPipelineStrategyVersion row = requireById(id);
        row.setStatus(RowStatus.INACTIVE.name());
        strategyVersionDbService.updateById(row);
        return toVO(row);
    }

    @Override
    public boolean delete(Long id) {
        // 策略版本行不可变：有绑定引用禁物理删除（停用代替删除，引用不悬空）
        requireById(id);
        ThrowUtil.throwIf(strategyBindingDbService.existsByStrategyVersionId(id),
                ErrorCode.STRATEGY_BOUND_DELETE_FORBIDDEN);
        return strategyVersionDbService.removeById(id);
    }

    private WbPipelineStrategyVersion requireById(Long id) {
        WbPipelineStrategyVersion row = strategyVersionDbService.getById(id);
        ThrowUtil.throwIf(NullUtil.isNull(row), ErrorCode.STRATEGY_VERSION_NOT_FOUND);
        return row;
    }

    /**
     * 策略配置校验：按各环节算法 Spec 校验；非法 40001。
     */
    private String resolveConfigSnapshot(String type, String configSnapshot) {
        validateConfig(type, configSnapshot);
        return configSnapshot;
    }

    private void validateConfig(String type, String configSnapshot) {
        if ("RETRIEVAL".equals(type)) {
            // 检索规则：字段/取值白名单 + 参数边界 + 预留能力锁定（40001/40451）
            retrievalRuleResolver.validate(configSnapshot);
            return;
        }
        ThrowUtil.throwIf(StrUtil.isBlank(configSnapshot), ErrorCode.PARAM_INVALID, "配置不能为空");
        Map<String, Object> config;
        try {
            config = JsonUtil.toMap(configSnapshot);
        } catch (Exception e) {
            throw new KnowledgeException(ErrorCode.PARAM_INVALID, "配置不是合法 JSON");
        }
        String error = switch (type) {
            case "CHUNK" -> ChunkAlgorithmSpec.validate(config);
            case "PREPROCESS" -> PreprocessAlgorithmSpec.validate(config);
            case "EMBED" -> EmbedAlgorithmSpec.validate(config);
            default -> null;
        };
        ThrowUtil.throwIf(NullUtil.isNotNull(error), ErrorCode.PARAM_INVALID, error);
    }

    private StrategyVersionVO toVO(WbPipelineStrategyVersion row) {
        StrategyVersionVO vo = new StrategyVersionVO();
        vo.setId(row.getId());
        vo.setType(row.getType());
        vo.setName(row.getName());
        vo.setVersion(row.getVersion());
        vo.setConfigSnapshot(row.getConfigSnapshot());
        vo.setStatus(row.getStatus());
        vo.setCreateTime(row.getCreateTime());
        return vo;
    }
}
