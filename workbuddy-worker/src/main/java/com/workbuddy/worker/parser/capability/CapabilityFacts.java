package com.workbuddy.worker.parser.capability;

import com.workbuddy.common.domain.parse.capability.LayoutResult;
import com.workbuddy.common.domain.parse.capability.StructuredOcrResult;
import com.workbuddy.common.domain.parse.capability.TableResult;
import com.workbuddy.common.domain.parse.signal.ParseFact;
import com.workbuddy.common.enums.parse.SignalType;
import com.workbuddy.common.utils.NullUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 能力调用留痕：模型补强成功记一条「能力补强」事实，失败（未配置 / 非 2xx / 超时 / 结果不完整）记一条
 * 「能力失败已降级」事实 —— 两类都挂 `TABLE` 事实类型，靠证据文案区分，降级子类型仍由事实类型推导。
 *
 * @author cxxl
 */
public final class CapabilityFacts {

    private CapabilityFacts() {
    }

    /**
     * 补强事实：模型给出了可用结构。
     *
     * @param region 表格区域
     * @param result 结构识别结果
     * @return 补强事实
     */
    public static ParseFact tableEnhanced(String region, TableResult result) {
        ParseFact fact = fact(region);
        Integer rows = NullUtil.isNull(result.getRows()) ? Integer.valueOf(0) : result.getRows();
        Integer cols = NullUtil.isNull(result.getCols()) ? Integer.valueOf(0) : result.getCols();
        fact.setEvidence(String.format("表格结构由能力补强（%s %s，%d 行 × %d 列）",
                StrUtil.blankToDefault(result.getModel(), "model"),
                StrUtil.blankToDefault(result.getVersion(), "-"), rows, cols));
        return fact;
    }

    /**
     * 降级留痕：能力不可用或结果不可用，按内置规则降级。
     *
     * @param region 表格区域
     * @param reason 失败原因（未配置 / 非 2xx / 超时 / 结果不完整等）
     * @return 降级事实
     */
    public static ParseFact tableDegraded(String region, String reason) {
        ParseFact fact = fact(region);
        fact.setEvidence("表格能力未产出结构，已按内置规则降级（" + StrUtil.blankToDefault(reason, "未配置") + "）");
        return fact;
    }

    /**
     * 版面补强事实：模型给出了区域分类与阅读顺序。
     *
     * @param region      页区域
     * @param result      版面分析结果
     * @return 补强事实
     */
    public static ParseFact layoutEnhanced(String region, LayoutResult result) {
        ParseFact fact = fact(region, SignalType.LAYOUT);
        fact.setEvidence(String.format("版面阅读顺序由能力补强（%s %s，%d 个区域）",
                StrUtil.blankToDefault(result.getModel(), "model"),
                StrUtil.blankToDefault(result.getVersion(), "-"),
                NullUtil.isNull(result.getRegions()) ? 0 : result.getRegions().size()));
        return fact;
    }

    /**
     * 版面降级留痕：能力不可用或结果不可用，按内置几何口径输出阅读顺序。
     *
     * @param region 页区域
     * @param reason 失败原因
     * @return 降级事实
     */
    public static ParseFact layoutDegraded(String region, String reason) {
        ParseFact fact = fact(region, SignalType.LAYOUT);
        fact.setEvidence("版面能力未产出顺序，已按内置几何口径输出（" + StrUtil.blankToDefault(reason, "未配置") + "）");
        return fact;
    }

    /**
     * OCR 补强事实：能力给出了逐行文本。
     *
     * @param region 页区域
     * @param result 识别结果
     * @return 补强事实
     */
    public static ParseFact ocrEnhanced(String region, StructuredOcrResult result) {
        ParseFact fact = fact(region, SignalType.OCR_TEXT);
        fact.setEvidence(String.format("文字由能力补出（%s %s，%d 行）",
                StrUtil.blankToDefault(result.getModel(), "model"),
                StrUtil.blankToDefault(result.getVersion(), "-"),
                NullUtil.isNull(result.getLines()) ? 0 : result.getLines().size()));
        return fact;
    }

    /**
     * OCR 降级留痕：能力不可用或结果不可用，该页保持原样（图片只记引用与图注）。
     *
     * @param region 页区域
     * @param reason 失败原因
     * @return 降级事实
     */
    public static ParseFact ocrDegraded(String region, String reason) {
        ParseFact fact = fact(region, SignalType.OCR_TEXT);
        fact.setEvidence("文字能力未补出内容（" + StrUtil.blankToDefault(reason, "未配置") + "）");
        return fact;
    }

    private static ParseFact fact(String region) {
        return fact(region, SignalType.TABLE);
    }

    private static ParseFact fact(String region, SignalType type) {
        ParseFact fact = new ParseFact();
        fact.setType(type);
        fact.setRegion(region);
        return fact;
    }
}
