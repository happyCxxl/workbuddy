package com.workbuddy.worker.parser.pdf.page.table;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.Region;
import com.workbuddy.worker.parser.pdf.page.PageContext;

import java.util.List;

/**
 * 表格构建策略：有边框表 / 无边框表 / 模型结构三种实现共用同一入口形态。
 *
 * <p>契约：构建器只判定与组装，<strong>不把元素写入页面</strong>；产出经 {@link TableBuilder.Result}
 * 返回，由 {@link TableBuildChain} 统一写入 {@link PageContext#getElements()}。
 * 构建器可以按需向 {@link PageContext} 追加事实与能力使用记录（内部证据只有构建器知道）。
 */
public interface TableBuilder {

    /**
     * 失败类别：链上裁决的依据。
     */
    enum Failure {

        /** 线网格未拟合：把块内行重新分类后逐块走文本路径 */
        GRID_UNFIT,

        /** 规则结构判定失败：交给模型结构策略补强 */
        STRUCTURE_FAILED,

        /** 判定为不值得成表：直接降级为段落 */
        UNWORTHY
    }

    /**
     * 一次构建的结果。
     *
     * @param table   产出的表格元素（null 表示未产出）
     * @param failure 失败类别（产出成功时为 null）
     * @param reason  失败原因文案（降级留痕用；产出成功时为 null）
     */
    record Result(ParseElement table, Failure failure, String reason) {

        /**
         * 构建成功。
         *
         * @param table 表格元素
         * @return 成功结果
         */
        static Result built(ParseElement table) {
            return new Result(table, null, null);
        }

        /**
         * 构建失败。
         *
         * @param failure 失败类别
         * @param reason  失败原因文案
         * @return 失败结果
         */
        static Result failed(Failure failure, String reason) {
            return new Result(null, failure, reason);
        }
    }

    /**
     * 单块构建输入：页面工作区 + 待构建的行块与所属线框区域。
     *
     * @param context 页面工作区（元素、事实、能力记录与配置都从它取）
     * @param lines   块内文本行（按页内阅读顺序）
     * @param region  线框区域；文本块为 null
     */
    record Input(PageContext context, List<PageLine> lines, Region region) {

        /**
         * 换行块派生输入（重新分类后的子块复用同一工作区）。
         *
         * @param value 新的行块
         * @return 同工作区的新输入
         */
        Input withLines(List<PageLine> value) {
            return new Input(context, value, region);
        }

        /**
         * 换区域派生输入。
         *
         * @param value 新的线框区域
         * @return 同工作区的新输入
         */
        Input withRegion(Region value) {
            return new Input(context, lines, value);
        }
    }

    /**
     * 为输入块构建表格。
     *
     * @param input 构建输入
     * @return 构建结果（永不为 null）
     */
    Result build(Input input);
}
