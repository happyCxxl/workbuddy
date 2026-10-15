package com.workbuddy.worker.parser;

import com.workbuddy.common.domain.input.FileReference;
import lombok.Data;

import java.io.InputStream;

/**
 * 解析上下文（解析机制的执行上下文，留在 worker：持有输入流与解析配置引用）。
 * 不关闭输入流。
 *
 * @author cxxl
 */
@Data
public class ParseContext {

    /** 文件结果 ID（血缘起点，落 resultId） */
    private Long fileResultId;

    /** 文件引用（文档输入环节建档数据） */
    private FileReference fileRef;

    /** 文件流（解析器不关闭） */
    private InputStream inputStream;

    /** 解析阈值配置 */
    private ParseProperties properties;
}
