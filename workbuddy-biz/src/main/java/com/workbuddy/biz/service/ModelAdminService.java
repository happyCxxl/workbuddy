package com.workbuddy.biz.service;

import com.workbuddy.common.dto.request.model.ModelQueryDto;
import com.workbuddy.common.dto.request.model.ModelSaveDto;
import com.workbuddy.common.dto.request.model.ModelStatusDto;
import com.workbuddy.common.dto.response.model.ModelItemVO;

import java.util.List;

/**
 * 模型管理服务：模型目录的清单、新增、编辑与启停。
 *
 * <p>厂商连接与密钥都在配置（`knowledge.model.vendors`），本服务只按该配置校验厂商码是否存在；
 * 写入只落 `kb_model` 的业务列，配置与库里的密钥都不经过本服务。校验在服务层一处完成，越线抛知识异常。
 *
 * @author cxxl
 */
public interface ModelAdminService {

    /**
     * 模型清单：按能力 / 状态 / 关键字过滤，返回目录视图字段。
     *
     * @param query 过滤条件（三项都可留空）
     * @return 命中的模型（厂商码升序、模型名升序）
     */
    List<ModelItemVO> list(ModelQueryDto query);

    /**
     * 新增模型：校验厂商码在配置里、唯一键不冲突、价格与限额非负、能力与状态取值在字典内。
     *
     * @param dto 模型收参
     * @return 落库后的模型
     */
    ModelItemVO create(ModelSaveDto dto);

    /**
     * 编辑模型：校验口径与新增一致，唯一键判定排除本行。
     *
     * @param id  主键
     * @param dto 模型收参
     * @return 覆盖写之后的模型
     */
    ModelItemVO update(Long id, ModelSaveDto dto);

    /**
     * 启停模型：只改状态列。
     *
     * @param id  主键
     * @param dto 目标状态
     * @return 回写后的模型
     */
    ModelItemVO updateStatus(Long id, ModelStatusDto dto);
}
