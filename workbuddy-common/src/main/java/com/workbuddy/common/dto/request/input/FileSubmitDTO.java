package com.workbuddy.common.dto.request.input;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 提交文件请求：body 只有 fileId + requestId（knowledgeBaseId 从路径取，提交人从登录态取）。
 * requestId 为空时的拒绝语义（REQUEST_ID_MISSING）在 service 校验，不走参数校验。
 */
@Data
public class FileSubmitDTO {

    /** 文件 ID */
    @NotBlank(message = "fileId 不能为空")
    private String fileId;

    /** 幂等键（提交端生成，通常 UUID；网络重试沿用同一值） */
    private String requestId;
}
