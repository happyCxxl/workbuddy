package com.workbuddy.worker.parser.pdf.pipeline;

import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.ParseContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文档加载步骤：小文件整档留内存，大文件落临时文件，返回打开的文档与页数。
 *
 * <p>加载失败时关闭已打开的文档并删除临时文件；输入流不由本步骤关闭。
 */
@Slf4j
public final class DocumentLoadStep implements DocumentStep<ParseContext, LoadedPdf> {

    /**
     * 加载 PDF。
     *
     * @param context 解析上下文
     * @return 已加载文档
     * @throws IOException 读取输入流、落临时文件或打开 PDF 失败
     */
    @Override
    public LoadedPdf execute(ParseContext context) throws IOException {
        context.getCancellationToken().throwIfCancelled();
        int largeFileMinBytes = context.getProperties().getLargeFileMinBytes();
        int probe = largeFileMinBytes >= Integer.MAX_VALUE ? Integer.MAX_VALUE : largeFileMinBytes + 1;
        byte[] head = context.getInputStream().readNBytes(probe);

        Path tempFile = null;
        PDDocument document = null;
        try {
            if (head.length <= largeFileMinBytes) {
                document = Loader.loadPDF(head);
            } else {
                tempFile = spoolToTempFile(context, head);
                document = Loader.loadPDF(tempFile.toFile(), IOUtils.createTempFileOnlyStreamCache());
            }
            return new LoadedPdf(document, tempFile, document.getNumberOfPages());
        } catch (IOException | RuntimeException e) {
            closeQuietly(document);
            deleteQuietly(tempFile);
            throw e;
        }
    }

    /** 已读入的头部再加流剩余部分落到临时文件。 */
    private static Path spoolToTempFile(ParseContext context, byte[] head) throws IOException {
        Path path = Files.createTempFile("kb-parse-", ".pdf");
        try (OutputStream out = Files.newOutputStream(path)) {
            out.write(head);
            context.getInputStream().transferTo(out);
        }
        return path;
    }

    private static void closeQuietly(PDDocument document) {
        if (NullUtil.isNull(document)) {
            return;
        }
        try {
            document.close();
        } catch (IOException e) {
            log.warn("===> DocumentLoadStep 加载失败后关闭文档失败", e);
        }
    }

    private static void deleteQuietly(Path path) {
        if (NullUtil.isNull(path)) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("===> DocumentLoadStep 加载失败后删除临时文件失败, path={}", path, e);
        }
    }
}
