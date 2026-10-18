package com.workbuddy.biz.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.service.ModelService;
import com.workbuddy.biz.service.db.WbModelDbService;
import com.workbuddy.common.domain.entity.WbModel;
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
 * <p>校验全部在 DTO 注解（Controller `@Valid` 触发）；本类只做能力逐项字典解析，
 * 唯一键冲突由 uk_vendor_model 索引拦下转 40001，清单分页的过滤全在 SQL 侧完成。
 *
 * @author cxxl
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelServiceImpl implements ModelService {

    private final WbModelDbService wbModelDbService;
    private final ModelVendorProperties modelVendorProperties;
    private final AuditEventPublisher auditEventPublisher;

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelItemVO create(ModelSaveDTO dto) {
        WbModel row = new WbModel();
        applyColumns(row, dto);
        row.setCurrency(dto.getCurrency());
        row.setStatus(ModelStatus.ENABLED.name());
        wbModelDbService.save(row);
        auditEventPublisher.publish(AuditActionType.MODEL_CREATE, AuditObjectType.MODEL.key(), row.getId(), null,
                JsonUtil.toJsonStr(row));
        log.info("===> ModelServiceImpl create 新增模型, id={}, vendor={}, model={}",
                row.getId(), row.getVendorCode(), row.getModelKey());
        return toItem(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelItemVO update(Long id, ModelUpdateDTO dto) {
        WbModel before = requireRow(id);
        String beforeJson = JsonUtil.toJsonStr(before);
        WbModel after = new WbModel();
        after.setId(id);
        applyColumns(after, dto);
        after.setCurrency(dto.getCurrency());
        after.setStatus(before.getStatus());
        wbModelDbService.updateModel(after);
        auditEventPublisher.publish(AuditActionType.MODEL_UPDATE, AuditObjectType.MODEL.key(), id, beforeJson,
                JsonUtil.toJsonStr(after));
        log.info("===> ModelServiceImpl update 修改模型, id={}, vendor={}, model={}",
                id, after.getVendorCode(), after.getModelKey());
        return toItem(after);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelItemVO updateStatus(Long id, ModelStatusDTO dto) {
        WbModel row = requireRow(id);
        String beforeJson = JsonUtil.toJsonStr(row);
        String status = StrUtil.trimToNull(NullUtil.isNull(dto) ? null : dto.getStatus());
        ThrowUtil.throwIf(NullUtil.isNull(status), ErrorCode.PARAM_INVALID, "状态不能为空（取值 ENABLED / DISABLED）");
        String target = status.toUpperCase(Locale.ROOT);
        wbModelDbService.updateStatus(id, target);
        row.setStatus(target);
        auditEventPublisher.publish(isEnabled(target) ? AuditActionType.MODEL_ENABLE : AuditActionType.MODEL_DISABLE,
                AuditObjectType.MODEL.key(), id, beforeJson, JsonUtil.toJsonStr(row));
        log.info("===> ModelServiceImpl updateStatus 模型启停, id={}, status={}", id, target);
        return toItem(row);
    }

    /**
     * 业务列搬运：文本 trim、能力清单解析成落库 CSV、思考标记缺省 0、数值列原样复制；
     * 币种与状态由新增 / 编辑各自定值，不在本方法。
     */
    private void applyColumns(WbModel row, ModelSaveDTO dto) {
        row.setVendorCode(StrUtil.trimToNull(dto.getVendorCode()));
        row.setModelKey(StrUtil.trimToNull(dto.getModelKey()));
        row.setCapabilities(capabilities(dto.getCapabilities()));
        row.setThinkingEnabled(
                NullUtil.isNull(dto.getThinkingEnabled()) ? Integer.valueOf(0) : dto.getThinkingEnabled());
        row.setInputLimit(dto.getInputLimit());
        row.setOutputLimit(dto.getOutputLimit());
        row.setThinkingInputLimit(dto.getThinkingInputLimit());
        row.setThinkingOutputLimit(dto.getThinkingOutputLimit());
        row.setContextWindow(dto.getContextWindow());
        row.setPriceInPerM(dto.getPriceInPerM());
        row.setPriceInPerMCacheHit(dto.getPriceInPerMCacheHit());
        row.setPriceOutPerM(dto.getPriceOutPerM());
        row.setRpmLimit(dto.getRpmLimit());
        row.setTpmLimit(dto.getTpmLimit());
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
