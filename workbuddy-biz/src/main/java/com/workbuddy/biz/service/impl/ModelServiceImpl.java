package com.workbuddy.biz.service.impl;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.ModelService;
import com.workbuddy.biz.service.db.WbModelDbService;
import com.workbuddy.common.domain.entity.WbModel;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.common.dto.request.model.ModelQueryDto;
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

import java.math.BigDecimal;
import java.util.*;

/**
 * 模型管理服务实现：模型目录的清单与写入、启停。
 *
 * <p>校验都在本类一处完成：厂商码必须出现在配置里、唯一键不冲突、长度 / 价格与限额非负、能力与状态取值在字典内。
 * 写入只落 `wb_model` 的业务列与审计，配置与密钥都不经过本类。
 *
 * @author cxxl
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelServiceImpl implements ModelService {

    /** 状态字典：启用（§4.6） */
    private static final String STATUS_ENABLED = "ENABLED";

    /** 状态字典：停用（§4.6） */
    private static final String STATUS_DISABLED = "DISABLED";

    /** 状态字典取值集 */
    private static final Set<String> STATUS_DICT = Set.of(STATUS_ENABLED, STATUS_DISABLED);

    /** 币种字典取值集（wb_model.currency） */
    private static final Set<String> CURRENCY_DICT = Set.of("CNY", "USD");

    /** 币种留空时的取值 */
    private static final String CURRENCY_DEFAULT = "CNY";

    /** 文本列宽上限（与 wb_model 的列定义一致） */
    private static final int VENDOR_CODE_MAX = 64;
    private static final int MODEL_KEY_MAX = 128;
    private static final int CAPABILITIES_MAX = 255;

    /** 思考模式标记取值：1 支持 / 0 不支持 */
    private static final int THINKING_ENABLED = 1;
    private static final int THINKING_DISABLED = 0;

    /** 日期写法的文本长度（yyyy-MM-dd） */
    private static final int DATE_TEXT_LENGTH = 10;

    /** 厂商展示名：配置里没有这一项，按厂商码给内置映射，认不出的回落码值本身 */
    private static final Map<String, String> VENDOR_DISPLAY_NAMES = Map.of(
            "dashscope", "阿里百炼",
            "deepseek", "DeepSeek",
            "openai", "OpenAI");

    /** 审计对象类型（wb_audit_log.object_type） */
    private static final String AUDIT_OBJECT_TYPE = AuditObjectType.MODEL.key();

    private final WbModelDbService kbModelDbService;

    private final ModelVendorProperties modelVendorProperties;

    private final AuditEventPublisher auditEventPublisher;

    @Override
    public List<ModelItemVO> list(ModelQueryDto query) {
        ModelQueryDto source = NullUtil.isNull(query) ? new ModelQueryDto() : query;
        ModelCapability capability = capabilityOf(source.getCapability());
        String status = statusOf(source.getStatus(), null);
        List<WbModel> rows = kbModelDbService.listByCondition(status, StrUtil.trimToNull(source.getKeyword()));
        List<ModelItemVO> items = new ArrayList<>();
        for (WbModel row : rows) {
            ModelItemVO item = toItem(row);
            if (NullUtil.isNull(capability) || item.getCapabilities().contains(capability.name())) {
                items.add(item);
            }
        }
        return items;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelItemVO create(ModelSaveDto dto) {
        WbModel row = new WbModel();
        fill(row, dto, null, CURRENCY_DEFAULT);
        kbModelDbService.save(row);
        auditEventPublisher.publish(AuditActionType.MODEL_CREATE, AUDIT_OBJECT_TYPE, row.getId(), null,
                JsonUtil.toJsonStr(row));
        log.info("===> ModelAdminServiceImpl create 新增模型, id={}, vendor={}, model={}",
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
        fill(after, dto, id, before.getCurrency());
        kbModelDbService.updateAll(after);
        auditEventPublisher.publish(AuditActionType.MODEL_UPDATE, AUDIT_OBJECT_TYPE, id, beforeJson,
                JsonUtil.toJsonStr(after));
        log.info("===> ModelAdminServiceImpl update 修改模型, id={}, vendor={}, model={}",
                id, after.getVendorCode(), after.getModelKey());
        return toItem(after);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelItemVO updateStatus(Long id, ModelStatusDto dto) {
        WbModel row = requireRow(id);
        String beforeJson = JsonUtil.toJsonStr(row);
        String status = requiredStatus(NullUtil.isNull(dto) ? null : dto.getStatus());
        kbModelDbService.updateStatus(id, status);
        row.setStatus(status);
        auditEventPublisher.publish(isEnabled(status) ? AuditActionType.MODEL_ENABLE : AuditActionType.MODEL_DISABLE,
                AUDIT_OBJECT_TYPE, id, beforeJson, JsonUtil.toJsonStr(row));
        log.info("===> ModelAdminServiceImpl updateStatus 模型启停, id={}, status={}", id, status);
        return toItem(row);
    }

    /**
     * 收参校验后把业务列写进行对象：新增与编辑共用，编辑时排除自身做唯一键判定，
     * 币种留空时取形参给出的回落值；**状态不在收参里**，统一写启用（停用走启停接口）。
     *
     * @param row              目标行（新增为新建对象，编辑为只带主键的新对象）
     * @param dto              模型收参
     * @param excludeId        唯一键判定要排除的主键，可空
     * @param currencyFallback 币种留空时的取值
     */
    private void fill(WbModel row, ModelSaveDto dto, Long excludeId, String currencyFallback) {
        ThrowUtil.throwIf(NullUtil.isNull(dto), ErrorCode.PARAM_INVALID, "请求体不能为空");
        String vendorCode = requiredVendorCode(dto.getVendorCode());
        String modelKey = text(dto.getModelKey(), "模型名", MODEL_KEY_MAX, true);
        ThrowUtil.throwIf(kbModelDbService.existsVendorAndModelKey(vendorCode, modelKey, excludeId),
                ErrorCode.PARAM_INVALID, "该厂商下已存在同名模型（唯一键 vendor_code + model_key）："
                        + vendorCode + " / " + modelKey);
        row.setVendorCode(vendorCode);
        row.setModelKey(modelKey);
        row.setCapabilities(capabilities(dto.getCapabilities()));
        row.setThinkingEnabled(flagOf(dto.getThinkingEnabled()));
        row.setInputLimit(nonNegative(dto.getInputLimit(), "最大输入长度"));
        row.setOutputLimit(nonNegative(dto.getOutputLimit(), "最大输出长度"));
        row.setThinkingInputLimit(nonNegative(dto.getThinkingInputLimit(), "思考模式最大输入长度"));
        row.setThinkingOutputLimit(nonNegative(dto.getThinkingOutputLimit(), "思考模式最大输出长度"));
        row.setContextWindow(nonNegative(dto.getContextWindow(), "上下文长度"));
        row.setPriceInPerM(nonNegative(dto.getPriceInPerM(), "输入单价（缓存未命中）"));
        row.setPriceInPerMCacheHit(nonNegative(dto.getPriceInPerMCacheHit(), "输入单价（缓存命中）"));
        row.setPriceOutPerM(nonNegative(dto.getPriceOutPerM(), "输出单价"));
        row.setCurrency(currency(dto.getCurrency(), currencyFallback));
        row.setRpmLimit(nonNegative(dto.getRpmLimit(), "RPM 上限"));
        row.setTpmLimit(nonNegative(dto.getTpmLimit(), "TPM 上限"));
        row.setStatus(STATUS_ENABLED);
    }

    /** 厂商码：必填，且必须出现在 knowledge.model.vendors 配置里 */
    private String requiredVendorCode(String value) {
        String vendorCode = text(value, "厂商码", VENDOR_CODE_MAX, true);
        Map<String, ModelVendorProperties.Vendor> vendors = vendorMap();
        ThrowUtil.throwIf(!vendors.containsKey(vendorCode), ErrorCode.MODEL_VENDOR_UNKNOWN,
                "厂商码不在配置里：" + vendorCode + "（配置键 knowledge.model.vendors，已配置厂商："
                        + vendors.keySet() + "）");
        return vendorCode;
    }

    /** 是否支持思考模式：留空取 0 不支持，只接受 0 / 1 */
    private Integer flagOf(Integer value) {
        if (NullUtil.isNull(value)) {
            return THINKING_DISABLED;
        }
        ThrowUtil.throwIf(value != THINKING_ENABLED && value != THINKING_DISABLED, ErrorCode.PARAM_INVALID,
                "思考模式标记只接受 " + THINKING_ENABLED + "（支持）或 " + THINKING_DISABLED + "（不支持），当前 " + value);
        return value;
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
        String text = ModelCapability.join(capabilities);
        ThrowUtil.throwIf(text.length() > CAPABILITIES_MAX, ErrorCode.PARAM_INVALID,
                "能力清单最长 " + CAPABILITIES_MAX + " 字符，当前 " + text.length());
        return text;
    }

    /** 能力过滤取值：留空即不按能力过滤，给了值必须在字典内 */
    private ModelCapability capabilityOf(String value) {
        ModelCapability capability = ModelCapability.of(value);
        ThrowUtil.throwIf(StrUtil.isNotBlank(value) && NullUtil.isNull(capability), ErrorCode.PARAM_INVALID,
                "能力取值不在字典内：" + value);
        return capability;
    }

    /** 状态：留空取回落值，给了值必须在字典内（大小写不敏感，落库取大写） */
    private String statusOf(String value, String fallback) {
        String status = StrUtil.trimToNull(value);
        if (NullUtil.isNull(status)) {
            return fallback;
        }
        String upper = status.toUpperCase(Locale.ROOT);
        ThrowUtil.throwIf(!STATUS_DICT.contains(upper), ErrorCode.PARAM_INVALID,
                "状态取值不在字典内：" + value + "（取值 ENABLED / DISABLED）");
        return upper;
    }

    /** 启停收参的状态：必填 */
    private String requiredStatus(String value) {
        String status = statusOf(value, null);
        ThrowUtil.throwIf(NullUtil.isNull(status), ErrorCode.PARAM_INVALID, "状态不能为空（取值 ENABLED / DISABLED）");
        return status;
    }

    /** 币种：留空取回落值，给了值必须在字典内（大小写不敏感，落库取大写） */
    private String currency(String value, String fallback) {
        String currency = StrUtil.trimToNull(value);
        if (NullUtil.isNull(currency)) {
            return NullUtil.isNull(fallback) ? CURRENCY_DEFAULT : fallback;
        }
        String upper = currency.toUpperCase(Locale.ROOT);
        ThrowUtil.throwIf(!CURRENCY_DICT.contains(upper), ErrorCode.PARAM_INVALID,
                "币种取值不在字典内：" + value + "（取值 CNY / USD）");
        return upper;
    }

    /** 整数列：可留空，给了值必须非负 */
    private Integer nonNegative(Integer value, String field) {
        ThrowUtil.throwIf(NullUtil.isNotNull(value) && value < 0, ErrorCode.PARAM_INVALID,
                field + "不能为负数，当前值 " + value);
        return value;
    }

    /** 金额列：可留空，给了值必须非负 */
    private BigDecimal nonNegative(BigDecimal value, String field) {
        ThrowUtil.throwIf(NullUtil.isNotNull(value) && value.signum() < 0, ErrorCode.PARAM_INVALID,
                field + "不能为负数，当前值 " + value);
        return value;
    }

    /** 文本列：去掉首尾空白，必填项为空即拒绝，超列宽即拒绝 */
    private String text(String value, String field, int max, boolean required) {
        String text = StrUtil.trimToNull(value);
        if (NullUtil.isNull(text)) {
            ThrowUtil.throwIf(required, ErrorCode.PARAM_INVALID, field + "不能为空");
            return null;
        }
        ThrowUtil.throwIf(text.length() > max, ErrorCode.PARAM_INVALID,
                field + "最长 " + max + " 字符，当前 " + text.length());
        return text;
    }

    /** 取一行现有记录；查不到即按模型不存在拒绝 */
    private WbModel requireRow(Long id) {
        WbModel row = kbModelDbService.getExistingById(id);
        ThrowUtil.throwIf(NullUtil.isNull(row), ErrorCode.MODEL_NOT_FOUND, "模型不存在：id=" + id);
        return row;
    }

    /** 库表行 → 清单条目：能力串切成字典名清单，厂商展示名按内置映射补齐 */
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

    /** 厂商展示名：内置映射命中取展示名，未命中回落厂商码 */
    private String vendorName(String vendorCode) {
        if (NullUtil.isNull(vendorCode)) {
            return null;
        }
        return VENDOR_DISPLAY_NAMES.getOrDefault(vendorCode, vendorCode);
    }

    /** 状态是否启用 */
    private boolean isEnabled(String status) {
        return STATUS_ENABLED.equals(status);
    }
}
