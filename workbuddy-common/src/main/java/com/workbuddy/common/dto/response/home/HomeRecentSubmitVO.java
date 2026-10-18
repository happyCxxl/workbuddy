package com.workbuddy.common.dto.response.home;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 首页「最近提交」一行（数据源：wb_submit_log，**只含当前登录用户的提交**，新→旧）。
 *
 * <p>**口径说明（重要）**：`status` 是**提交校验结果**（PASS / FAIL），
 * 即"文件能不能进处理链"；**不是处理链进度**。
 * 校验通过 ≠ 处理完成 —— 后者在 `wb_knowledge_pipeline_task`，要用得再 join 任务表。
 * 这里叫"最近提交"，不叫"处理进度"。
 */
@Data
public class HomeRecentSubmitVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 提交日志 ID */
    private Long id;

    /** 文件名（文件不存在等场景可能为空串） */
    private String fileName;

    /**
     * 文件类型（`FileFormat` 枚举名：PDF / DOCX / XLSX …）。
     *
     * <p>由 `wb_source_file.mime_type` 换算，**不按扩展名猜**；校验失败的行没有来源文件，
     * 取不到就下发 null，界面显示占位符。
     */
    private String fileType;

    /** 所属知识库 ID（字符串下发） */
    private Long knowledgeBaseId;

    /** 所属知识库名（按 ID 反查；已删库回落成「知识库 {ID}」） */
    private String knowledgeBaseName;

    /** 提交结果：PASS / FAIL */
    private String status;

    /**
     * 校验失败原因码值（`FileValidationFailReason` 枚举名；PASS 时为空）。
     *
     * <p>码值与中文名都给：前端要按码值做条件分支（比如只对某几类提示"重新上传"），
     * 中文名只用于展示。
     */
    private String failReason;

    /** 校验失败原因中文名 */
    private String failReasonLabel;

    /** 提交时间 */
    private LocalDateTime createTime;
}
