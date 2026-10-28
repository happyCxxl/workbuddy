package com.workbuddy.common.domain.parse;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 质量信息：告警清单 + 扫描页/失败页清单（只标记，裁决在成功占比门槛）。
 */
@Data
public class QualityInfo {

    /** 告警列表 */
    private List<QualityWarning> warnings = new ArrayList<>();

    /** 扫描页页码列表（OCR 预留） */
    private List<Integer> scannedPages = new ArrayList<>();

    /** 空白页页码列表（无文本且无图片；既不计成功也不计失败，只从成功占比的分母里剔除） */
    private List<Integer> blankPages = new ArrayList<>();

    /** 失败页/失败单元清单（页号；Excel 为 sheet 序号） */
    private List<Integer> failedPages = new ArrayList<>();
}
