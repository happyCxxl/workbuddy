package com.workbuddy.filecenter.service.impl;

import com.workbuddy.common.domain.input.FileMetadata;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.service.FileService;
import com.workbuddy.filecenter.storage.FileStorage;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文件下载服务实现：读取元数据与文件流并写入响应。
 */
@Service
public class FileServiceImpl implements FileService {

    private final FileStorage fileStorage;

    /**
     * 构造文件服务。
     *
     * @param fileStorage 文件存储端口
     */
    public FileServiceImpl(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * 下载文件：以附件形式写进响应流，文件名按 UTF-8 编码。
     *
     * @param fileId   文件 ID
     * @param response 响应
     * @throws IOException 读对象或写响应失败
     */
    @Override
    public void download(String fileId, HttpServletResponse response) throws IOException {
        FileMetadata metadata = fileStorage.metadata(fileId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(metadata.getFileName(), StandardCharsets.UTF_8)
                .build();
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        if (NullUtil.isNotNull(metadata.getFileSize())) {
            response.setContentLengthLong(metadata.getFileSize());
        }
        try (InputStream in = fileStorage.open(fileId)) {
            in.transferTo(response.getOutputStream());
        }
    }
}
