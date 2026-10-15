package com.workbuddy.worker.input;

import com.workbuddy.common.domain.input.FileMetadata;
import com.workbuddy.common.enums.input.FileFormat;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.io.InputStream;

/**
 * 校验链共享上下文：一次校验内各步骤累积状态的唯一载体。
 * 单次下载流语义：sourceStream 由编排器打开、仅大小统计步骤消费；后续步骤经 spill 重开流探测。
 *
 * @author cxxl
 */
@Data
@RequiredArgsConstructor
public class FileCheckContext {

    private final String fileId;

    /** 文件档案元数据（大小双源比对来源） */
    private final FileMetadata metadata;

    private final FileValidationProperties properties;

    /** 校验期缓冲（流式 sha256 + 溢写临时文件；后续步骤经此重开流） */
    private final SpillBuffer spill;

    /** 源文件下载流（仅大小统计步骤消费） */
    private InputStream sourceStream;

    /** 流式统计字节数（大小统计步骤写入） */
    private Long size;

    /** Tika 识别媒体类型（魔数识别步骤写入） */
    private String mediaType;

    /** 真实格式（魔数识别步骤写入） */
    private FileFormat format;
}
