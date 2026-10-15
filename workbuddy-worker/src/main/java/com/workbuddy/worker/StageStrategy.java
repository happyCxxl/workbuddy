package com.workbuddy.worker;

/**
 * 环节策略的公共元信息契约：类型 / 名称 / 版本三项的写入。
 *
 * <p>只含元信息三项，不含各环节自身的策略字段。
 *
 * @author cxxl
 */
public interface StageStrategy {

    void setType(String type);

    void setName(String name);

    void setVersion(String version);
}
