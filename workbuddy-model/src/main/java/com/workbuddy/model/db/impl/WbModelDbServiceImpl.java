package com.workbuddy.model.db.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.workbuddy.common.domain.entity.WbModel;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import com.workbuddy.model.db.WbModelDbService;
import com.workbuddy.model.mapper.WbModelMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模型目录数据访问服务实现。
 */
@Service
public class WbModelDbServiceImpl extends InfraDbServiceImpl<WbModelMapper, WbModel> implements WbModelDbService {

    /**
     * 按状态列模型目录。
     *
     * @param status 状态码值（可空，空则不过滤）
     * @return 模型列表，按厂商与模型键升序
     */
    @Override
    public List<WbModel> listByCondition(String status) {
        return this.lambdaQuery()
                .eq(StrUtil.isNotBlank(status), WbModel::getStatus, status)
                .orderByAsc(WbModel::getVendorCode)
                .orderByAsc(WbModel::getModelKey)
                .list();
    }

    /**
     * 按条件分页查模型，命中条件为空则不加该过滤条件。
     *
     * @param current    页码
     * @param size       每页条数
     * @param status     状态码值（可空）
     * @param keyword    模型键模糊匹配（可空）
     * @param capability 能力项，按逗号清单整项匹配（可空）
     * @return 模型分页结果
     */
    @Override
    public IPage<WbModel> pageByCondition(long current, long size, String status, String keyword,
            String capability) {
        return this.lambdaQuery()
                .eq(StrUtil.isNotBlank(status), WbModel::getStatus, status)
                .like(StrUtil.isNotBlank(keyword), WbModel::getModelKey, keyword)
                // 能力列是逗号分隔清单：FIND_IN_SET 做整项匹配
                .apply(NullUtil.isNotNull(capability), "FIND_IN_SET({0}, capabilities)",
                        NullUtil.isNull(capability) ? "" : capability)
                .orderByAsc(WbModel::getVendorCode)
                .orderByAsc(WbModel::getModelKey)
                .page(new Page<>(current, size));
    }

    /**
     * 按 ID 取模型行。
     *
     * @param id 模型 ID（可空）
     * @return 模型行；ID 为空或不存在时为 null
     */
    @Override
    public WbModel getExistingById(Long id) {
        return NullUtil.isNull(id) ? null : getById(id);
    }

    /**
     * 厂商与模型键的组合是否已存在。
     *
     * @param vendorCode 厂商码值
     * @param modelKey   模型键
     * @param excludeId  排除的模型 ID（编辑时排除自身，可空）
     * @return 已存在为 true
     */
    @Override
    public boolean existsVendorAndModelKey(String vendorCode, String modelKey, Long excludeId) {
        // 只取主键列：判定存在性不需要整行
        List<WbModel> rows = this.lambdaQuery()
                .select(WbModel::getId)
                .eq(WbModel::getVendorCode, vendorCode)
                .eq(WbModel::getModelKey, modelKey)
                .ne(NullUtil.isNotNull(excludeId), WbModel::getId, excludeId)
                .list();
        return !rows.isEmpty();
    }

    /**
     * 更新模型：值为空的数值列显式写 NULL，否则实体更新策略会跳过、旧值清不掉。
     *
     * @param row 模型行
     */
    @Override
    public void updateModel(WbModel row) {
        LambdaUpdateWrapper<WbModel> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(WbModel::getId, row.getId());
        clearIfNull(wrapper, WbModel::getInputLimit, row.getInputLimit());
        clearIfNull(wrapper, WbModel::getOutputLimit, row.getOutputLimit());
        clearIfNull(wrapper, WbModel::getThinkingInputLimit, row.getThinkingInputLimit());
        clearIfNull(wrapper, WbModel::getThinkingOutputLimit, row.getThinkingOutputLimit());
        clearIfNull(wrapper, WbModel::getContextWindow, row.getContextWindow());
        clearIfNull(wrapper, WbModel::getPriceInPerM, row.getPriceInPerM());
        clearIfNull(wrapper, WbModel::getPriceInPerMCacheHit, row.getPriceInPerMCacheHit());
        clearIfNull(wrapper, WbModel::getPriceOutPerM, row.getPriceOutPerM());
        clearIfNull(wrapper, WbModel::getRpmLimit, row.getRpmLimit());
        clearIfNull(wrapper, WbModel::getTpmLimit, row.getTpmLimit());
        update(row, wrapper);
    }

    /** 值被清空的列显式写 NULL：实体更新策略跳过 null，缺这一句就清不掉旧值 */
    private <T> void clearIfNull(LambdaUpdateWrapper<WbModel> wrapper, SFunction<WbModel, T> column, T value) {
        if (NullUtil.isNull(value)) {
            wrapper.set(column, null);
        }
    }

    /**
     * 只更新模型状态。
     *
     * @param id     模型 ID
     * @param status 目标状态码值
     */
    @Override
    public void updateStatus(Long id, String status) {
        this.lambdaUpdate()
                .set(WbModel::getStatus, status)
                .eq(WbModel::getId, id)
                .update();
    }
}
