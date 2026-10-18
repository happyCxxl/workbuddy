package com.workbuddy.biz.service;

import com.workbuddy.common.dto.response.setting.StorageSourceVO;
import com.workbuddy.common.dto.response.setting.StorageSwitchPreviewVO;
import com.workbuddy.common.dto.response.setting.StorageSwitchResultVO;

import java.util.List;

/**
 * 存储数据源服务：数据源清单与当前启用切换。
 *
 * <p>数据源一行 = 一个实例（可同名类型多行），当前启用全表至多一行；新对象写到当前启用的数据源，
 * 读取按对象记录里的数据源 ID。设为当前启用前先做连接测试，不通过不落库。
 */
public interface StorageSourceService {

    /** 数据源清单（含连接参数、启用与接入标记、最近一次探测结论） */
    List<StorageSourceVO> list();

    /**
     * 切换预览：目标可用性与参数齐全校验后统计影响面；不落库、不切换、不探活。
     *
     * @param id 目标数据源 ID
     * @return 目标数据源、当前启用的数据源与影响面
     */
    StorageSwitchPreviewVO previewCurrent(Long id);

    /**
     * 设为当前启用：连接测试 → 先清后置 is_current 并回写探测结论 → 落审计 → 替换运行时当前启用。
     *
     * @param id       目标数据源 ID
     * @param operator 操作人
     * @return 切换结果：目标数据源、切换前的当前数据源与影响面
     */
    StorageSwitchResultVO switchCurrent(Long id, String operator);
}
