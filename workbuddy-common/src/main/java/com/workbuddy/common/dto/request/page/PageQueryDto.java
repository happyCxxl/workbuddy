package com.workbuddy.common.dto.request.page;

import com.workbuddy.common.utils.NullUtil;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分页查询条件（列表类接口共用）。
 *
 * <p>页码与页长都可空，取值小于 1 或未传时按默认值兜底。
 *
 * @author cxxl
 */
@Data
public class PageQueryDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 默认页码 */
    private static final long DEFAULT_CURRENT = 1L;

    /** 默认每页条数 */
    private static final long DEFAULT_SIZE = 10L;

    /** 当前页（可空，按默认 1） */
    private Long current;

    /** 每页条数（可空，按默认 10） */
    private Long size;

    /** 页码兜底：未传或小于 1 时取默认值 */
    public long currentOrDefault() {
        return NullUtil.isNull(current) || current < 1 ? DEFAULT_CURRENT : current;
    }

    /** 每页条数兜底：未传或小于 1 时取默认值 */
    public long sizeOrDefault() {
        return NullUtil.isNull(size) || size < 1 ? DEFAULT_SIZE : size;
    }
}
