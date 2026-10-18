package com.workbuddy.biz.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.common.dto.request.model.ModelQueryDTO;
import com.workbuddy.common.dto.request.model.ModelSaveDTO;
import com.workbuddy.common.dto.request.model.ModelStatusDTO;
import com.workbuddy.common.dto.request.model.ModelUpdateDTO;
import com.workbuddy.common.dto.response.model.ModelItemVO;

/**
 * 模型管理服务：模型目录的清单分页、新增、编辑与启停。
 *
 * <p>厂商连接与密钥都在配置（`workbuddy.model.vendors`），本服务只按该配置校验厂商码是否存在；
 * 写入只落 `wb_model` 的业务列。格式校验在 DTO 注解，本服务只做要查库查配置的判定。
 *
 * @author cxxl
 */
public interface ModelService {

    /**
     * 模型清单分页：按能力 / 状态 / 模型名过滤，页码与页长口径见 {@link ModelQueryDTO} 父类。
     *
     * @param query 查询条件（各项都可留空）
     * @return 分页结果（厂商码升序、模型名升序）
     */
    IPage<ModelItemVO> page(ModelQueryDTO query);

    /**
     * 新增模型：校验厂商码在配置里、唯一键不冲突、能力逐项按字典解析。
     *
     * @param dto 模型收参
     * @return 落库后的模型
     */
    ModelItemVO create(ModelSaveDTO dto);

    /**
     * 编辑模型：校验口径与新增一致，唯一键判定排除本行；状态沿用原值。
     *
     * @param id  主键
     * @param dto 模型编辑收参
     * @return 覆盖写之后的模型
     */
    ModelItemVO update(Long id, ModelUpdateDTO dto);

    /**
     * 启停模型：只改状态列。
     *
     * @param id  主键
     * @param dto 目标状态
     * @return 回写后的模型
     */
    ModelItemVO updateStatus(Long id, ModelStatusDTO dto);
}
