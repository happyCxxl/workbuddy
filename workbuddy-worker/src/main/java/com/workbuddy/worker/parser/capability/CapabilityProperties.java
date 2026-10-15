package com.workbuddy.worker.parser.capability;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模型能力开关（knowledge.parse.capability 前缀；三项开关默认 false）。
 * 开关只控制"已注册实现是否启用"；开关关闭或无实现时恒走内置降级。
 *
 * <p>能力的连接与凭据不在这里：厂商地址、密钥与超时取自 {@code knowledge.model.vendors.<厂商码>}，
 * 候选模型由模型目录按能力筛。
 *
 * @author cxxl
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.parse.capability")
public class CapabilityProperties {

    /** 结构化 OCR 开关（默认关） */
    private boolean ocrEnabled = false;

    /** 版面分析开关（默认关） */
    private boolean layoutEnabled = false;

    /** 表格结构识别开关（默认关） */
    private boolean tableEnabled = false;
}
