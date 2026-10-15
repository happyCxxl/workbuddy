package com.workbuddy.common.domain.input;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件引用：文件 ID 的领域内包装。
 *
 * @author cxxl
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileReference {

    /** 文件 ID */
    private String fileId;

    /** 文件名 */
    private String fileName;

    /** 文件内容指纹（sha256） */
    private String sha256;

    /** 真实 MIME 类型 */
    private String mimeType;

    /** 页数（PDF 解析器回填；Excel 无页码概念，恒 0） */
    private Integer pageCount;
}
