package com.workbuddy.common.domain.input;

import com.workbuddy.common.domain.entity.WbSourceFile;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件引用：文件 ID 的领域内包装。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileReference {

    /**
     * 文件 ID
     */
    private String fileId;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件内容指纹（sha256）
     */
    private String sha256;

    /**
     * 真实 MIME 类型
     */
    private String mimeType;


    /**
     * 由来源文件档案构建文件引用（只取文件元信息，不含解析产物）。
     *
     * @param sourceFile 来源文件档案
     * @return 文件引用
     */
    public static FileReference from(WbSourceFile sourceFile) {
        FileReference fileRef = new FileReference();
        fileRef.setFileId(sourceFile.getFileId());
        fileRef.setFileName(sourceFile.getFileName());
        fileRef.setSha256(sourceFile.getSha256());
        fileRef.setMimeType(sourceFile.getMimeType());
        return fileRef;
    }
}
