package com.workbuddy.worker.structure.craft;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 重复/噪声识别结果：标记统计 + 告警（预处理环节处置依据）。
 */
@Data
public class MarkOutcome {

    /** 重复页数（除首份外） */
    private int repeatPageCount;

    /** 重复段数（除首份外） */
    private int repeatSegmentCount;

    /** 噪声页数 */
    private int noisePageCount;

    /** 告警信息 */
    private List<String> warnings = new ArrayList<>();
}
