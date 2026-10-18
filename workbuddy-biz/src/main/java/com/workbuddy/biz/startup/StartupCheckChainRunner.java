package com.workbuddy.biz.startup;

import com.workbuddy.common.startup.StartupCheck;
import com.workbuddy.common.startup.StartupCheckChain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 启动校验链的驱动方：把容器里的全部 {@link StartupCheck} 按 {@link StartupCheck#order()} 排序后交给第一项，
 * 之后各校验项自行调用 {@code next.proceed()} 推进；首个不通过即终止启动。
 *
 * <p>新增一项启动校验只需新增一个 {@link StartupCheck} 实现并标 {@code @Component}，本类不改。
 * 校验项的先后由 {@code order()} 决定，不再依赖 Bean 注册顺序。
 *
 * <p><b>不在本链内的启动期校验</b>（各有落点，清单在此保持完整）：
 * <ul>
 *   <li>导入文件的存废与绑定：Spring 配置加载期，见 {@code application.yml} 的 {@code spring.config.import}
 *       清单与 {@code ConfigImportManifestTest}；</li>
 *   <li>环节超时的环节名（{@code knowledge.task.timeout.<环节>}）：键是 {@code PipelineStage} 枚举，
 *       写错环节名在绑定期被 Spring 直接拒绝；</li>
 *   <li>向量化开关的取值（{@code knowledge.embed} 的三个开关）：字段是 {@code EmbedSwitch} 枚举，
 *       写错取值同样在绑定期被拒；</li>
 *   <li>存储数据源 seed、注册与当前启用行的连接探活：{@code StorageSourceServiceImpl#initialize}，{@code @PostConstruct}，
 *       在 refresh 期执行，早于本链；</li>
 *   <li>Milvus 目标库探测/创建与客户端连接：{@code MilvusClientFactory#milvusClientV2}，{@code @Bean}，
 *       属基础设施创建；其中探测/建库失败只告警，不阻断启动。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupCheckChainRunner implements ApplicationRunner {

    private final List<StartupCheck> checks;

    @Override
    public void run(ApplicationArguments args) {
        List<StartupCheck> ordered = checks.stream()
                .sorted(Comparator.comparingInt(StartupCheck::order))
                .toList();
        log.info("===> StartupCheckChainRunner run 启动校验开始, count={}", ordered.size());
        if (ordered.isEmpty()) {
            return;
        }
        Chain chain = new Chain(ordered);
        long startedAt = System.currentTimeMillis();
        try {
            chain.proceed();
        } catch (RuntimeException e) {
            log.error("===> StartupCheckChainRunner run 启动校验失败 [{}/{}] {}, 耗时={}ms",
                    chain.position(), ordered.size(), chain.current().name(), System.currentTimeMillis() - startedAt);
            throw e;
        }
        log.info("===> StartupCheckChainRunner run 启动校验全部通过, count={}, 耗时={}ms",
                ordered.size(), System.currentTimeMillis() - startedAt);
    }

    /**
     * 链的推进器：持有排序后的校验项与游标，{@code proceed()} 取下一项并把自身交给它。
     *
     * <p>每项开始前打编排行；失败项的定位取 {@link #current()}（最后一次开始执行的那一项）。
     */
    private static final class Chain implements StartupCheckChain {

        private final List<StartupCheck> ordered;

        private int index;

        private StartupCheck current;

        private Chain(List<StartupCheck> ordered) {
            this.ordered = ordered;
        }

        @Override
        public void proceed() {
            if (index >= ordered.size()) {
                return;
            }
            current = ordered.get(index);
            index++;
            log.info("===> StartupCheckChainRunner run 启动校验 [{}/{}] {}",
                    index, ordered.size(), current.name());
            current.check(this);
        }

        /** 最后一次开始执行的校验项 */
        private StartupCheck current() {
            return current;
        }

        /** 最后一次开始执行的校验项的序号（1 起） */
        private int position() {
            return index;
        }
    }
}
