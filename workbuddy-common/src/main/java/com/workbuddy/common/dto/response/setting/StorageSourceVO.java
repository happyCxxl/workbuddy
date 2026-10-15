package com.workbuddy.common.dto.response.setting;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存储数据源条目 VO：数据源清单里的一行。
 *
 * <p>连接参数按键值原样下发，密钥类参数同样包含在内。
 *
 * @author cxxl
 */
@Data
public class StorageSourceVO {

    /** 数据源 ID */
    private Long id;

    /** 数据源名称 */
    private String name;

    /** 存储类型码值（minio / local） */
    private String storageType;

    /** 存储类型显示名（MinIO / 本地） */
    private String storageTypeName;

    /** 状态码值（ENABLED / DISABLED） */
    private String status;

    /** 是否当前启用 */
    private boolean current;

    /** 必填参数是否齐全（密钥按库里已存值判断） */
    private boolean configured;

    /** 是否已接入运行时路由（未停用且参数齐全） */
    private boolean registered;

    /** 最近一次连接探测结果：true 通过 / false 失败 / null 从未探测 */
    private Boolean probeOk;

    /** 最近一次连接探测时间（ISO-8601 文本，与实体时间字段的序列化口径一致）；从未探测为空 */
    private String probeAt;

    /** 连接参数（按键取值，含密钥类参数） */
    private Map<String, String> params = new LinkedHashMap<>();
}
