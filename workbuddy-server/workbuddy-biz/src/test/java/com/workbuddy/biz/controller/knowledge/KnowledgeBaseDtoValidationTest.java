package com.workbuddy.biz.controller.knowledge;

import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseCreateDTO;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseUpdateDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库收参注解校验单测：名称必填与长度、策略绑定开关必填且只接受 0/1
 * （创建与更新同一口径；Controller `@Valid` 触发，失败由 `GlobalExceptionHandler` 转 40001）。
 */
class KnowledgeBaseDtoValidationTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();

    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    /**
     * 守护 {@code flagShouldOnlyAcceptZeroOrOne}：0/1 放行，缺省与越界（含负数）拒绝，创建更新同判。
     */
    @Test
    void flagShouldOnlyAcceptZeroOrOne() {
        for (Integer legal : new Integer[] { 1, 0 }) {
            assertTrue(VALIDATOR.validate(createDto(legal)).isEmpty(), () -> "创建入参合法取值被拒: " + legal);
            assertTrue(VALIDATOR.validate(updateDto(legal)).isEmpty(), () -> "更新入参合法取值被拒: " + legal);
        }
        for (Integer illegal : new Integer[] { null, 2, -1 }) {
            assertPropertyViolated(VALIDATOR.validate(createDto(illegal)), "strategyBindingEnabled");
            assertPropertyViolated(VALIDATOR.validate(updateDto(illegal)), "strategyBindingEnabled");
        }
    }

    /**
     * 守护 {@code nameShouldBeRequiredAndBounded}：名称空白与超 14 字在创建、更新两侧都拒绝。
     */
    @Test
    void nameShouldBeRequiredAndBounded() {
        KnowledgeBaseCreateDTO blankCreate = createDto(1);
        blankCreate.setName("   ");
        KnowledgeBaseUpdateDTO blankUpdate = updateDto(1);
        blankUpdate.setName("   ");
        KnowledgeBaseCreateDTO overlongCreate = createDto(1);
        overlongCreate.setName("x".repeat(15));
        KnowledgeBaseUpdateDTO overlongUpdate = updateDto(1);
        overlongUpdate.setName("x".repeat(15));

        assertPropertyViolated(VALIDATOR.validate(blankCreate), "name");
        assertPropertyViolated(VALIDATOR.validate(blankUpdate), "name");
        assertPropertyViolated(VALIDATOR.validate(overlongCreate), "name");
        assertPropertyViolated(VALIDATOR.validate(overlongUpdate), "name");
    }

    /** 创建请求体的合法收参（开关由入参决定，null 表示请求体未带该字段） */
    private KnowledgeBaseCreateDTO createDto(Integer strategyBindingEnabled) {
        KnowledgeBaseCreateDTO dto = new KnowledgeBaseCreateDTO();
        dto.setName("标书库");
        dto.setDescription("招标文件");
        dto.setStrategyBindingEnabled(strategyBindingEnabled);
        return dto;
    }

    /** 更新请求体的合法收参（id 与名称先给上，让违规只来自被测字段） */
    private KnowledgeBaseUpdateDTO updateDto(Integer strategyBindingEnabled) {
        KnowledgeBaseUpdateDTO dto = new KnowledgeBaseUpdateDTO();
        dto.setId(9L);
        dto.setName("标书库");
        dto.setDescription("招标文件");
        dto.setStrategyBindingEnabled(strategyBindingEnabled);
        return dto;
    }

    private <T> void assertPropertyViolated(Set<ConstraintViolation<T>> violations, String property) {
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(property)),
                () -> property + " 应命中校验违规，实际: " + violations);
    }
}
