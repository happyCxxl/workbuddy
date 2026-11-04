package com.workbuddy.worker.parser.pdf.store;

import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 磁盘页产物存储：每个装配窗口一个临时文件，第二遍按窗口读取，不保留全档页产物对象。
 */
@Slf4j
final class DiskPageArtifactStore implements PageArtifactStore {

    private static final String PREFIX = "kb-parse-pages-";

    private final Path directory;

    /** 生产构造：在系统临时目录下创建本次解析独有的页产物目录。 */
    DiskPageArtifactStore() {
        this(createTempDirectory());
    }

    /**
     * 测试/定制构造：使用指定目录。
     *
     * @param directory 页产物目录
     */
    DiskPageArtifactStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "页产物目录不能为空");
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new PageArtifactStoreException("创建页产物目录失败: " + directory, e);
        }
    }

    /**
     * 写入单窗口页产物到临时文件。
     *
     * @param fromPage 窗口起始页
     * @param pages    页产物
     */
    @Override
    public void writeWindow(int fromPage, List<PageContent> pages) {
        PageArtifactSupport.validateWindow(fromPage, pages);
        Path file = PageArtifactSupport.fileOf(directory, fromPage);
        try {
            PageArtifactCodec.write(file, pages);
        } catch (IOException e) {
            throw new PageArtifactStoreException("写入页产物失败: " + file, e);
        }
    }

    /**
     * 从临时文件读取单窗口页产物；缺失或损坏即失败。
     *
     * @param fromPage 窗口起始页
     * @return 页产物
     */
    @Override
    public List<PageContent> readWindow(int fromPage) {
        Path file = PageArtifactSupport.fileOf(directory, fromPage);
        if (!Files.exists(file)) {
            throw new PageArtifactStoreException("页产物窗口不存在: " + fromPage);
        }
        try {
            List<PageContent> pages = PageArtifactCodec.read(file);
            PageArtifactSupport.validateWindow(fromPage, pages);
            return pages;
        } catch (IOException e) {
            throw new PageArtifactStoreException("读取页产物失败: " + file, e);
        }
    }

    /** 删除临时目录。 */
    @Override
    public void close() {
        deleteRecursively(directory);
    }

    /** 创建本次解析独有的临时目录 */
    private static Path createTempDirectory() {
        try {
            return Files.createTempDirectory(PREFIX);
        } catch (IOException e) {
            throw new PageArtifactStoreException("创建页产物临时目录失败", e);
        }
    }

    /** 递归清理临时目录：失败只告警，不掩盖解析结果 */
    private static void deleteRecursively(Path path) {
        if (NullUtil.isNull(path) || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            paths.forEach(DiskPageArtifactStore::deleteQuietly);
        } catch (IOException e) {
            log.warn("===> DiskPageArtifactStore 临时目录清理失败, path={}", path, e);
        }
    }

    /** 删除单个临时文件：失败只告警 */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("===> DiskPageArtifactStore 临时文件删除失败, path={}", path, e);
        }
    }
}
