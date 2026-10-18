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
 *
 * @author cxxl
 */
@Service
public class FileServiceImpl implements FileService {

    private final FileStorage fileStorage;

    public FileServiceImpl(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

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
