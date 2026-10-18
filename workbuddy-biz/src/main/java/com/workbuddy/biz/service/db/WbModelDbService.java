package com.workbuddy.biz.service.db;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.common.domain.entity.WbModel;
import com.workbuddy.infra.persistence.InfraDbService;

import java.util.List;

/**
 * 模型目录数据访问服务（表：wb_model）：清单分页查询与三处定点回写。
 *
 * <p>状态与关键字在 SQL 里筛，能力按逗号分隔清单整项匹配；清单按厂商码与模型名升序，取值稳定。
 */
public interface WbModelDbService extends InfraDbService<WbModel> {

    /**
     * 按状态取全部行（注册表装载路径）。
     *
     * @param status 状态码值，可空 = 不过滤
     * @return 命中的行（厂商码升序、模型名升序）
     */
    List<WbModel> listByCondition(String status);

    /**
     * 分页取行：状态等值、模型名模糊、能力整项匹配。
     *
     * @param current    页码（1 起）
     * @param size       每页条数
     * @param status     状态码值，可空
     * @param keyword    模型名模糊关键字，可空
     * @param capability 能力（对 capabilities 清单整项匹配），可空
     * @return 分页结果（厂商码升序、模型名升序）
     */
    IPage<WbModel> pageByCondition(long current, long size, String status, String keyword, String capability);

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
     * 覆盖式更新一行的业务列（厂商码、模型名、能力、思考模式标记、四个长度列、三个价格列、币种、两个限额、
     * 状态）：实体带什么值就写什么值，**被清空的列写成 NULL**。
     *
     * @param row 目标行（主键必填，业务列按覆盖口径写入）
     */
    void updateModel(WbModel row);

    /**
     * 回写状态（只动这一列）。
     *
     * @param id     主键
     * @param status 目标状态码值
     */
    void updateStatus(Long id, String status);
}
