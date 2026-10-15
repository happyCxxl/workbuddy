package com.workbuddy.worker.parser.capability;

import com.workbuddy.common.domain.model.ImageRegion;
import com.workbuddy.common.domain.model.OutputSpec;
import com.workbuddy.common.domain.model.VisionRequest;
import com.workbuddy.common.domain.parse.BBox;

/**
 * 能力调用的视觉入参组装：把解析侧的区域信息装成平台侧的 {@link VisionRequest}。
 *
 * <p>图像引用沿用区域自带的取值（整页 OCR 与失败表两块区域带的是页内引用串）；图像字节在解析侧没有来源，
 * 字节与媒体类型两个字段都留空，接入图像通路时由栅格化产物决定媒体类型；bbox 按左上角原点换算成四至坐标。
 *
 * @author cxxl
 */
public final class CapabilityRequests {

    private CapabilityRequests() {
    }

    /**
     * 组装视觉入参。
     *
     * @param imageRef 图像引用（可空）
     * @param pageNo   页码（可空）
     * @param bbox     区域包围盒（可空 = 整图）
     * @param prompt   提示词
     * @param output   输出约束
     * @return 视觉入参（模型名由按能力选型的那条入口回填；图像字节与媒体类型留空）
     */
    public static VisionRequest of(String imageRef, Integer pageNo, BBox bbox, String prompt, OutputSpec output) {
        VisionRequest request = new VisionRequest();
        request.setImageRef(imageRef);
        request.setRegion(toImageRegion(pageNo, bbox));
        request.setPrompt(prompt);
        request.setOutput(output);
        return request;
    }

    /**
     * 区域换算：包围盒缺失即留空（整图）。
     *
     * @param pageNo 页码（可空）
     * @param bbox   区域包围盒（可空）
     * @return 四至坐标区域；包围盒缺失返回 null
     */
    private static ImageRegion toImageRegion(Integer pageNo, BBox bbox) {
        if (bbox == null) {
            return null;
        }
        ImageRegion region = new ImageRegion();
        region.setPageNo(pageNo);
        region.setX0(bbox.getX());
        region.setY0(bbox.getY());
        region.setX1(bbox.getX() + bbox.getWidth());
        region.setY1(bbox.getY() + bbox.getHeight());
        return region;
    }
}
