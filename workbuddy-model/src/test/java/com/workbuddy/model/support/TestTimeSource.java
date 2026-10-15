package com.workbuddy.model.support;

import com.workbuddy.model.route.TimeSource;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 可推进的时间源（测试用）：从给定起点起算，用例按需推进毫秒数。
 *
 * @author cxxl
 */
public class TestTimeSource implements TimeSource {

    /** 当前时刻（毫秒） */
    private final AtomicLong current;

    public TestTimeSource() {
        this(0L);
    }

    /**
     * 指定起点的计时器。
     *
     * @param startMillis 起点时刻（毫秒）
     */
    public TestTimeSource(long startMillis) {
        this.current = new AtomicLong(startMillis);
    }

    @Override
    public long now() {
        return current.get();
    }

    /**
     * 推进时间。
     *
     * @param millis 推进的毫秒数
     * @return 推进后的时刻
     */
    public long advance(long millis) {
        return current.addAndGet(millis);
    }
}
