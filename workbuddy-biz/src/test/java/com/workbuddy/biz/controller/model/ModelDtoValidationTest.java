package com.workbuddy.biz.controller.model;

import com.workbuddy.common.dto.request.model.ModelQueryDTO;
import com.workbuddy.common.dto.request.model.ModelSaveDTO;
import com.workbuddy.common.dto.request.model.ModelStatusDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型收参注解校验单测：必填与长度、非负、思考标记 0/1、币种与状态字典（Controller `@Valid` 的判定口径）。
 */
class ModelDtoValidationTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();

    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    /**
     * 守护 {@code fullDtoShouldPass}：该场景行为改动即失败。
     */
    @Test
    void fullDtoShouldPass() {
        assertTrue(VALIDATOR.validate(saveDto()).isEmpty());
        assertTrue(VALIDATOR.validate(statusDto("disabled")).isEmpty());
    }

    /**
     * 守护 {@code blankOrOverlongTextShouldFail}：该场景行为改动即失败。
     */
    @Test
    void blankOrOverlongTextShouldFail() {
        ModelSaveDTO blankVendor = saveDto();
        blankVendor.setVendorCode("   ");
        ModelSaveDTO blankKey = saveDto();
        blankKey.setModelKey("");
        ModelSaveDTO overlong = saveDto();
        overlong.setModelKey("x".repeat(129));

        assertPropertyViolated(VALIDATOR.validate(blankVendor), "vendorCode");
        assertPropertyViolated(VALIDATOR.validate(blankKey), "modelKey");
        assertPropertyViolated(VALIDATOR.validate(overlong), "modelKey");
    }

    /**
     * 守护 {@code negativeNumbersShouldFail}：该场景行为改动即失败。
     */
    @Test
    void negativeNumbersShouldFail() {
        ModelSaveDTO price = saveDto();
        price.setPriceInPerM(new BigDecimal("-1"));
        ModelSaveDTO limit = saveDto();
        limit.setRpmLimit(-5);

        assertPropertyViolated(VALIDATOR.validate(price), "priceInPerM");
        assertPropertyViolated(VALIDATOR.validate(limit), "rpmLimit");
    }

    /** 0 / 1 / 留空合法，越界取值拒绝（{@code thinkingFlagShouldBoundToZeroOrOne}）。 */
    @Test
    void thinkingFlagShouldBoundToZeroOrOne() {
        ModelSaveDTO legalZero = saveDto();
        legalZero.setThinkingEnabled(0);
        ModelSaveDTO legalNull = saveDto();
        legalNull.setThinkingEnabled(null);
        ModelSaveDTO illegal = saveDto();
        illegal.setThinkingEnabled(2);

        assertTrue(VALIDATOR.validate(legalZero).isEmpty());
        assertTrue(VALIDATOR.validate(legalNull).isEmpty());
        assertPropertyViolated(VALIDATOR.validate(illegal), "thinkingEnabled");
    }

    /** 限额必填且为正数（{@code limitsShouldBeRequiredAndPositive}）。 */
    @Test
    void limitsShouldBeRequiredAndPositive() {
        ModelSaveDTO missingRpm = saveDto();
        missingRpm.setRpmLimit(null);
        ModelSaveDTO missingTpm = saveDto();
        missingTpm.setTpmLimit(null);
        ModelSaveDTO zero = saveDto();
        zero.setRpmLimit(0);

        assertPropertyViolated(VALIDATOR.validate(missingRpm), "rpmLimit");
        assertPropertyViolated(VALIDATOR.validate(missingTpm), "tpmLimit");
        assertPropertyViolated(VALIDATOR.validate(zero), "rpmLimit");
    }

    /** 币种必填、只认 CNY / USD 精确大小写（{@code currencyShouldMatchDictionary}）。 */
    @Test
    void currencyShouldMatchDictionary() {
        ModelSaveDTO absent = saveDto();
        absent.setCurrency(null);
        ModelSaveDTO lower = saveDto();
        lower.setCurrency("usd");
        ModelSaveDTO illegal = saveDto();
        illegal.setCurrency("EUR");

        assertPropertyViolated(VALIDATOR.validate(absent), "currency");
        assertPropertyViolated(VALIDATOR.validate(lower), "currency");
        assertPropertyViolated(VALIDATOR.validate(illegal), "currency");
    }

    /**
     * 守护 {@code statusShouldMatchDictionary}：该场景行为改动即失败。
     */
    @Test
    void statusShouldMatchDictionary() {
        assertTrue(VALIDATOR.validate(statusDto("ENABLED")).isEmpty());
        assertPropertyViolated(VALIDATOR.validate(statusDto("PAUSED")), "status");
        assertPropertyViolated(VALIDATOR.validate(statusDto(" ")), "status");
    }

    /** 清单查询：六个能力取值与大小写不敏感都放行，字典外取值（含带空格的）拒绝（{@code queryCapabilityShouldMatchDictionary}）。 */
    @Test
    void queryCapabilityShouldMatchDictionary() {
        for (String legal : new String[] { "CHAT", "embedding", "OCR", "rerank", "IMAGE", "VIDEO", null }) {
            assertTrue(VALIDATOR.validate(queryDto(legal, null)).isEmpty(), "合法取值被拒: " + legal);
        }
        assertPropertyViolated(VALIDATOR.validate(queryDto("LAYOUT", null)), "capability");
        assertPropertyViolated(VALIDATOR.validate(queryDto("TABLE_STRUCTURE", null)), "capability");
        assertPropertyViolated(VALIDATOR.validate(queryDto("VISION", null)), "capability");
        assertPropertyViolated(VALIDATOR.validate(queryDto("EMBEDDING ", null)), "capability");
    }

    /**
     * 守护 {@code queryStatusShouldMatchDictionary}：该场景行为改动即失败。
     */
    @Test
    void queryStatusShouldMatchDictionary() {
        assertTrue(VALIDATOR.validate(queryDto(null, "disabled")).isEmpty());
        assertPropertyViolated(VALIDATOR.validate(queryDto(null, "PAUSED")), "status");
    }

    private ModelQueryDTO queryDto(String capability, String status) {
        ModelQueryDTO query = new ModelQueryDTO();
        query.setCapability(capability);
        query.setStatus(status);
        return query;
    }

    /** 字段齐全的合法收参 */
    private ModelSaveDTO saveDto() {
        ModelSaveDTO dto = new ModelSaveDTO();
        dto.setVendorCode("deepseek");
        dto.setModelKey("qwen-max");
        dto.setCapabilities(List.of("CHAT", "EMBEDDING"));
        dto.setThinkingEnabled(1);
        dto.setInputLimit(30720);
        dto.setOutputLimit(8192);
        dto.setContextWindow(32768);
        dto.setPriceInPerM(new BigDecimal("20"));
        dto.setPriceOutPerM(new BigDecimal("60"));
        dto.setCurrency("CNY");
        dto.setRpmLimit(600);
        dto.setTpmLimit(120000);
        return dto;
    }

    private ModelStatusDTO statusDto(String status) {
        ModelStatusDTO dto = new ModelStatusDTO();
        dto.setStatus(status);
        return dto;
    }

    private <T> void assertPropertyViolated(Set<ConstraintViolation<T>> violations, String property) {
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(property)),
                () -> property + " 应命中校验违规，实际: " + violations);
    }
}
