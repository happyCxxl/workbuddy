package com.workbuddy.biz.startup;

import com.workbuddy.auth.config.JwtProperties;
import com.workbuddy.auth.config.LoginGuardProperties;
import com.workbuddy.auth.startup.AuthConfigCheck;
import com.workbuddy.biz.config.RetrievalCapabilityProperties;
import com.workbuddy.biz.config.TaskQueueProperties;
import com.workbuddy.common.startup.StartupCheck;
import com.workbuddy.filecenter.config.FileCenterConfig;
import com.workbuddy.filecenter.startup.StorageTypeCheck;
import com.workbuddy.model.config.ModelVendorProperties;
import com.workbuddy.model.startup.ModelVendorValidator;
import com.workbuddy.worker.input.FileValidationProperties;
import com.workbuddy.worker.parser.DocumentParserPort;
import com.workbuddy.worker.parser.office.impl.DocDocumentParser;
import com.workbuddy.worker.parser.office.impl.DocxDocumentParser;
import com.workbuddy.worker.parser.office.impl.ExcelDocumentParser;
import com.workbuddy.worker.parser.pdf.PdfBoxDocumentParser;
import com.workbuddy.worker.parser.pdf.layout.ProjectionPageLayoutAnalyzer;
import com.workbuddy.worker.startup.ParserWhitelistChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动校验项的契约：每个真实校验项在通过路径上都必须调用 {@code next.proceed()}（漏调会静默跳过链上后续校验），
 * 且各自钉住声明的 order 档位（改档位属有意识操作，必须同时改本用例）。
 *
 * <p>本用例横跨各模块——只有 biz 能看见全部校验项，故落在此处。
 */
class StartupCheckContractTest {

    @Test
    void realChecksShouldProceedOnPassPath() {
        assertProceeds(new ModelVendorValidator(new ModelVendorProperties()));
        assertProceeds(new ParserWhitelistChecker(realParsers(), new FileValidationProperties()));
        assertProceeds(new RetrievalCapabilityCheck(new RetrievalCapabilityProperties()));
        assertProceeds(new TaskTimeoutCheck(new TaskQueueProperties()));
        assertProceeds(new StorageTypeCheck(new FileCenterConfig()));
        assertProceeds(new AuthConfigCheck(jwtProperties(), new LoginGuardProperties()));
    }

    @Test
    void knownChecksShouldKeepTheirOrderTier() {
        assertEquals(StartupCheck.ORDER_CREDENTIALS, new ModelVendorValidator(new ModelVendorProperties()).order());
        assertEquals(StartupCheck.ORDER_ASSEMBLY,
                new ParserWhitelistChecker(List.of(), new FileValidationProperties()).order());
        assertEquals(StartupCheck.ORDER_PARAMETERS,
                new RetrievalCapabilityCheck(new RetrievalCapabilityProperties()).order());
        assertEquals(StartupCheck.ORDER_PARAMETERS, new TaskTimeoutCheck(new TaskQueueProperties()).order());
        assertEquals(StartupCheck.ORDER_PARAMETERS, new StorageTypeCheck(new FileCenterConfig()).order());
        assertEquals(StartupCheck.ORDER_CREDENTIALS,
                new AuthConfigCheck(jwtProperties(), new LoginGuardProperties()).order());
    }

    private void assertProceeds(StartupCheck check) {
        AtomicBoolean proceeded = new AtomicBoolean();

        check.check(() -> proceeded.set(true));

        assertTrue(proceeded.get(), () -> check.name() + " 通过路径未调用 next.proceed()");
    }

    /** 令牌配置替身：密钥过长度门槛，两档有效期为正 */
    private JwtProperties jwtProperties() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-0123456789-abcdefghijklmnopqrstuvwxyz");
        properties.setTtlDays(1);
        properties.setTtlDaysRemember(30);
        return properties;
    }

    /** 真实解析器集合：覆盖 FileValidationProperties 的默认白名单 */
    private List<DocumentParserPort> realParsers() {
        return List.of(new PdfBoxDocumentParser(new ProjectionPageLayoutAnalyzer()),
                new DocxDocumentParser(), new DocDocumentParser(), new ExcelDocumentParser());
    }
}
