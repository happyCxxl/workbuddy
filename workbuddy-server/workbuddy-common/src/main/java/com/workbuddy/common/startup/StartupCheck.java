package com.workbuddy.common.startup;

/**
 * 启动期校验项：一位校验一个实现，由启动校验链按 {@link #order()} 顺序串起。
 *
 * <p>实现只做检查：不通过即抛异常（异常即终止启动），通过则调用 {@code next.proceed()} 把控制权交给下一项。
 * 校验项之间互不感知，也不在这里做初始化、建连、数据播种等有副作用的启动动作 ——
 * 那类动作要产出后续依赖的对象，不属于"检查"。
 *
 * <p><b>不调用 {@code next.proceed()} 即短路</b>：链上后续校验项不再执行。这是链的语义而非省事的写法，
 * 一处漏调会静默跳过后面所有校验，故除有意短路外，每条通过路径都必须调用它。
 *
 * <p>{@link #order()} 的档位取本接口的三个常量，留 10 的间隔：值小的先执行。
 */
public interface StartupCheck {

    /** 顺序档位：本地纯参数校验，不碰外部资源 */
    int ORDER_PARAMETERS = 100;

    /** 顺序档位：依赖外部凭据（密钥、连接参数）的配置校验 */
    int ORDER_CREDENTIALS = 200;

    /** 顺序档位：依赖容器把相关组件装配齐全后才能成立的一致性校验 */
    int ORDER_ASSEMBLY = 300;

    /** 执行顺序，值小的先执行 */
    int order();

    /** 校验项名称（日志与失败定位用），缺省取实现类简名 */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * 执行本项校验，再调用 {@code next.proceed()} 交给下一项。
     *
     * @param next 链上的下一项；已是最后一项时 {@code proceed()} 直接返回
     */
    void check(StartupCheckChain next);
}
