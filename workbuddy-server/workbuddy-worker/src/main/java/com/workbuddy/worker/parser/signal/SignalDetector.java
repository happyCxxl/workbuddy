package com.workbuddy.worker.parser.signal;

import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.signal.Signal;
import com.workbuddy.worker.parser.ParseContext;

import java.util.List;

/**
 * 信号判定器：native 路元素 + 页级指标 + 解析事实 → 信号集合。
 * 判定顺序先便宜后贵（页级字符统计 → 乱码率 → 文字占比 → 表格 → 版面 → 结构缺口），
 * 全链执行、聚合信号（不命中即断链），供能力扩展点按需升级。
 */
public interface SignalDetector {

    /**
     * 判定信号。
     *
     * @param nativeSource 原生解析路
     * @param context      解析上下文（阈值配置）
     * @return 信号集合（可能为空）
     */
    List<Signal> detect(ParseSource nativeSource, ParseContext context);
}
