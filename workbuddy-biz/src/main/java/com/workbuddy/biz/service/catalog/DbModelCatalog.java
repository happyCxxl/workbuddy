package com.workbuddy.biz.service.catalog;

import com.workbuddy.biz.mapper.KbModelMapper;
import com.workbuddy.common.domain.entity.KbModel;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.enums.embed.EmbeddingModel;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import com.workbuddy.model.catalog.ModelCatalogPort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 模型目录的库表实现（表：kb_model），按端口契约供 knowledge-model 的组件注入。
 *
 * <p>筛选口径两段：启用状态与生效区间在 SQL 里筛（走 idx_status_capabilities），能力在应用侧按逗号分隔串切分比对。
 * 能力不用 LIKE 的理由是子串会误命中（例如能力串里含 OCR 的行会被 TABLE_STRUCTURE 之外的短取值串到），
 * 且本表是配置级表、行数在几十行量级，整表候选集一次读入内存没有规模风险。
 *
 * <p>向量规格（维度 / 度量 / 归一化 / 单批上限）仍由 common 的 {@link EmbeddingModel} 枚举承载，
 * 本类按模型名叠加到目录视图上；枚举里没有的模型名在向量编码链路上不可用。
 *
 * @author cxxl
 */
@Service
public class DbModelCatalog extends InfraDbServiceImpl<KbModelMapper, KbModel> implements ModelCatalogPort {

    /** 启用状态码值（kb_model.status） */
    private static final String STATUS_ENABLED = "ENABLED";

    @Override
    public List<ModelDescriptor> listByCapability(ModelCapability capability) {
        if (NullUtil.isNull(capability)) {
            return List.of();
        }
        List<KbModel> rows = this.lambdaQuery()
                .eq(KbModel::getStatus, STATUS_ENABLED)
                // 生效区间：起点留空视为立即生效，终点留空视为长期有效，两端都按开放区间判定
                .and(wrapper -> wrapper.isNull(KbModel::getEffectiveFrom)
                        .or().le(KbModel::getEffectiveFrom, LocalDateTime.now()))
                .and(wrapper -> wrapper.isNull(KbModel::getEffectiveTo)
                        .or().gt(KbModel::getEffectiveTo, LocalDateTime.now()))
                .orderByAsc(KbModel::getModelKey)
                .list();
        List<ModelDescriptor> candidates = new ArrayList<>();
        for (KbModel row : rows) {
            ModelDescriptor descriptor = toDescriptor(row);
            if (descriptor.supports(capability)) {
                candidates.add(descriptor);
            }
        }
        return candidates;
    }

    @Override
    public ModelDescriptor findByVendorAndModelKey(String vendorCode, String modelKey) {
        if (NullUtil.isNull(vendorCode) || NullUtil.isNull(modelKey)) {
            return null;
        }
        KbModel row = this.lambdaQuery()
                .eq(KbModel::getVendorCode, vendorCode)
                .eq(KbModel::getModelKey, modelKey)
                .one();
        return NullUtil.isNull(row) ? null : toDescriptor(row);
    }

    @Override
    public ModelDescriptor findByModelKey(String modelKey) {
        List<KbModel> rows = rowsByModelKey(modelKey, false);
        return rows.isEmpty() ? null : toDescriptor(rows.getFirst());
    }

    @Override
    public ModelDescriptor findUniqueEnabledByModelKey(String modelKey) {
        List<KbModel> rows = rowsByModelKey(modelKey, true);
        if (rows.size() > 1) {
            throw new KnowledgeException(ErrorCode.MODEL_NOT_FOUND,
                    "模型：" + modelKey + " 在目录里有多个启用条目，厂商：" + vendors(rows));
        }
        return rows.isEmpty() ? null : toDescriptor(rows.getFirst());
    }

    /** 按模型名查行：启用筛选开关打开时只取 ENABLED，命中多行按厂商码升序（同名的取值稳定） */
    private List<KbModel> rowsByModelKey(String modelKey, boolean enabledOnly) {
        if (NullUtil.isNull(modelKey) || modelKey.isBlank()) {
            return List.of();
        }
        List<KbModel> rows = new ArrayList<>(this.lambdaQuery()
                .eq(KbModel::getModelKey, modelKey)
                .eq(enabledOnly, KbModel::getStatus, STATUS_ENABLED)
                .list());
        rows.sort(Comparator.comparing(KbModel::getVendorCode, Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    /** 同模型名命中的厂商码清单（错误信息里指出是哪几家） */
    private String vendors(List<KbModel> rows) {
        List<String> codes = new ArrayList<>(rows.size());
        for (KbModel row : rows) {
            codes.add(row.getVendorCode());
        }
        return String.join(", ", codes);
    }

    /** 库表行 → 目录视图：能力串切分，向量编码能力再叠加枚举里的向量规格 */
    private ModelDescriptor toDescriptor(KbModel row) {
        Set<ModelCapability> capabilities = ModelCapability.parse(row.getCapabilities());
        ModelDescriptor descriptor = new ModelDescriptor();
        descriptor.setVendorCode(row.getVendorCode());
        descriptor.setModelKey(row.getModelKey());
        descriptor.setDisplayName(row.getDisplayName());
        descriptor.setCapabilities(capabilities);
        descriptor.setContextWindow(row.getContextWindow());
        descriptor.setPriceInPerM(row.getPriceInPerM());
        descriptor.setPriceOutPerM(row.getPriceOutPerM());
        descriptor.setPriceImagePerPage(row.getPriceImagePerPage());
        descriptor.setPricePerThousandCalls(row.getPricePerThousandCalls());
        descriptor.setCurrency(row.getCurrency());
        descriptor.setRpmLimit(row.getRpmLimit());
        descriptor.setTpmLimit(row.getTpmLimit());
        descriptor.setStatus(row.getStatus());
        descriptor.setEffectiveFrom(row.getEffectiveFrom());
        descriptor.setEffectiveTo(row.getEffectiveTo());
        descriptor.setRemark(row.getRemark());
        if (capabilities.contains(ModelCapability.EMBEDDING)) {
            descriptor.setEmbedSpecs(embedSpecs(row.getModelKey()));
        }
        return descriptor;
    }

    /** 向量规格：取 common 枚举同位模型名的那一项；枚举里没有该模型名即返回 null（该模型不承载向量编码） */
    private ModelDescriptor.EmbedSpecs embedSpecs(String modelKey) {
        EmbeddingModel model = EmbeddingModel.of(modelKey);
        if (NullUtil.isNull(model)) {
            return null;
        }
        ModelDescriptor.EmbedSpecs specs = new ModelDescriptor.EmbedSpecs();
        specs.setDimension(model.dimension());
        specs.setMetric(model.metric());
        specs.setNormalized(model.normalized());
        specs.setContextWindowTokens(model.contextWindowTokens());
        specs.setBatchLimit(model.batchLimit());
        return specs;
    }
}
