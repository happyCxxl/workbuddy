package com.workbuddy.model.route;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机源：路由的随机放行与平局抽样都经本接口取随机数。
 * 默认实现取当前线程的随机数发生器（多线程下无共享状态），用例可注入取值可预测的实现。
 *
 * @author cxxl
 */
@FunctionalInterface
public interface RandomSource {

    /** 默认随机源：取当前线程的随机数发生器 */
    RandomSource DEFAULT = bound -> ThreadLocalRandom.current().nextInt(bound);

    /**
     * 取一个 [0, bound) 区间的随机整数。
     *
     * @param bound 上界（不含），必须为正数
     * @return 随机整数
     */
    int nextInt(int bound);
}
