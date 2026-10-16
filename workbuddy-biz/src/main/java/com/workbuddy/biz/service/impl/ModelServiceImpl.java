package com.workbuddy.biz.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.service.ModelService;
import com.workbuddy.biz.service.db.WbModelDbService;
import com.workbuddy.common.domain.entity.WbModel;
import com.workbuddy.common.dto.request.model.ModelQueryDto;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.common.dto.request.model.ModelSaveDto;
import com.workbuddy.common.dto.request.model.ModelStatusDto;
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
 * 模型管理服务实现：模型目录的清单与写入、启停。
 *
 * <p>格式与取值校验在 DTO 注解（Controller `@Valid` 触发）；本类只做要查库查配置的三件事：
 * 厂商码必须出现在配置里、同厂商同名模型唯一、能力逐项按字典解析。清单分页的过滤全在 SQL 侧完成。
 *
 * @author cxxl
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelServiceImpl implements ModelService {

    /** 新增行的初始状态（编辑不改状态，启停走专用接口） */
    private static final String STATUS_ENABLED = "ENABLED";

    /** 新增行币种留空时的取值 */
    private static final String CURRENCY_DEFAULT = "CNY";

    /** 审计对象类型（wb_audit_log.object_type） */
    private static final String AUDIT_OBJECT_TYPE = AuditObjectType.MODEL.key();

    private final WbModelDbService wbModelDbService;

    private final ModelVendorProperties modelVendorProperties;

    private final AuditEventPublisher auditEventPublisher;

    @Override
    public IPage<ModelItemVO> page(ModelQueryDto query) {
        ModelQueryDto source = NullUtil.isNull(query) ? new ModelQueryDto() : query;
        IPage<WbModel> rows = wbModelDbService.pageByCondition(source.currentOrDefault(),
                source.sizeOrDefault(), source.getStatus(), StrUtil.trimToNull(source.getKeyword()),
                source.getCapability());
        Page<ModelItemVO> target = new Page<>(rows.getCurrent(), rows.getSize(), rows.getTotal());
        target.setRecords(rows.getRecords().stream().map(this::toItem).toList());
        return target;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelItemVO create(ModelSaveDto dto) {
        WbModel row = new WbModel();
        fill(row, dto, null, CURRENCY_DEFAULT, STATUS_ENABLED);
        wbModelDbService.save(row);
        auditEventPublisher.publish(AuditActionType.MODEL_CREATE, AUDIT_OBJECT_TYPE, row.getId(), null,
                JsonUtil.toJsonStr(row));
        log.info("===> ModelServiceImpl create 新增模型, id={}, vendor={}, model={}",
                row.getId(), row.getVendorCode(), row.getModelKey());
        return toItem(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelItemVO update(Long id, ModelSaveDto dto) {
        WbModel before = requireRow(id);
        String beforeJson = JsonUtil.toJsonStr(before);
        WbModel after = new WbModel();
        after.setId(id);
        fill(after, dto, id, before.getCurrency(), before.getStatus());
        wbModelDbService.updateAll(after);
        auditEventPublisher.publish(AuditActionType.MODEL_UPDATE, AUDIT_OBJECT_TYPE, id, beforeJson,
                JsonUtil.toJsonStr(after));
        log.info("===> ModelServiceImpl update 修改模型, id={}, vendor={}, model={}",
                id, after.getVendorCode(), after.getModelKey());
        return toItem(after);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelItemVO updateStatus(Long id, ModelStatusDto dto) {
        WbModel row = requireRow(id);
        String beforeJson = JsonUtil.toJsonStr(row);
        String status = StrUtil.trimToNull(NullUtil.isNull(dto) ? null : dto.getStatus());
        ThrowUtil.throwIf(NullUtil.isNull(status), ErrorCode.PARAM_INVALID, "状态不能为空（取值 ENABLED / DISABLED）");
        String target = status.toUpperCase(Locale.ROOT);
        wbModelDbService.updateStatus(id, target);
        row.setStatus(target);
        auditEventPublisher.publish(isEnabled(target) ? AuditActionType.MODEL_ENABLE : AuditActionType.MODEL_DISABLE,
                AUDIT_OBJECT_TYPE, id, beforeJson, JsonUtil.toJsonStr(row));
        log.info("===> ModelServiceImpl updateStatus 模型启停, id={}, status={}", id, target);
        return toItem(row);
    }

    /**
     * 收参校验后把业务列写进行对象：新增与编辑共用，编辑时排除自身做唯一键判定；
     * 币种与状态留空时各取形参给出的回落值（币种回落新增取 CNY、编辑沿用原值；状态回落新增取启用、编辑沿用原值）。
     *
     * @param row              目标行（新增为新建对象，编辑为只带主键的新对象）
     * @param dto              模型收参
     * @param excludeId        唯一键判定要排除的主键，可空
     * @param currencyFallback 币种留空时的取值
     * @param statusFallback   状态留空时的取值
     */
    private void fill(WbModel row, ModelSaveDto dto, Long excludeId, String currencyFallback, String statusFallback) {
        ThrowUtil.throwIf(NullUtil.isNull(dto), ErrorCode.PARAM_INVALID, "请求体不能为空");
        String vendorCode = requiredVendorCode(dto.getVendorCode());
        String modelKey = StrUtil.trimToNull(dto.getModelKey());
        ThrowUtil.throwIf(wbModelDbService.existsVendorAndModelKey(vendorCode, modelKey, excludeId),
                ErrorCode.PARAM_INVALID, "该厂商下已存在同名模型（唯一键 vendor_code + model_key）："
                        + vendorCode + " / " + modelKey);
        row.setVendorCode(vendorCode);
        row.setModelKey(modelKey);
        row.setCapabilities(capabilities(dto.getCapabilities()));
        row.setThinkingEnabled(NullUtil.isNull(dto.getThinkingEnabled()) ? Integer.valueOf(0) : dto.getThinkingEnabled());
        row.setInputLimit(dto.getInputLimit());
        row.setOutputLimit(dto.getOutputLimit());
        row.setThinkingInputLimit(dto.getThinkingInputLimit());
        row.setThinkingOutputLimit(dto.getThinkingOutputLimit());
        row.setContextWindow(dto.getContextWindow());
        row.setPriceInPerM(dto.getPriceInPerM());
        row.setPriceInPerMCacheHit(dto.getPriceInPerMCacheHit());
        row.setPriceOutPerM(dto.getPriceOutPerM());
        row.setCurrency(currency(dto.getCurrency(), currencyFallback));
        row.setRpmLimit(dto.getRpmLimit());
        row.setTpmLimit(dto.getTpmLimit());
        row.setStatus(statusFallback);
    }

    /** 厂商码：必须出现在 workbuddy.model.vendors 配置里 */
    private String requiredVendorCode(String value) {
        String vendorCode = StrUtil.trimToNull(value);
        Map<String, ModelVendorProperties.Vendor> vendors = vendorMap();
        ThrowUtil.throwIf(!vendors.containsKey(vendorCode), ErrorCode.MODEL_VENDOR_UNKNOWN,
                "厂商码不在配置里：" + vendorCode + "（配置键 workbuddy.model.vendors，已配置厂商："
                        + vendors.keySet() + "）");
        return vendorCode;
    }

    /** 能力：至少一项，逐项按字典解析，未知取值拒绝；落库形态是逗号分隔的多值串 */
    private String capabilities(List<String> values) {
        ThrowUtil.throwIf(NullUtil.isNull(values) || values.isEmpty(), ErrorCode.PARAM_INVALID, "能力至少选一项");
        Set<ModelCapability> capabilities = new LinkedHashSet<>();
        for (String value : values) {
            ModelCapability capability = ModelCapability.of(value);
            ThrowUtil.throwIf(NullUtil.isNull(capability), ErrorCode.PARAM_INVALID, "能力取值不在字典内：" + value);
            capabilities.add(capability);
        }
        return ModelCapability.join(capabilities);
    }

    /** 币种归一：留空取回落值（再空取 CNY），给了值统一大写 */
    private String currency(String value, String fallback) {
        String currency = StrUtil.trimToNull(value);
        if (NullUtil.isNull(currency)) {
            return NullUtil.isNull(fallback) ? CURRENCY_DEFAULT : fallback;
        }
        return currency.toUpperCase(Locale.ROOT);
    }

    /** 取一行现有记录；查不到即按模型不存在拒绝 */
    private WbModel requireRow(Long id) {
        WbModel row = wbModelDbService.getExistingById(id);
        ThrowUtil.throwIf(NullUtil.isNull(row), ErrorCode.MODEL_NOT_FOUND, "模型不存在：id=" + id);
        return row;
    }

    /** 库表行 → 清单条目：能力串切成字典名清单，厂商展示名取配置 display-name */
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

    /** 厂商配置（整段缺省时给空 Map，遍历与判定都不必再判空） */
    private Map<String, ModelVendorProperties.Vendor> vendorMap() {
        Map<String, ModelVendorProperties.Vendor> vendors = modelVendorProperties.getVendors();
        return NullUtil.isNull(vendors) ? Map.of() : vendors;
    }

    /** 厂商展示名：配置 display-name 命中取展示名，未配置回落厂商码 */
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

    /** 状态是否启用 */
    private boolean isEnabled(String status) {
        return STATUS_ENABLED.equals(status);
    }
}
