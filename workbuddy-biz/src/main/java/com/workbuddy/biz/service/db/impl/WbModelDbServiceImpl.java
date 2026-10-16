package com.workbuddy.biz.service.db.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.workbuddy.biz.mapper.WbModelMapper;
import com.workbuddy.biz.service.db.WbModelDbService;
import com.workbuddy.common.domain.entity.WbModel;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模型目录数据访问服务实现。
 *
 * @author cxxl
 */
@Service
public class WbModelDbServiceImpl extends InfraDbServiceImpl<WbModelMapper, WbModel> implements WbModelDbService {

    @Override
    public List<WbModel> listByCondition(String status, String keyword) {
        return this.lambdaQuery()
                .eq(StrUtil.isNotBlank(status), WbModel::getStatus, status)
                .like(StrUtil.isNotBlank(keyword), WbModel::getModelKey, keyword)
                .orderByAsc(WbModel::getVendorCode)
                .orderByAsc(WbModel::getModelKey)
                .list();
    }

    @Override
    public WbModel getExistingById(Long id) {
        return NullUtil.isNull(id) ? null : getById(id);
    }

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

    @Override
    public void updateAll(WbModel row) {
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

    @Override
    public void updateStatus(Long id, String status) {
        this.lambdaUpdate()
                .set(WbModel::getStatus, status)
                .eq(WbModel::getId, id)
                .update();
    }
}
