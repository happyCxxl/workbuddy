package com.workbuddy.filecenter.service;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 文件下载服务。
 *
 * @author cxxl
 */
public interface FileService {

    /** 按 fileId 下载：读取元数据与文件流，写入响应 */
    void download(String fileId, HttpServletResponse response) throws IOException;
}
