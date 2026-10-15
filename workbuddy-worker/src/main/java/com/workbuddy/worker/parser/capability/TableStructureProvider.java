package com.workbuddy.worker.parser.capability;

import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.domain.parse.capability.TableRegion;
import com.workbuddy.common.domain.parse.capability.TableResult;

/**
 * 表格结构识别能力接口：规则路径判不出结构的表交本接口补强。
 * 值对象见 common.domain.parse（TableResult/TableRegion/TableCell）。
 *
 * @author cxxl
 */
public interface TableStructureProvider {

    /**
     * 识别表格结构。
     *
     * @param region 表格区域
     * @param ctx    调用上下文
     * @return 表格结构结果
     */
    TableResult recognize(TableRegion region, ProviderContext ctx);
}
