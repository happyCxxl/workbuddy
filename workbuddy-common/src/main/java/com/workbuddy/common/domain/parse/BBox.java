package com.workbuddy.common.domain.parse;

import com.workbuddy.common.utils.NullUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 边界框（pt，左上角原点）。PDF 元素带完整坐标；Office 元素可空（以结构路径溯源）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BBox {

    /** 左 x */
    private double x;

    /** 上 y */
    private double y;

    /** 宽 */
    private double width;

    /** 高 */
    private double height;

    /**
     * 是否与另一框位置重叠。
     *
     * @param other     另一框
     * @param tolerance 容差（坐标单位）
     * @return 重叠为 true；另一框为空时为 false
     */
    public boolean overlaps(BBox other, double tolerance) {
        if (NullUtil.isNull(other)) {
            return false;
        }
        boolean xOverlap = x < other.x + other.width + tolerance && other.x < x + width + tolerance;
        boolean yOverlap = y < other.y + other.height + tolerance && other.y < y + height + tolerance;
        return xOverlap && yOverlap;
    }
}
