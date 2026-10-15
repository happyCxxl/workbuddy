package com.workbuddy.worker.parser;

import com.workbuddy.common.enums.input.FileFormat;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.worker.input.FileValidationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动期一致性校验：文件白名单（`knowledge.file.enabled-formats`）里的每种格式都要有解析器接得住 ——
 * 白名单在文件侧、解析器在 worker 侧，靠人工对齐，漏接的格式在启动期就暴露，不留到解析时才失败。
 * 反向差异（有解析器但未进白名单）只打警告：文件侧不会放行这类文件。
 *
 * @author cxxl
 */
@Slf4j
@Component
public class ParserWhitelistChecker implements ApplicationRunner {

    private final List<DocumentParserPort> parsers;
    private final FileValidationProperties properties;

    public ParserWhitelistChecker(List<DocumentParserPort> parsers, FileValidationProperties properties) {
        this.parsers = parsers;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        check(parsers, properties.getEnabledFormats());
    }

    /** 校验并返回结论；白名单里的格式没有解析器时抛异常阻断启动 */
    void check(List<DocumentParserPort> parserPorts, List<String> enabledFormats) {
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
                log.warn("格式 {} 有解析器但未进白名单（`knowledge.file.enabled-formats`），文件侧不会放行", format);
            }
        }
        log.info("解析格式白名单校验通过, enabledFormats={}, parsers={}", enabledFormats,
                parserPorts.stream().map(DocumentParserPort::capabilityName).toList());
    }
}
