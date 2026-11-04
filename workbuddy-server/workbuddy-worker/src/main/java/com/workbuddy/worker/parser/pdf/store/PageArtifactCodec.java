package com.workbuddy.worker.parser.pdf.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workbuddy.worker.parser.pdf.model.PageContent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 页产物编解码：Jackson JSON + GZIP，页产物只在单次解析内使用，不承担跨版本兼容。
 */
final class PageArtifactCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<PageContent>> PAGE_LIST = new TypeReference<>() {
    };

    private PageArtifactCodec() {
    }

    /**
     * 写入窗口页产物。
     *
     * @param file  目标文件
     * @param pages 页产物
     * @throws IOException 写入失败
     */
    static void write(Path file, List<PageContent> pages) throws IOException {
        try (OutputStream out = Files.newOutputStream(file);
             GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            MAPPER.writeValue(gzip, pages);
        }
    }

    /**
     * 读取窗口页产物。
     *
     * @param file 页产物文件
     * @return 页产物
     * @throws IOException 读取或解析失败
     */
    static List<PageContent> read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file);
             GZIPInputStream gzip = new GZIPInputStream(in)) {
            return MAPPER.readValue(gzip, PAGE_LIST);
        }
    }
}
