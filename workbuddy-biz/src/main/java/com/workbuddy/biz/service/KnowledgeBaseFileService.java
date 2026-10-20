package com.workbuddy.biz.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.common.dto.request.input.FileSubmitDTO;
import com.workbuddy.common.dto.request.knowledge.FileResultQueryDTO;
import com.workbuddy.common.dto.request.knowledge.SubmitLogQueryDTO;
import com.workbuddy.common.dto.response.input.FileResultVO;
import com.workbuddy.common.dto.response.input.FileSubmitVO;
import com.workbuddy.common.dto.response.input.SubmitLogVO;

/**
 * 知识库文档输入用例：提交建档、提交记录分页、文件结果分页。
 *
 * <p>提交以 requestId 幂等；文件校验与元数据读取在事务外，建档三写用短事务；
 * 文件校验不通过只记 FAIL 提交日志并正常返回。
 */
public interface KnowledgeBaseFileService {

    /**
     * 提交文件：幂等检查 → 归属校验 → 文件校验 → 建档三写
     * （wb_source_file / wb_file_result / wb_submit_log，同一短事务）。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param request         提交请求（fileId + requestId）
     * @return 提交响应：新建提交 pipelineTaskId 为 null；
     *         幂等回放时回填该文件结果已有的解析任务 ID（可能为 null）
     */
    FileSubmitVO submit(Long knowledgeBaseId, FileSubmitDTO request);

    /**
     * 提交记录分页查询：数据源 wb_submit_log（PASS/FAIL 均记录），支持按提交结果与文件名模糊筛选。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param query           查询条件（状态/文件名都可留空，页码与页长口径见父类）
     * @return 提交记录分页 VO（含失败原因）
     */
    IPage<SubmitLogVO> pageSubmitLogs(Long knowledgeBaseId, SubmitLogQueryDTO query);

    /**
     * 文件结果分页查询（执行链工作台列表数据源）：每行附带各环节最新任务状态。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param query           查询条件（环节可留空，页码与页长口径见父类；
     *                        环节合法值 PARSE/STRUCTURE/PREPROCESS/CHUNK/EMBED，非法值 40001）
     * @return 文件结果分页 VO（含来源文件信息与各环节状态列表）
     */
    IPage<FileResultVO> pageFileResults(Long knowledgeBaseId, FileResultQueryDTO query);
}
