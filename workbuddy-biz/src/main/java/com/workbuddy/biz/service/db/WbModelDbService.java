package com.workbuddy.biz.service.db;

import com.workbuddy.common.domain.entity.WbModel;
import com.workbuddy.infra.persistence.InfraDbService;

import java.util.List;

/**
 * 模型目录数据访问服务（表：wb_model）：管理页面的清单查询与三处定点回写。
 *
 * <p>状态与关键字在 SQL 里筛，能力在业务层按逗号分隔串比对（子串匹配会误命中短取值，
 * 判定口径与目录端口一致）；清单按厂商码与模型名升序，取值稳定。
 *
 * @author cxxl
 */
public interface WbModelDbService extends InfraDbService<WbModel> {

    /**
     * 按状态与关键字取行（两项都可留空 = 不过滤）。
     *
     * @param status  状态码值，可空
     * @param keyword 关键字（匹配模型名或展示名），可空
     * @return 命中的行（厂商码升序、模型名升序）
     */
    List<WbModel> listByCondition(String status, String keyword);

    /**
     * 取一行现有记录。
     *
     * @param id 主键
     * @return 行；主键为空或行不存在为 null
     */
    WbModel getExistingById(Long id);

    /**
     * 唯一键判定：同厂商下是否已有该模型名。
     *
     * @param vendorCode 厂商码
     * @param modelKey   厂商侧模型名
     * @param excludeId  排除的主键（编辑时排除自己），可空
     * @return 已存在为 true
     */
    boolean existsVendorAndModelKey(String vendorCode, String modelKey, Long excludeId);

    /**
     * 覆盖式更新一行的业务列（厂商码、模型名、展示名、能力、上下文窗口、四个价格列、币种、两个限额、
     * 状态、生效区间、备注）：实体带什么值就写什么值，**被清空的列写成 NULL**。
     *
     * @param row 目标行（主键必填，业务列按覆盖口径写入）
     */
    void updateAll(WbModel row);

    /**
     * 回写状态（只动这一列）。
     *
     * @param id     主键
     * @param status 目标状态码值
     */
    void updateStatus(Long id, String status);
}
