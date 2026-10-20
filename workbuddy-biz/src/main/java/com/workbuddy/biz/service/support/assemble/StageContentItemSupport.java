package com.workbuddy.biz.service.support.assemble;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.dto.response.stagecontent.BBoxVO;
import com.workbuddy.common.dto.response.stagecontent.StageContentExtraVO;
import com.workbuddy.common.dto.response.stagecontent.StageContentItemVO;
import com.workbuddy.common.utils.NullUtil;

/**
 * 环节内容条目公共组装：条目骨架、页网格与边界框字段，解析/组装/切片/向量化共用。
 *
 * <p>只做字段搬运，不判断环节语义；各环节专属字段留在各自的 StageContentSupport。
 */
public final class StageContentItemSupport {

    private StageContentItemSupport() {
    }

    /**
     * 条目骨架：对齐键取顺序号，类型/状态/展示文本与 extra 原样落位。
     *
     * @param seq     顺序号（从 1 起）
     * @param type    元素类型
     * @param status  处置状态，可空
     * @param display 展示文本
     * @param extra   环节专属字段
     * @return 内容条目
     */
    public static StageContentItemVO itemOf(int seq, String type, String status, String display,
                                            StageContentExtraVO extra) {
        StageContentItemVO item = new StageContentItemVO();
        item.setAlignKey(String.valueOf(seq));
        item.setSeq(seq);
        item.setType(type);
        item.setStatus(status);
        item.setDisplay(display);
        item.setExtra(extra);
        return item;
    }

    /**
     * 页/行/列公共字段按非空并入 extra（解析/组装共用口径）。
     *
     * @param extra 环节专属字段
     * @param page  文档页码，可空
     * @param rows  行数，可空
     * @param cols  列数，可空
     */
    public static void putPageGrid(StageContentExtraVO extra, Integer page, Integer rows, Integer cols) {
        if (NullUtil.isNotNull(page)) {
            extra.setPage(page);
        }
        if (NullUtil.isNotNull(rows)) {
            extra.setRows(rows);
        }
        if (NullUtil.isNotNull(cols)) {
            extra.setCols(cols);
        }
    }

    /**
     * 边界框并入 extra：原文预览按它在页面上画高亮框。
     *
     * <p>单位点（pt）、左上角原点，与解析产物里的 bbox 同口径；元素无坐标时不下发该字段。
     *
     * @param extra 环节专属字段
     * @param bbox  边界框，可空
     */
    public static void putBbox(StageContentExtraVO extra, BBox bbox) {
        if (NullUtil.isNull(bbox)) {
            return;
        }
        BBoxVO box = new BBoxVO();
        box.setX(bbox.getX());
        box.setY(bbox.getY());
        box.setWidth(bbox.getWidth());
        box.setHeight(bbox.getHeight());
        extra.setBbox(box);
    }
}
