package com.workbuddy.biz.service.impl;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.StrategyVersionService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbStrategyBindingDbService;
import com.workbuddy.biz.service.support.retrieval.RetrievalRuleResolver;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.dto.request.strategy.StrategyVersionCreateDTO;
import com.workbuddy.common.dto.request.strategy.StrategyVersionUpdateDTO;
import com.workbuddy.common.dto.response.strategy.StrategyVersionVO;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyVersionServiceImpl implements StrategyVersionService {

    private final WbPipelineStrategyVersionDbService strategyVersionDbService;

    private final WbStrategyBindingDbService strategyBindingDbService;

    private final RetrievalRuleResolver retrievalRuleResolver;

    /**
     * 列出某策略类型的版本。
     *
     * @param type            策略类型码值
     * @param includeInactive 是否包含停用版本
     * @return 策略版本列表
     */
    @Override
    public List<StrategyVersionVO> list(String type, boolean includeInactive) {
        ThrowUtil.throwIf(StrUtil.isBlank(type) || !SUPPORTED_TYPES.contains(type),
                ErrorCode.PARAM_INVALID, "未知策略类型: " + type);
        List<WbPipelineStrategyVersion> rows = includeInactive
                ? strategyVersionDbService.listByType(type)
                : strategyVersionDbService.listEnabledByType(type);
        return rows.stream().map(this::toVO).toList();
    }

    /**
     * 新建策略版本。
     *
     * @param dto 新建请求
     * @return 新建的策略版本
     */
    @Override
    public StrategyVersionVO create(StrategyVersionCreateDTO dto) {
        ThrowUtil.throwIf(!SUPPORTED_TYPES.contains(dto.getType()),
                ErrorCode.PARAM_INVALID, "未知策略类型: " + dto.getType());
        return register(dto.getType(), dto.getName(), dto.getVersion(), dto.getConfigSnapshot());
    }

    /**
     * 编辑策略版本：版本行不可变，编辑等于复制一行新版本，旧行原样保留。
     *
     * @param id  策略版本行 ID
     * @param dto 编辑请求
     * @return 新的策略版本
     */
    @Override
    public StrategyVersionVO update(Long id, StrategyVersionUpdateDTO dto) {
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
            throw new WorkbuddyException(ErrorCode.PARAM_INVALID, "同环节同名版本已存在");
        }
        return toVO(row);
    }

    /**
     * 启用策略版本。
     *
     * @param id 策略版本行 ID
     * @return 更新后的策略版本
     */
    @Override
    public StrategyVersionVO enable(Long id) {
        WbPipelineStrategyVersion row = requireById(id);
        row.setStatus(RowStatus.ACTIVE.name());
        strategyVersionDbService.updateById(row);
        return toVO(row);
    }

    /**
     * 停用策略版本。
     *
     * @param id 策略版本行 ID
     * @return 更新后的策略版本
     */
    @Override
    public StrategyVersionVO disable(Long id) {
        WbPipelineStrategyVersion row = requireById(id);
        row.setStatus(RowStatus.INACTIVE.name());
        strategyVersionDbService.updateById(row);
        return toVO(row);
    }

    /**
     * 删除策略版本：有绑定引用时拒绝（停用代替删除，引用不悬空）。
     *
     * @param id 策略版本行 ID
     * @return 删除成功为 true
     */
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
            throw new WorkbuddyException(ErrorCode.PARAM_INVALID, "配置不是合法 JSON");
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
