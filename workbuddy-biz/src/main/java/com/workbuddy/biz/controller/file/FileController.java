package com.workbuddy.biz.controller.file;

import com.workbuddy.common.web.R;
import com.workbuddy.filecenter.service.FileService;
import com.workbuddy.filecenter.storage.FileStorage;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 文件接口：上传与下载（薄转发，业务逻辑在 service 层）。
 */
@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorage fileStorage;
    private final FileService fileService;

    /**
     * 上传文件。
     *
     * @param file 上传的文件
     * @return 新建的 fileId
     */
    @PostMapping
    public R<String> upload(@RequestParam("file") MultipartFile file) {
        return R.ok(fileStorage.putFile(file));
    }

    /**
     * 按 fileId 下载文件：以附件形式写进响应流。
     *
     * @param fileId   文件 ID
     * @param response 响应
     * @throws IOException 读对象或写响应失败
     */
    @GetMapping("/{fileId}")
    public void download(@PathVariable String fileId, HttpServletResponse response) throws IOException {
        fileService.download(fileId, response);
    }
}
