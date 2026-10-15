package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workbuddy.common.domain.base.BaseCreateInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 来源文件：文件服务的 fileId 引用与指纹。
 * 无逻辑删除与更新字段（append-only），继承 {@link BaseCreateInfo}。
 * 同一 fileId 唯一（uk_file_id）：同一文件再次提交复用本行、新建 wb_file_result。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wb_source_file")
public class WbSourceFile extends BaseCreateInfo {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 文件 ID */
    private String fileId;

    /** 文件内容指纹（sha256，输入身份） */
    private String sha256;

    /** 文件名 */
    private String fileName;

    /** MIME 类型（真实格式识别结果） */
    private String mimeType;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 文件档案快照（JSON 文本） */
    private String inputSnapshot;

    /** 上传用户 ID */
    private Long userId;
}
