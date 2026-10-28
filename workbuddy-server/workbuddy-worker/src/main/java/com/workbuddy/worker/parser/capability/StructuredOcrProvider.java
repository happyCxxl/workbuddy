package com.workbuddy.worker.parser.capability;

import com.workbuddy.common.domain.parse.capability.OcrRegion;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.domain.parse.capability.StructuredOcrResult;

/**
 * 结构化 OCR 能力接口：整页无文本且含图片的页交本接口补出文字层。
 * 实现一个类 + 注册 + 打开配置即接入，主流程零改动。
 * 输出结构经平台视觉模态返回（值对象见 common.domain.parse）。
 */
public interface StructuredOcrProvider {

    /**
     * 识别图片/页面区域。
     *
     * @param region 待识别区域
     * @param ctx    调用上下文
     * @return 结构化识别结果
     */
    StructuredOcrResult recognize(OcrRegion region, ProviderContext ctx);
}
