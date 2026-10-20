package com.workbuddy.biz.service.support;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.StageContentQueryService;
import com.workbuddy.common.dto.response.stagecontent.StageContentExtraVO;
import com.workbuddy.common.dto.response.stagecontent.StageContentItemVO;
import com.workbuddy.common.utils.NullUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 环节内容分页与过滤：页码/每页条数归一、截断判定、分页切片、文档页与处置状态过滤。
 *
 * <p>过滤一律先于分页：total 与 truncated 都按过滤后的口径给；这些口径由调用方统一使用，
 * 避免各环节各写一份边界判断。
 */
public final class StageContentPaginationSupport {

    private StageContentPaginationSupport() {
    }

    /**
     * 页码归一：非正数或空按第 1 页。
     *
     * @param current 请求页码，可空
     * @return 归一后的页码（从 1 起）
     */
    public static int resolveCurrent(Integer current) {
        return NullUtil.isNull(current) || current < 1 ? 1 : current;
    }

    /**
     * 每页条数归一：空/非正按默认上限，超过上限按上限截断。
     *
     * @param size 请求每页条数，可空
     * @return 归一后的每页条数
     */
    public static int resolveSize(Integer size) {
        if (NullUtil.isNull(size) || size < 1) {
            return StageContentQueryService.DEFAULT_SIZE;
        }
        return Math.min(size, StageContentQueryService.MAX_SIZE);
    }

    /**
     * 这一页之后是否还有内容。
     *
     * <p>**不能写成 `current × size < total`**：最后一页不满时（5 条按每页 2 条取第 3 页，
     * 只回 1 条）`3 × 2 = 6` 不小于 5，会把"已到末页"报成还有内容。按"这一页取到的位移
     * 是否到达总数"判断才等价于"后面还有没有"。
     *
     * @param current   当前页码
     * @param size      本页条数上限
     * @param pageCount 本页实际返回条数
     * @param total     总条数
     * @return 还有内容未返回则为 true（页码越界的空页后面没有内容，返回 false）
     */
    public static boolean hasMore(int current, int size, int pageCount, int total) {
        if (total <= 0) {
            return false;
        }
        return (current - 1) * size + pageCount < total;
    }

    /**
     * 取某一页：越界返回空列表（total 与 truncated 由调用处据实标明）。
     *
     * @param all     过滤后的全量条目
     * @param current 当前页码（从 1 起）
     * @param size    每页条数上限
     * @return 当前页条目
     */
    public static List<StageContentItemVO> slice(List<StageContentItemVO> all, int current, int size) {
        int from = (current - 1) * size;
        if (from >= all.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(all.subList(from, Math.min(from + size, all.size())));
    }

    /**
     * 元素的文档页码：解析/组装走 extra.page，其余环节无页概念返回 null。
     *
     * @param item 内容条目
     * @return 文档页码；无页概念或页码缺失时返回 null
     */
    public static Long docPageOf(StageContentItemVO item) {
        StageContentExtraVO extra = item.getExtra();
        if (NullUtil.isNull(extra) || NullUtil.isNull(extra.getPage())) {
            return null;
        }
        return extra.getPage().longValue();
    }

    /**
     * 按文档页过滤：只留该页元素（无页概念/非该页的一律排除）。
     *
     * <p>比较用 Long：入参来自 HTTP 是大整数，而元素页码在产物里是 Integer，
     * 直接比会因类型不同永远不相等。
     *
     * @param all     全量条目
     * @param docPage 文档页码（可空，空或非正表示不过滤）
     * @return 过滤后的条目
     */
    public static List<StageContentItemVO> filterByDocPage(List<StageContentItemVO> all, Long docPage) {
        if (NullUtil.isNull(docPage) || docPage < 1) {
            return all;
        }
        return all.stream()
                .filter(item -> ObjectUtil.equal(docPageOf(item), docPage))
                .toList();
    }

    /**
     * 按处置状态过滤：只留状态在给定集合里的元素。
     *
     * <p>状态以逗号分隔（如 `EXCLUDED_TOC,REPEATED`）；空串或全是空白表示不过滤。
     * 与文档页过滤同一原则：先过滤再分页，total 与 truncated 都按过滤后的口径给。
     *
     * @param all    全量条目
     * @param status 处置状态（逗号分隔，可空）
     * @return 过滤后的条目
     */
    public static List<StageContentItemVO> filterByStatus(List<StageContentItemVO> all, String status) {
        if (StrUtil.isBlank(status)) {
            return all;
        }
        Set<String> wanted = Arrays.stream(status.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
        if (wanted.isEmpty()) {
            return all;
        }
        return all.stream()
                .filter(item -> wanted.contains(item.getStatus()))
                .toList();
    }
}
