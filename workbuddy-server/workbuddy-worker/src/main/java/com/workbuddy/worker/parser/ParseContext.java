package com.workbuddy.worker.parser;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.worker.parser.asset.ParseAssetSink;
import lombok.Data;

import java.io.InputStream;

/**
 * 解析上下文（解析机制的执行上下文，留在 worker：持有输入流与解析配置引用）。
 * 不关闭输入流。
 */
@Data
public class ParseContext {

    /** 知识库文件 ID（血缘起点，落 resultId） */
    private Long knowledgeFileId;

    /** 文件引用（文档输入环节建档数据） */
    private FileReference fileRef;

    /** 文件流（解析器不关闭） */
    private InputStream inputStream;

    /** 解析阈值配置 */
    private ParseProperties properties;

    /** 取消令牌（看门狗超时/服务停机时置位；默认未取消） */
    private CancellationToken cancellationToken = new CancellationToken();

    /** 资产写入口（biz 注入对象存储实现；单测/未接存储时为空实现） */
    private ParseAssetSink assetSink = ParseAssetSink.noop();
}
