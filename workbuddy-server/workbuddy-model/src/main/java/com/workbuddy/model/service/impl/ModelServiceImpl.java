package com.workbuddy.model.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.common.domain.entity.WbModel;
import com.workbuddy.common.convert.model.ModelConverter;
import com.workbuddy.common.dto.request.model.ModelQueryDTO;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.common.enums.model.ModelStatus;
import com.workbuddy.common.dto.request.model.ModelSaveDTO;
import com.workbuddy.common.dto.request.model.ModelStatusDTO;
import com.workbuddy.common.dto.request.model.ModelUpdateDTO;
import com.workbuddy.common.dto.response.model.ModelItemVO;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.knowledge.AuditObjectType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.security.audit.AuditEventPublisher;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.config.ModelVendorProperties;
import com.workbuddy.model.db.WbModelDbService;
import com.workbuddy.model.service.ModelService;
import com.workbuddy.model.service.support.catalog.ModelRegistryRefresher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 模型管理服务实现：模型目录的清单分页与写入、启停。
 *
 * <p>格式校验在 DTO 注解（Controller `@Valid` 触发）；厂商是否在配置里、唯一键是否冲突、
 * 能力是否在字典里由本类判定，清单分页的过滤全在 SQL 侧完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelServiceImpl implements ModelService {

    private final WbModelDbService wbModelDbService;

    private final ModelVendorProperties modelVendorProperties;
    private final AuditEventPublisher auditEventPublisher;
    private final ModelRegistryRefresher modelRegistryRefresher;
    private final ModelConverter modelConverter;

    /**
     * 分页查模型目录。
     *
     * @param query 查询条件
     * @return 模型分页
     */
    @Override
    public IPage<ModelItemVO> page(ModelQueryDTO query) {
        ModelQueryDTO source = NullUtil.isNull(query) ? new ModelQueryDTO() : query;
        IPage<WbModel> rows = wbModelDbService.pageByCondition(source.currentOrDefault(),
                source.sizeOrDefault(), source.getStatus(), StrUtil.trimToNull(source.getKeyword()),
                source.getCapability());
        Page<ModelItemVO> target = new Page<>(rows.getCurrent(), rows.getSize(), rows.getTotal());
        target.setRecords(rows.getRecords().stream().map(this::toItem).toList());
        return target;
    }

    /**
     * 新增模型。
     *
     * @param dto 新增请求
     * @return 新建的模型
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelItemVO create(ModelSaveDTO dto) {
        WbModel row = modelConverter.toModel(dto);
        applyBusinessColumns(row, dto);
        row.setStatus(ModelStatus.ENABLED.name());
        ensureUnique(row.getVendorCode(), row.getModelKey(), null);
        wbModelDbService.save(row);
        auditEventPublisher.publish(AuditActionType.MODEL_CREATE, AuditObjectType.MODEL.key(), row.getId(),
                JsonUtil.toJsonStr(row));
        log.info("===> ModelServiceImpl create 新增模型, id={}, vendor={}, model={}",
                row.getId(), row.getVendorCode(), row.getModelKey());
        modelRegistryRefresher.refreshAfterCommit();
        return toItem(row);
    }

    /**
     * 编辑模型。
     *
     * @param id  模型 ID
     * @param dto 编辑请求
     * @return 更新后的模型
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelItemVO update(Long id, ModelUpdateDTO dto) {
        WbModel before = requireRow(id);
        WbModel after = modelConverter.toModel(dto);
        after.setId(id);
        applyBusinessColumns(after, dto);
        after.setStatus(before.getStatus());
        ensureUnique(after.getVendorCode(), after.getModelKey(), id);
        wbModelDbService.updateModel(after);
        auditEventPublisher.publish(AuditActionType.MODEL_UPDATE, AuditObjectType.MODEL.key(), id,
                JsonUtil.toJsonStr(Map.of("before", before, "after", after)));
        log.info("===> ModelServiceImpl update 修改模型, id={}, vendor={}, model={}",
                id, after.getVendorCode(), after.getModelKey());
        modelRegistryRefresher.refreshAfterCommit();
        return toItem(after);
    }

    /**
     * 更新模型状态。
     *
     * @param id  模型 ID
     * @param dto 状态请求
     * @return 更新后的模型
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelItemVO updateStatus(Long id, ModelStatusDTO dto) {
        WbModel row = requireRow(id);
        String status = StrUtil.trimToNull(NullUtil.isNull(dto) ? null : dto.getStatus());
        ThrowUtil.throwIf(NullUtil.isNull(status), ErrorCode.PARAM_INVALID, "状态不能为空（取值 ENABLED / DISABLED）");
        String target = status.toUpperCase(Locale.ROOT);
        wbModelDbService.updateStatus(id, target);
        row.setStatus(target);
        auditEventPublisher.publish(isEnabled(target) ? AuditActionType.MODEL_ENABLE : AuditActionType.MODEL_DISABLE,
                AuditObjectType.MODEL.key(), id, JsonUtil.toJsonStr(Map.of("status", target)));
        log.info("===> ModelServiceImpl updateStatus 模型启停, id={}, status={}", id, target);
        modelRegistryRefresher.refreshAfterCommit();
        return toItem(row);
    }

    /**
     * 业务列加工：文本 trim、能力清单解析成落库 CSV、思考标记缺省 0；
     * 纯字段搬运与币种由 {@link ModelConverter#toModel} 完成，状态由新增 / 编辑各自定值。
     */
    private void applyBusinessColumns(WbModel row, ModelSaveDTO dto) {
        row.setVendorCode(normalizeVendor(dto.getVendorCode()));
        row.setModelKey(StrUtil.trimToNull(dto.getModelKey()));
        row.setCapabilities(capabilities(dto.getCapabilities()));
        row.setThinkingEnabled(
                NullUtil.isNull(dto.getThinkingEnabled()) ? Integer.valueOf(0) : dto.getThinkingEnabled());
    }

    /**
     * 能力逐项按字典解析并拼成落库 CSV；未知取值拒绝
     */
    private String capabilities(List<String> values) {
        Set<ModelCapability> capabilities = new LinkedHashSet<>();
        for (String value : values) {
            ModelCapability capability = ModelCapability.of(value);
            ThrowUtil.throwIf(NullUtil.isNull(capability), ErrorCode.PARAM_INVALID, "能力取值不在字典内：" + value);
            capabilities.add(capability);
        }
        return ModelCapability.join(capabilities);
    }

    /**
     * 厂商码归一化：必须在配置里（大小写不敏感），落库统一小写
     */
    private String normalizeVendor(String vendorCode) {
        String trimmed = StrUtil.trimToNull(vendorCode);
        String normalized = NullUtil.isNull(trimmed) ? null : trimmed.toLowerCase(Locale.ROOT);
        ThrowUtil.throwIf(NullUtil.isNull(normalized) || !vendorMap().containsKey(normalized),
                ErrorCode.MODEL_VENDOR_UNKNOWN, "厂商未配置：" + vendorCode);
        return normalized;
    }

    /**
     * 唯一键预检：同厂商同模型名（编辑时排除自身），DB 唯一键兜底
     */
    private void ensureUnique(String vendorCode, String modelKey, Long excludeId) {
        boolean exists = wbModelDbService.existsVendorAndModelKey(vendorCode, modelKey, excludeId);
        ThrowUtil.throwIf(exists, ErrorCode.PARAM_INVALID,
                "同厂商下模型已存在：" + vendorCode + "/" + modelKey);
    }

    /**
     * 取一行现有记录；查不到即按模型不存在拒绝
     */
    private WbModel requireRow(Long id) {
        WbModel row = wbModelDbService.getExistingById(id);
        ThrowUtil.throwIf(NullUtil.isNull(row), ErrorCode.MODEL_NOT_FOUND, "模型不存在：id=" + id);
        return row;
    }

    /**
     * 库表行 → 清单条目：能力串切成字典名清单，厂商展示名取配置 display-name
     */
    private ModelItemVO toItem(WbModel row) {
        ModelItemVO item = new ModelItemVO();
        item.setId(row.getId());
        item.setVendorCode(row.getVendorCode());
        item.setVendorName(vendorName(row.getVendorCode()));
        item.setModelKey(row.getModelKey());
        for (ModelCapability capability : ModelCapability.parse(row.getCapabilities())) {
            item.getCapabilities().add(capability.name());
        }
        item.setThinkingEnabled(row.getThinkingEnabled());
        item.setInputLimit(row.getInputLimit());
        item.setOutputLimit(row.getOutputLimit());
        item.setThinkingInputLimit(row.getThinkingInputLimit());
        item.setThinkingOutputLimit(row.getThinkingOutputLimit());
        item.setContextWindow(row.getContextWindow());
        item.setPriceInPerM(row.getPriceInPerM());
        item.setPriceInPerMCacheHit(row.getPriceInPerMCacheHit());
        item.setPriceOutPerM(row.getPriceOutPerM());
        item.setCurrency(row.getCurrency());
        item.setRpmLimit(row.getRpmLimit());
        item.setTpmLimit(row.getTpmLimit());
        item.setStatus(row.getStatus());
        return item;
    }

    /**
     * 厂商配置（整段缺省时给空 Map，遍历与判定都不必再判空）
     */
    private Map<String, ModelVendorProperties.Vendor> vendorMap() {
        Map<String, ModelVendorProperties.Vendor> vendors = modelVendorProperties.getVendors();
        return NullUtil.isNull(vendors) ? Map.of() : vendors;
    }

    /**
     * 厂商展示名：配置 display-name 命中取展示名，未配置回落厂商码
     */
    private String vendorName(String vendorCode) {
        if (NullUtil.isNull(vendorCode)) {
            return null;
        }
        ModelVendorProperties.Vendor vendor = vendorMap().get(vendorCode);
        if (NullUtil.isNull(vendor) || StrUtil.isBlank(vendor.getDisplayName())) {
            return vendorCode;
        }
        return vendor.getDisplayName();
    }

    /**
     * 状态是否启用
     */
    private boolean isEnabled(String status) {
        return ModelStatus.ENABLED.name().equals(status);
    }
}
