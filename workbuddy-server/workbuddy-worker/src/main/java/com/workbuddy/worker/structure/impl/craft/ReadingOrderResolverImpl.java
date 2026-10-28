package com.workbuddy.worker.structure.impl.craft;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.structure.AssembleContext;
import com.workbuddy.worker.structure.craft.ReadingOrderResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 阅读顺序实现（XY-cut）：
 * 有坐标元素按页分组、页内 XY-cut（横向切行带 → 带内纵向切块 → 块内递归，上→下、左→右）；
 * 无坐标元素（Office 结构路径）保持解析环节文档顺序直通；
 * HEADER/FOOTER 排除出正文流（仍在树内，仅不参与 NEXT 排序）。
 */
@Component
public class ReadingOrderResolverImpl implements ReadingOrderResolver {

    /**
     * 阅读顺序：把元素排成自上而下、分栏内先后的序列。
     *
     * @param elements 归一后的元素
     * @param context  结构化上下文
     * @return 有序元素
     */
    @Override
    public List<UnifiedElement> resolve(List<UnifiedElement> elements, AssembleContext context) {
        List<UnifiedElement> withBBox = new ArrayList<>();
        List<UnifiedElement> withoutBBox = new ArrayList<>();
        for (UnifiedElement element : elements) {
            if (NullUtil.isNotNull(element.getBbox()) && NullUtil.isNotNull(element.getPage())) {
                withBBox.add(element);
            } else {
                withoutBBox.add(element);
            }
        }

        // 有坐标元素：按页分组 → 页内 XY-cut → 页码升序串联
        List<UnifiedElement> ordered = new ArrayList<>();
        withBBox.stream()
                .collect(Collectors.groupingBy(UnifiedElement::getPage))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> ordered.addAll(xyCut(entry.getValue(), context)));

        // 无坐标元素：保持原顺序（解析环节已按文档顺序输出）
        ordered.addAll(withoutBBox);
        return ordered;
    }

    private List<UnifiedElement> xyCut(List<UnifiedElement> elements, AssembleContext context) {
        if (elements.size() <= 1) {
            return new ArrayList<>(elements);
        }
        double bandGapRatio = context.getProperties().getXyCutBandGapRatio();
        double blockGap = context.getProperties().getXyCutBlockGap();

        // ① 横向切行带：按 y 排序，y 间距超"行高 × 比率"即切带
        List<UnifiedElement> sorted = elements.stream()
                .sorted(Comparator.comparingDouble(e -> e.getBbox().getY()))
                .toList();
        List<List<UnifiedElement>> bands = new ArrayList<>();
        List<UnifiedElement> current = new ArrayList<>();
        double prevY = Double.NaN;
        double prevHeight = 0;
        for (UnifiedElement element : sorted) {
            double y = element.getBbox().getY();
            double height = lineHeight(element);
            if (!current.isEmpty() && y - (prevY + prevHeight) > bandGapRatio * Math.max(height, 1)) {
                bands.add(current);
                current = new ArrayList<>();
            }
            current.add(element);
            prevY = y;
            prevHeight = height;
        }
        if (!current.isEmpty()) {
            bands.add(current);
        }

        // ② 带内纵向切块 + 递归；③ 上→下、左→右串联
        List<UnifiedElement> result = new ArrayList<>();
        for (List<UnifiedElement> band : bands) {
            result.addAll(recursiveBlocks(band, blockGap));
        }
        return result;
    }

    private List<UnifiedElement> recursiveBlocks(List<UnifiedElement> band, double blockGap) {
        if (band.size() <= 1) {
            return new ArrayList<>(band);
        }
        List<UnifiedElement> sortedByX = band.stream()
                .sorted(Comparator.comparingDouble(e -> e.getBbox().getX()))
                .toList();
        // 纵向切块：x 间距超块间距即分块
        List<List<UnifiedElement>> blocks = new ArrayList<>();
        List<UnifiedElement> current = new ArrayList<>();
        double prevXEnd = Double.NaN;
        for (UnifiedElement element : sortedByX) {
            double x = element.getBbox().getX();
            if (!current.isEmpty() && x - prevXEnd > blockGap) {
                blocks.add(current);
                current = new ArrayList<>();
            }
            current.add(element);
            prevXEnd = x + element.getBbox().getWidth();
        }
        if (!current.isEmpty()) {
            blocks.add(current);
        }
        if (blocks.size() == 1) {
            // 单块：块内按 y 排序（递归退化为行内排序）
            return blocks.getFirst().stream()
                    .sorted(Comparator.comparingDouble(e -> e.getBbox().getY()))
                    .toList();
        }
        List<UnifiedElement> result = new ArrayList<>();
        for (List<UnifiedElement> block : blocks) {
            result.addAll(recursiveBlocks(block, blockGap));
        }
        return result;
    }

    private double lineHeight(UnifiedElement element) {
        BBox bbox = element.getBbox();
        double height = NullUtil.isNull(bbox) ? 0 : bbox.getHeight();
        double fontHeight = NullUtil.isNotNull(element.getFont())
                && NullUtil.isNotNull(element.getFont().getSize())
                ? element.getFont().getSize() * 1.2 : 0;
        return Math.max(height, fontHeight);
    }
}
