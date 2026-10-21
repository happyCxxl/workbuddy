package com.workbuddy.common.convert;

import com.workbuddy.common.config.BaseCreateInfoConvertConfig;
import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.common.domain.input.FileMetadata;
import com.workbuddy.common.domain.input.FileValidationResult;
import com.workbuddy.common.dto.request.input.FileSubmitDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 文件域转换：提交请求 / 校验结果 / 元数据 → 来源文件实体（编译期生成实现，运行期无反射）。
 *
 * <p>只搬字段；主键与创建列继承 {@code serverFields} 原型，输入快照序列化在表达式里完成。
 */
@Mapper(config = BaseCreateInfoConvertConfig.class)
public interface FileConverter {

    /**
     * 来源文件行：文件标识取请求，指纹与类型取校验结果，名称取元数据，输入快照序列化存留。
     *
     * @param request          提交请求
     * @param validationResult 文件校验结果
     * @param metadata         文件元数据
     * @param userId           当前用户 ID（可空）
     * @return 来源文件行（未保存）
     */
    @Mapping(target = "fileId", source = "request.fileId")
    @Mapping(target = "sha256", source = "validationResult.sha256")
    @Mapping(target = "fileName", source = "metadata.fileName")
    @Mapping(target = "mimeType", source = "validationResult.mimeType")
    @Mapping(target = "fileSize", source = "validationResult.size")
    @Mapping(target = "inputSnapshot", expression = "java(com.workbuddy.common.utils.JsonUtil.toJsonStr(metadata))")
    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createBy", ignore = true)
    @Mapping(target = "createTime", ignore = true)
    WbSourceFile toSourceFile(FileSubmitDTO request, FileValidationResult validationResult,
                              FileMetadata metadata, Long userId);
}
