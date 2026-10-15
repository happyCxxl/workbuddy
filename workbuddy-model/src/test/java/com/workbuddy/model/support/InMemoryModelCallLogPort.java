package com.workbuddy.model.support;

import com.workbuddy.common.domain.model.calllog.ModelCallLogEntry;
import com.workbuddy.common.domain.model.calllog.ModelCallLogQuery;
import com.workbuddy.common.domain.model.calllog.ModelCallLogSummary;
import com.workbuddy.common.domain.model.calllog.ModelCallLogView;
import com.workbuddy.model.calllog.ModelCallLogPort;

import java.util.ArrayList;
import java.util.List;

/**
 * 调用明细端口的内存实现（测试用）：按写入顺序留存明细，查询与概览返回空结果。
 * 不连库，供调用链用例断言"写了几行、写成什么样"。
 *
 * @author cxxl
 */
public class InMemoryModelCallLogPort implements ModelCallLogPort {

    /** 写入的明细行（按写入顺序） */
    private final List<ModelCallLogEntry> entries = new ArrayList<>();

    /** 端口异常注入：非空时写入直接抛出该异常（验证落库失败不影响调用结果） */
    private RuntimeException failure;

    /**
     * 注入写入异常。
     *
     * @param failure 写入时抛出的异常
     * @return 本对象（链式设置）
     */
    public InMemoryModelCallLogPort withFailure(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    @Override
    public void record(ModelCallLogEntry entry) {
        if (failure != null) {
            throw failure;
        }
        entries.add(entry);
    }

    @Override
    public List<ModelCallLogView> listCalls(ModelCallLogQuery query) {
        return List.of();
    }

    @Override
    public ModelCallLogSummary summarize(ModelCallLogQuery query) {
        return new ModelCallLogSummary();
    }

    /** 写入的明细行 */
    public List<ModelCallLogEntry> entries() {
        return entries;
    }

    /** 最近一次写入的明细行；没有写入过返回 null */
    public ModelCallLogEntry lastEntry() {
        return entries.isEmpty() ? null : entries.getLast();
    }
}
