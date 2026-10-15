package com.workbuddy.model.support;

import com.workbuddy.model.adapter.ModelAdapter;
import com.workbuddy.model.calllog.ModelCallLogPort;
import com.workbuddy.model.calllog.ModelCallLogRecorder;
import com.workbuddy.model.catalog.ModelCatalogPort;
import com.workbuddy.model.catalog.StaticModelCatalog;
import com.workbuddy.model.config.ModelRouteProperties;
import com.workbuddy.model.config.ModelVendorProperties;
import com.workbuddy.model.invoke.ModelErrorClassifier;
import com.workbuddy.model.invoke.ModelInvoker;
import com.workbuddy.model.listener.ModelCallTraceHolder;
import com.workbuddy.model.route.ModelCircuitBreakers;
import com.workbuddy.model.route.ModelRouter;
import com.workbuddy.model.route.RouteStateStore;
import com.workbuddy.model.validate.OutputSchemaValidator;

/**
 * 调用器装配（测试用）：按生产装配顺序把适配器、上下文持有器、调用明细端口与路由接成一次调用的完整外壳。
 * 目录默认取静态目录（不带价格），需要价格快照或按能力选型的用例改走 {@link RouteFixture}。
 *
 * @author cxxl
 */
public final class TestModelInvoker {

    private TestModelInvoker() {
    }

    /**
     * 不带定价的调用器：目录取静态目录，成本留空。
     *
     * @param adapter     适配器
     * @param traceHolder 调用上下文持有器
     * @param logPort     调用明细端口
     * @return 调用器
     */
    public static ModelInvoker of(ModelAdapter adapter, ModelCallTraceHolder traceHolder, ModelCallLogPort logPort) {
        return of(adapter, traceHolder, logPort, new StaticModelCatalog());
    }

    /**
     * 指定目录的调用器。
     *
     * @param adapter     适配器
     * @param traceHolder 调用上下文持有器
     * @param logPort     调用明细端口
     * @param catalog     模型目录（价格快照来源）
     * @return 调用器
     */
    public static ModelInvoker of(ModelAdapter adapter, ModelCallTraceHolder traceHolder, ModelCallLogPort logPort,
            ModelCatalogPort catalog) {
        ModelRouteProperties routeProperties = new ModelRouteProperties();
        ModelErrorClassifier classifier = new ModelErrorClassifier();
        RouteStateStore stateStore = new RouteStateStore(routeProperties);
        ModelCircuitBreakers breakers = new ModelCircuitBreakers(routeProperties, classifier);
        ModelRouter router = new ModelRouter(routeProperties, new ModelVendorProperties(), catalog, stateStore,
                breakers);
        return new ModelInvoker(adapter, new OutputSchemaValidator(), classifier, traceHolder,
                new ModelCallLogRecorder(logPort, catalog), router, stateStore, breakers);
    }
}
