package com.workbuddy.filecenter.service;

import com.workbuddy.common.domain.input.FileValidationResult;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 文件服务：上传（先校验后落库）、提交校验与下载。
 */
public interface FileService {

    /**
     * 上传文件：校验（扩展名白名单 → 大小 → 空文件 → 魔数）不通过抛
     * {@link com.workbuddy.common.exception.WorkbuddyException}，通过后写入对象存储。
     *
     * @param file 上传文件
     * @return 新建的 fileId
     */
    String upload(MultipartFile file);

    /**
     * 校验已落库文件：单次取流内依次完成大小统计与 sha256、空文件判定、大小双源比对、
     * 魔数识别与白名单、损坏/加密试解析探测。失败返回携带 failReason（不抛异常，由调用方记提交日志）。
     *
     * @param fileId 文件 ID
     * @return 校验结果（pass 带 format/mimeType/size/sha256；fail 带 failReason）
     */
    FileValidationResult validate(String fileId);

    /** 按 fileId 下载：读取元数据与文件流，写入响应 */
    void download(String fileId, HttpServletResponse response) throws IOException;
}
