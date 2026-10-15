package com.workbuddy.common.dto.response.setting;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 存储数据源切换结果 VO：目标数据源、切换前后的当前数据源与本次切换的影响面。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StorageSwitchResultVO extends StorageSwitchPreviewVO {

    /** 切换后当前启用的数据源 ID（与目标一致） */
    private Long switchedSourceId;
}
