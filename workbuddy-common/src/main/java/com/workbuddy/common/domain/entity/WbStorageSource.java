package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workbuddy.common.domain.base.BaseInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 存储数据源（表：wb_storage_source）：一行 = 一个数据源实例，连接参数落在 config_json。
 *
 * <p>同名类型可以有多行，对象记录按 {@code storage_source_id} 定位到具体哪一行。
 * 当前启用（{@code is_current} = 1）全表至多一行，决定新对象写到哪个数据源。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wb_storage_source")
public class WbStorageSource extends BaseInfo {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 数据源名称（界面展示，全表唯一） */
    private String name;

    /** 存储类型（StorageType 枚举码：minio / local） */
    private String storageType;

    /** 连接参数（JSON，按类型定义；含密钥） */
    private String configJson;

    /** 是否当前启用：1 是 / 0 否 */
    private Integer isCurrent;

    /** 状态码值（StorageSourceStatus：ENABLED 启用 / DISABLED 停用） */
    private String status;

    /** 最近一次连接探测结果：true 通过 / false 失败 / null 从未探测 */
    private Boolean lastProbeOk;

    /** 最近一次连接探测时间；从未探测为空 */
    private LocalDateTime lastProbeAt;
}
