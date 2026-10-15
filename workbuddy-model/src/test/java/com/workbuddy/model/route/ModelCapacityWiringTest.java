package com.workbuddy.model.route;

import com.workbuddy.common.domain.model.ChatMessage;
import com.workbuddy.common.domain.model.ChatRequest;
import com.workbuddy.common.domain.model.GenerationResult;
import com.workbuddy.common.domain.model.MessageRole;
import com.workbuddy.common.domain.model.calllog.ModelCallLogEntry;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.enums.model.RouteReason;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.model.invoke.ModelInvoker;
import com.workbuddy.model.support.RecordingAdapter;
import com.workbuddy.model.support.RouteFixture;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容量占用接线的用例：每次真实调用结束后写入的占用读数，以及该读数对下一次选型的实际影响。
 *
 * <p>依据都是可观测结果：被调用的模型名、状态存储里的读数、选型读到的占用率与明细里的路由原因。
 * 覆盖三种情形：调用后读数确实变了；限额用满即被闸门剔除、请求落到另一个候选；厂商未回 usage 时
 * 按请求文本长度粗估占用，而明细里的用量仍按未知记。
 *
 * @author cxxl
 */
class ModelCapacityWiringTest {

    private static final String VENDOR = "dashscope";

    /** 名字靠前的候选：并列时按模型名定序，它是首选 */
    private static final String PRIMARY = "qwen-max";

    /** 名字靠后的候选 */
    private static final String SECONDARY = "qwen-plus";

    /** 每次成功调用的 token 合计：假适配器回的用量为十加五 */
    private static final long USAGE_TOKENS = 15L;

    /** 请求文本长度：粗估 token 数按字符数除以三算 */
    private static final int PROMPT_LENGTH = 90;

    /** 粗估出的 token 数 */
    private static final long ESTIMATED_TOKENS = 30L;

    /** 首选的每分钟请求限额：一次调用占到一半，两次即用满 */
    private static final int PRIMARY_RPM_LIMIT = 2;

    /** 次选的每分钟请求限额：用例尺度的大值，读数远不足以越线 */
    private static final int SECONDARY_RPM_LIMIT = 1000;

    /** 次选的每分钟 token 限额：同上，用例尺度的大值 */
    private static final int SECONDARY_TPM_LIMIT = 1_000_000;

    @Test
    void callShouldWriteOccupancyForTheCalledModel() {
        RouteFixture fixture = twoCandidates();
        ModelInvoker invoker = fixture.invoker(new RecordingAdapter());

        GenerationResult result = invoker.chatByCapability(ModelCapability.CHAT, request());

        assertEquals(PRIMARY, result.getModel());
        RouteStateStore.Snapshot called = fixture.stateStore().snapshot(VENDOR, PRIMARY);
        RouteStateStore.Snapshot untouched = fixture.stateStore().snapshot(VENDOR, SECONDARY);
        assertEquals(USAGE_TOKENS, called.tpmTokens(), "本次调用的 token 合计写进占用窗口");
        assertEquals(1, called.rpmCount(), "本次调用按一次请求记");
        assertEquals(1, called.totalCalls());
        assertEquals(0L, untouched.tpmTokens(), "没被调用的候选不写占用");
        assertEquals(0, untouched.rpmCount());
    }

    @Test
    void occupancyWrittenByCallsShouldFeedTheNextSelection() {
        RouteFixture fixture = twoCandidates();
        fixture.catalog().findByVendorAndModelKey(VENDOR, PRIMARY).setRpmLimit(PRIMARY_RPM_LIMIT);
        fixture.catalog().findByVendorAndModelKey(VENDOR, SECONDARY).setRpmLimit(SECONDARY_RPM_LIMIT);
        RecordingAdapter adapter = new RecordingAdapter();
        ModelInvoker invoker = fixture.invoker(adapter);

        GenerationResult first = invoker.chatByCapability(ModelCapability.CHAT, request());

        assertEquals(PRIMARY, first.getModel(), "首次选型落在名字靠前的候选");
        RouteStateStore.Snapshot afterFirst = fixture.stateStore().snapshot(VENDOR, PRIMARY);
        assertEquals(USAGE_TOKENS, afterFirst.tpmTokens());
        assertEquals(1, afterFirst.rpmCount());
        assertEquals(0.5D, occupancy(fixture, PRIMARY), 0.0001D, "一次调用即占用一半");
        assertEquals(0L, fixture.stateStore().snapshot(VENDOR, SECONDARY).tpmTokens(),
                "没被调用的候选占用仍为零");

        // 第二次选型：首选的占用读数已进打分，容量项把它降权，请求落到零占用的那个
        GenerationResult second = invoker.chatByCapability(ModelCapability.CHAT, request());

        assertEquals(SECONDARY, second.getModel(), "容量项把已占用的候选降权");
        assertEquals(1, fixture.stateStore().snapshot(VENDOR, SECONDARY).rpmCount());
        assertEquals(USAGE_TOKENS, fixture.stateStore().snapshot(VENDOR, SECONDARY).tpmTokens());
        assertEquals(List.of(PRIMARY, SECONDARY), adapter.called());
    }

    @Test
    void candidateAtLimitShouldBeGatedOutWithCapacityReading() {
        RouteFixture fixture = twoCandidates();
        // 首选的 token 限额等于一次调用的用量：一次真实调用即把它推到上限
        fixture.catalog().findByVendorAndModelKey(VENDOR, PRIMARY).setTpmLimit((int) USAGE_TOKENS);
        fixture.catalog().findByVendorAndModelKey(VENDOR, SECONDARY).setTpmLimit(SECONDARY_TPM_LIMIT);
        RecordingAdapter adapter = new RecordingAdapter();
        ModelInvoker invoker = fixture.invoker(adapter);
        GenerationResult first = invoker.chatByCapability(ModelCapability.CHAT, request());

        assertEquals(PRIMARY, first.getModel(), "首次选型落在名字靠前的候选");
        assertEquals(USAGE_TOKENS, fixture.stateStore().snapshot(VENDOR, PRIMARY).tpmTokens(),
                "本次调用的 token 合计写进窗口");
        assertEquals(1.0D, occupancy(fixture, PRIMARY), 0.0001D, "读数 ÷ 限额即占用率，用满即到上限");

        // 第二个请求被上限闸门挡住，落到另一个候选
        GenerationResult second = invoker.chatByCapability(ModelCapability.CHAT, request());

        assertEquals(SECONDARY, second.getModel(), "占用已达上限，该候选被剔除");
        assertEquals(List.of(PRIMARY, SECONDARY), adapter.called());

        // 把另一个候选从目录里去掉：只剩占用到上限的那个，选型直接拒绝
        fixture.catalog().register(disabledDescriptor(SECONDARY));
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> fixture.router().plan(ModelCapability.CHAT));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
        assertTrue(error.getMessage().contains("占用已达上限"), error.getMessage());
    }

    @Test
    void candidateWithoutLimitShouldNotTakePartInOccupancy() {
        RouteFixture fixture = twoCandidates();
        fixture.catalog().findByVendorAndModelKey(VENDOR, PRIMARY).setRpmLimit(PRIMARY_RPM_LIMIT);
        fixture.catalog().findByVendorAndModelKey(VENDOR, SECONDARY).setRpmLimit(SECONDARY_RPM_LIMIT);
        RecordingAdapter adapter = new RecordingAdapter();
        ModelInvoker invoker = fixture.invoker(adapter);
        invoker.chatByCapability(ModelCapability.CHAT, request());
        invoker.chatByCapability(ModelCapability.CHAT, request());
        int recorded = fixture.stateStore().snapshot(VENDOR, PRIMARY).rpmCount();

        // 同一份读数搬到不配限额的模型上：占用率恒为零，上限闸门不参与判断
        fixture.catalog().findByVendorAndModelKey(VENDOR, PRIMARY).setRpmLimit(null);

        assertTrue(recorded > 0, "读数确实写进去了");
        assertEquals(0D, occupancy(fixture, PRIMARY), 0.0001D, "限额留空即占用率为零");
        assertEquals(recorded, fixture.stateStore().snapshot(VENDOR, PRIMARY).rpmCount(),
                "读数照记，只是不参与判断");
        assertEquals(PRIMARY, fixture.router().plan(ModelCapability.CHAT).selected().modelKey(),
                "占用率为零即通过占用闸门，首选仍可选");
    }

    @Test
    void missingUsageShouldEstimateTokensFromPromptText() {
        RouteFixture fixture = twoCandidates();
        RecordingAdapter adapter = new RecordingAdapter().withoutUsage();
        ModelInvoker invoker = fixture.invoker(adapter);

        GenerationResult result = invoker.chatByCapability(ModelCapability.CHAT, request());

        assertEquals(PRIMARY, result.getModel());
        assertEquals(List.of(PRIMARY), adapter.called());
        RouteStateStore.Snapshot snapshot = fixture.stateStore().snapshot(VENDOR, PRIMARY);
        assertEquals(ESTIMATED_TOKENS, snapshot.tpmTokens(), "厂商未回 usage 时按请求文本长度粗估");
        assertEquals(1, snapshot.rpmCount());
        ModelCallLogEntry entry = fixture.callLog().lastEntry();
        assertEquals(1, entry.getUsageUnknown(), "粗估值只进占用窗口，明细仍按用量未知记");
        assertNull(entry.getTotalTokens());
        assertNull(entry.getCost());
    }

    /** 两个候选：限额都留空，占用读数只由调用链写入 */
    private RouteFixture twoCandidates() {
        RouteFixture fixture = new RouteFixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, PRIMARY, null).withChatModel(VENDOR, SECONDARY, null);
        return fixture;
    }

    /** 选型读到的该候选决策：占用率与被放到哪个位置都看它 */
    private ModelRouter.Decision planOf(RouteFixture fixture, String modelKey) {
        return fixture.router().plan(ModelCapability.CHAT).ordered().stream()
                .filter(decision -> modelKey.equals(decision.modelKey()))
                .findFirst()
                .orElseThrow();
    }

    /** 停用的目录项：状态为 DISABLED，其他字段不影响占用口径的用例 */
    private ModelDescriptor disabledDescriptor(String modelKey) {
        ModelDescriptor descriptor = new ModelDescriptor();
        descriptor.setVendorCode(VENDOR);
        descriptor.setModelKey(modelKey);
        descriptor.setCapabilities(Set.of(ModelCapability.CHAT));
        descriptor.setStatus("DISABLED");
        return descriptor;
    }

    /** 该模型当前的占用率：请求数与 token 数两项取较大者，限额留空即该项记零 */
    private double occupancy(RouteFixture fixture, String modelKey) {
        RouteStateStore.Snapshot snapshot = fixture.stateStore().snapshot(VENDOR, modelKey);
        ModelDescriptor descriptor = fixture.catalog().findByVendorAndModelKey(VENDOR, modelKey);
        return Math.max(ratio(snapshot.rpmCount(), descriptor.getRpmLimit()),
                ratio(snapshot.tpmTokens(), descriptor.getTpmLimit()));
    }

    /** 单项占用率：限额留空或非正数按不参与处理 */
    private double ratio(long used, Integer limit) {
        return limit == null || limit <= 0 ? 0D : (double) used / limit;
    }

    /** 对话入参：单条用户消息，文本长度取 {@link #PROMPT_LENGTH} */
    private ChatRequest request() {
        ChatRequest request = new ChatRequest();
        request.setModelKey(PRIMARY);
        List<ChatMessage> messages = new ArrayList<>();
        ChatMessage message = new ChatMessage();
        message.setRole(MessageRole.USER);
        message.setContent("p".repeat(PROMPT_LENGTH));
        messages.add(message);
        request.setMessages(messages);
        request.setTimeoutMs(10000);
        return request;
    }
}
