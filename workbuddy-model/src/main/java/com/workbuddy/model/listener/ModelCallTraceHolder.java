package com.workbuddy.model.listener;

import com.workbuddy.common.utils.NullUtil;
import org.springframework.stereotype.Component;

/**
 * 调用上下文持有：把一次调用的 {@link ModelCallTrace} 交给适配器，适配器把它放进请求选项的属性表传给监听器。
 * 绑定与解绑都发生在调用线程内，未绑定时取到 null。
 *
 * @author cxxl
 */
@Component
public class ModelCallTraceHolder {

    private final ThreadLocal<ModelCallTrace> holder = new ThreadLocal<>();

    /**
     * 绑定本次调用的上下文。
     *
     * @param trace 调用上下文；入参为空即解绑
     */
    public void bind(ModelCallTrace trace) {
        if (NullUtil.isNull(trace)) {
            holder.remove();
            return;
        }
        holder.set(trace);
    }

    /** 当前线程绑定的上下文，未绑定为 null */
    public ModelCallTrace current() {
        return holder.get();
    }

    /** 解绑当前线程的上下文 */
    public void clear() {
        holder.remove();
    }
}
