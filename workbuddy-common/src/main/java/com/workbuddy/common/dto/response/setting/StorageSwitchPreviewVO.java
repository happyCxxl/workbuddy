package com.workbuddy.common.dto.response.setting;

import lombok.Data;

/**
 * 存储数据源切换预览 VO：目标数据源、当前启用的数据源与切换影响面。
 *
 * <p>预览响应里的 {@code current*} 是切换前的值；确认切换后的响应复用本类，
 * 那一份由服务在切换完成后按新的生效值回填。
 *
 * @author cxxl
 */
@Data
public class StorageSwitchPreviewVO {

    /** 目标数据源 ID */
    private Long targetSourceId;

    /** 目标数据源名称 */
    private String targetName;

    /** 目标数据源类型码值 */
    private String targetType;

    /** 当前启用的数据源 ID（未启用任何数据源时为空） */
    private Long currentSourceId;

    /** 当前启用的数据源名称（与 currentSourceId 同口径） */
    private String currentName;

    /** 当前启用的数据源类型码值（与 currentSourceId 同口径） */
    private String currentType;

    /** 属于其他数据源且排队中的任务数 */
    private long queuedCount;

    /** 属于其他数据源且执行中的任务数 */
    private long runningCount;

    /** 影响面提示文案 */
    private String message;
}
