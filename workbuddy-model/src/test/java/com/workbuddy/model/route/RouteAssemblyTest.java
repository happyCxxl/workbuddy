package com.workbuddy.model.route;

import com.workbuddy.model.adapter.ModelAdapter;
import com.workbuddy.model.calllog.ModelCallLogPort;
import com.workbuddy.model.calllog.ModelCallLogRecorder;
import com.workbuddy.model.catalog.ModelCatalogPort;
import com.workbuddy.model.config.ModelRouteProperties;
import com.workbuddy.model.config.ModelVendorProperties;
import com.workbuddy.model.invoke.ModelErrorClassifier;
import com.workbuddy.model.invoke.ModelInvoker;
import com.workbuddy.model.listener.ModelCallTraceHolder;
import com.workbuddy.model.support.InMemoryModelCallLogPort;
import com.workbuddy.model.support.RecordingAdapter;
import com.workbuddy.model.support.TestRouteCatalog;
import com.workbuddy.model.validate.OutputSchemaValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由相关组件的装配用例：按生产装配顺序把 {@link RouteStateStore}、{@link ModelCircuitBreakers}、
 * {@link ModelRouter} 与 {@link ModelInvoker} 放进容器，断言它们能从上下文取到且依赖齐备。
 *
 * <p>做法：{@link ApplicationContextRunner} 起一个小上下文，路由参数与厂商配置用真实实现（配置有默认值），
 * 目录与调用明细端口用测试替身注入。构造器注入写错、某个协作者缺失或参数类型不匹配，都会在这里失败，
 * 不必等到运行期。
 *
 * @author cxxl
 */
class RouteAssemblyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RouteConfiguration.class));

    @Test
    void routeComponentsShouldBeWiredFromContext() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RouteStateStore.class);
            assertThat(context).hasSingleBean(ModelCircuitBreakers.class);
            assertThat(context).hasSingleBean(ModelRouter.class);
            assertThat(context).hasSingleBean(ModelInvoker.class);
        });
    }

    @Test
    void routerShouldBeUsableWithItsWiredCollaborators() {
        runner.run(context -> {
            ModelRouter router = context.getBean(ModelRouter.class);
            RouteStateStore stateStore = context.getBean(RouteStateStore.class);

            assertThat(stateStore.snapshot("dashscope", "qwen-max").rpmCount()).isZero();
            assertThat(router).isNotNull();
            assertThat(context.getBean(ModelInvoker.class).canFallback(new IllegalStateException("boom"))).isFalse();
        });
    }

    @Test
    void missingCatalogShouldFailFast() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RouteWithoutCatalogConfiguration.class))
                .run(context -> assertThat(context).hasFailed());
    }

    /** 路由侧的最小装配：参数、厂商配置与错误分类用真实实现，目录与明细端口用测试替身 */
    @Configuration
    static class RouteConfiguration {

        @Bean
        ModelRouteProperties routeProperties() {
            return new ModelRouteProperties();
        }

        @Bean
        ModelVendorProperties vendorProperties() {
            return new ModelVendorProperties();
        }

        @Bean
        ModelErrorClassifier modelErrorClassifier() {
            return new ModelErrorClassifier();
        }

        @Bean
        ModelCatalogPort modelCatalogPort() {
            return new TestRouteCatalog();
        }

        @Bean
        ModelCallLogPort modelCallLogPort() {
            return new InMemoryModelCallLogPort();
        }

        @Bean
        RouteStateStore routeStateStore(ModelRouteProperties routeProperties) {
            return new RouteStateStore(routeProperties);
        }

        @Bean
        ModelCircuitBreakers modelCircuitBreakers(ModelRouteProperties routeProperties,
                ModelErrorClassifier classifier) {
            return new ModelCircuitBreakers(routeProperties, classifier);
        }

        @Bean
        ModelRouter modelRouter(ModelRouteProperties routeProperties, ModelVendorProperties vendorProperties,
                ModelCatalogPort catalog, RouteStateStore stateStore, ModelCircuitBreakers breakers) {
            return new ModelRouter(routeProperties, vendorProperties, catalog, stateStore, breakers);
        }

        @Bean
        ModelCallLogRecorder modelCallLogRecorder(ModelCallLogPort logPort, ModelCatalogPort catalog) {
            return new ModelCallLogRecorder(logPort, catalog);
        }

        @Bean
        ModelCallTraceHolder modelCallTraceHolder() {
            return new ModelCallTraceHolder();
        }

        @Bean
        OutputSchemaValidator outputSchemaValidator() {
            return new OutputSchemaValidator();
        }

        @Bean
        ModelAdapter modelAdapter() {
            return new RecordingAdapter();
        }

        @Bean
        ModelInvoker modelInvoker(ModelAdapter adapter, OutputSchemaValidator validator,
                ModelErrorClassifier classifier, ModelCallTraceHolder traceHolder, ModelCallLogRecorder recorder,
                ModelRouter router, RouteStateStore stateStore, ModelCircuitBreakers breakers) {
            return new ModelInvoker(adapter, validator, classifier, traceHolder, recorder, router, stateStore,
                    breakers);
        }
    }

    /** 缺目录的装配：容器启动即失败，说明依赖缺失不会被静默吞掉 */
    @Configuration
    static class RouteWithoutCatalogConfiguration {

        @Bean
        ModelRouteProperties routeProperties() {
            return new ModelRouteProperties();
        }

        @Bean
        ModelVendorProperties vendorProperties() {
            return new ModelVendorProperties();
        }

        @Bean
        ModelErrorClassifier modelErrorClassifier() {
            return new ModelErrorClassifier();
        }

        @Bean
        RouteStateStore routeStateStore(ModelRouteProperties routeProperties) {
            return new RouteStateStore(routeProperties);
        }

        @Bean
        ModelCircuitBreakers modelCircuitBreakers(ModelRouteProperties routeProperties,
                ModelErrorClassifier classifier) {
            return new ModelCircuitBreakers(routeProperties, classifier);
        }

        @Bean
        ModelRouter modelRouter(ModelRouteProperties routeProperties, ModelVendorProperties vendorProperties,
                ModelCatalogPort catalog, RouteStateStore stateStore, ModelCircuitBreakers breakers) {
            return new ModelRouter(routeProperties, vendorProperties, catalog, stateStore, breakers);
        }
    }
}
