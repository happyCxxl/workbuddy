package com.workbuddy.worker.preprocessing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 预处理阈值配置（knowledge.preprocess 前缀；application.yml 同名 kebab-case 键）。
 * 识别侧阈值在解析/组装环节配置，本类只留处置聚合阈值与摘要上限。
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.preprocess")
public class PreprocessProperties {

    /** 目录页判定：该页 TOC_LINE 元素数下限（草案 ≥3 行） */
    private int tocMinLinesPerPage = 3;

    /** 目录连续窗口：Word 无页概念，连续 TOC_LINE 元素数下限（草案 ≥3 行） */
    private int tocRunMinLength = 3;

    /** trace 前后摘要截断长度（防膨胀） */
    private int traceBeforeAfterMaxLen = 80;
}
