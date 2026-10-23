package com.workbuddy.worker.startup;

import com.workbuddy.common.enums.input.FileFormat;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.startup.StartupCheck;
import com.workbuddy.common.startup.StartupCheckChain;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.config.FileProperties;
import com.workbuddy.worker.parser.DocumentParserPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动期一致性校验：文件白名单（`knowledge.file.enabled-formats`）里的每种格式都要有解析器接得住 ——
 * 白名单在文件侧、解析器在 worker 侧，靠人工对齐，漏接的格式在启动期就暴露，不留到解析时才失败。
 * 反向差异（有解析器但未进白名单）只打警告：文件侧不会放行这类文件。
 *
 * <p>在启动校验链里排在 {@link #ORDER_ASSEMBLY} 档：它依赖容器把解析器装配齐全。
 */
@Slf4j
@Component
public class ParserWhitelistChecker implements StartupCheck {

    private final List<DocumentParserPort> parsers;
    private final FileProperties properties;

    /**
     * 构造校验项：登记全部解析器并读取白名单配置。
     *
     * @param parsers    解析器实现
     * @param properties 文件校验配置
     */
    public ParserWhitelistChecker(List<DocumentParserPort> parsers, FileProperties properties) {
        this.parsers = parsers;
        this.properties = properties;
    }

    /**
     * 校验序号：参数类校验。
     *
     * @return 校验顺序
     */
    @Override
    public int order() {
        return ORDER_ASSEMBLY;
    }

    /**
     * 校验各格式白名单内的解析器都已注册，通过后交给链上的下一项。
     *
     * @param next 启动校验链的下一项
     */
    @Override
    public void check(StartupCheckChain next) {
        checkWhitelist(parsers, properties.getEnabledFormats());
        next.proceed();
    }

    /** 校验并返回结论；白名单里的格式没有解析器时抛异常阻断启动 */
    void checkWhitelist(List<DocumentParserPort> parserPorts, List<String> enabledFormats) {
        ThrowUtil.throwIf(NullUtil.isNull(enabledFormats), ErrorCode.PARAM_INVALID,
                "文件格式白名单未配置（配置键 knowledge.file.enabled-formats）");
        List<FileFormat> enabled = new ArrayList<>();
        for (String name : enabledFormats) {
            enabled.add(FileFormat.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT)));
        }
        List<String> unsupported = enabled.stream()
                .filter(format -> parserPorts.stream().noneMatch(parser -> parser.supports(format.getMimeType())))
                .map(Enum::name).toList();
        ThrowUtil.throwIf(!unsupported.isEmpty(), () -> new IllegalStateException("白名单格式没有可用的解析器: " + unsupported));
        for (FileFormat format : FileFormat.values()) {
            boolean parserExists = parserPorts.stream().anyMatch(parser -> parser.supports(format.getMimeType()));
            if (parserExists && !enabled.contains(format)) {
                log.warn("===> ParserWhitelistChecker 格式 {} 有解析器但未进白名单（`knowledge.file.enabled-formats`），文件侧不会放行", format);
            }
        }
        log.info("===> ParserWhitelistChecker 解析格式白名单校验通过, enabledFormats={}, parsers={}", enabledFormats,
                parserPorts.stream().map(DocumentParserPort::capabilityName).toList());
    }
}
