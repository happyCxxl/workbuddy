package com.workbuddy.model.catalog;

import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;

import java.util.List;

/**
 * 模型目录 Port：目录是「能力 × 模型」的查询入口。
 *
 * <p>三类查询：按能力列候选（已启用且落在生效区间内）、按厂商码 + 模型名精确取一条、按模型名取唯一启用模型。
 * 返回类型是 common 里的目录视图 {@link ModelDescriptor}，库表实体与 Mapper 不进入本模块。
 * 候选按能力筛，冷却 / 在途 / 延迟 / 限额按模型算（D26），后三者由路由与治理层实现。
 *
 * @author cxxl
 */
public interface ModelCatalogPort {

    /**
     * 按能力列候选：只返回已启用且落在生效区间内的模型，一个模型声明多个能力时同时出现在各自的候选集里。
     *
     * @param capability 能力
     * @return 候选模型列表（按模型名升序，可能为空）
     */
    List<ModelDescriptor> listByCapability(ModelCapability capability);

    /**
     * 按厂商码与模型名精确取一条（不限状态与生效区间，供校验与页面回显）。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     * @return 目录项；未收录返回 null
     */
    ModelDescriptor findByVendorAndModelKey(String vendorCode, String modelKey);

    /**
     * 按模型名取目录项（不限状态与生效区间）：未收录与已停用两种情形由取值区分。
     *
     * @param modelKey 模型名
     * @return 目录项；未收录返回 null
     */
    ModelDescriptor findByModelKey(String modelKey);

    /**
     * 按模型名取唯一启用模型：用于「模型 → 厂商」解析，多个厂商收录同名模型时拒绝而不是取第一个。
     *
     * @param modelKey 模型名
     * @return 唯一命中的启用模型（已启用且落在生效区间内）；未收录返回 null
     * @throws com.workbuddy.common.exception.KnowledgeException 多个启用模型同名（判定不出厂商）
     */
    ModelDescriptor findUniqueEnabledByModelKey(String modelKey);
}
