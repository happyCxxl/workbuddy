package com.workbuddy.biz.service.db.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.workbuddy.biz.mapper.KbModelMapper;
import com.workbuddy.biz.service.db.KbModelDbService;
import com.workbuddy.common.domain.entity.KbModel;
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
public class KbModelDbServiceImpl extends InfraDbServiceImpl<KbModelMapper, KbModel> implements KbModelDbService {

    @Override
    public List<KbModel> listByCondition(String status, String keyword) {
        return this.lambdaQuery()
                .eq(StrUtil.isNotBlank(status), KbModel::getStatus, status)
                .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                        .like(KbModel::getModelKey, keyword)
                        .or().like(KbModel::getDisplayName, keyword))
                .orderByAsc(KbModel::getVendorCode)
                .orderByAsc(KbModel::getModelKey)
                .list();
    }

    @Override
    public KbModel getExistingById(Long id) {
        return NullUtil.isNull(id) ? null : getById(id);
    }

    @Override
    public boolean existsVendorAndModelKey(String vendorCode, String modelKey, Long excludeId) {
        // 只取主键列：判定存在性不需要整行
        List<KbModel> rows = this.lambdaQuery()
                .select(KbModel::getId)
                .eq(KbModel::getVendorCode, vendorCode)
                .eq(KbModel::getModelKey, modelKey)
                .ne(NullUtil.isNotNull(excludeId), KbModel::getId, excludeId)
                .list();
        return !rows.isEmpty();
    }

    @Override
    public void updateAll(KbModel row) {
        LambdaUpdateWrapper<KbModel> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(KbModel::getId, row.getId());
        clearIfNull(wrapper, KbModel::getContextWindow, row.getContextWindow());
        clearIfNull(wrapper, KbModel::getPriceInPerM, row.getPriceInPerM());
        clearIfNull(wrapper, KbModel::getPriceOutPerM, row.getPriceOutPerM());
        clearIfNull(wrapper, KbModel::getPriceImagePerPage, row.getPriceImagePerPage());
        clearIfNull(wrapper, KbModel::getPricePerThousandCalls, row.getPricePerThousandCalls());
        clearIfNull(wrapper, KbModel::getRpmLimit, row.getRpmLimit());
        clearIfNull(wrapper, KbModel::getTpmLimit, row.getTpmLimit());
        clearIfNull(wrapper, KbModel::getEffectiveFrom, row.getEffectiveFrom());
        clearIfNull(wrapper, KbModel::getEffectiveTo, row.getEffectiveTo());
        clearIfNull(wrapper, KbModel::getRemark, row.getRemark());
        update(row, wrapper);
    }

    /** 值被清空的列显式写 NULL：实体更新策略跳过 null，缺这一句就清不掉旧值 */
    private <T> void clearIfNull(LambdaUpdateWrapper<KbModel> wrapper, SFunction<KbModel, T> column, T value) {
        if (NullUtil.isNull(value)) {
            wrapper.set(column, null);
        }
    }

    @Override
    public void updateStatus(Long id, String status) {
        this.lambdaUpdate()
                .set(KbModel::getStatus, status)
                .eq(KbModel::getId, id)
                .update();
    }
}
