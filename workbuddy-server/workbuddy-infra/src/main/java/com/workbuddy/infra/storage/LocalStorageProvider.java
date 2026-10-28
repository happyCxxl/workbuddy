package com.workbuddy.infra.storage;

import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地磁盘存储提供者：把 {@code bucket} 当一级子目录、{@code key} 当文件名落到存储根下。
 *
 * <p>**目录布局**：{@code {root}/{bucket}/{key}}，与对象存储的两级结构一一对应。
 * 存储根取自数据源定义的参数；桶名取自记录（写入时落库），读取不回落到定义里的桶名。
 *
 * <p>**路径逃逸防护**：{@code key}（fileId 或 sha256）统一做路径规范化 + 前缀校验，
 * 含 {@code ..} 等越界串一律拒绝。
 *
 * <p>**不是原子的**：写入用临时文件 + 移动实现"内容不半截"，不做 fsync、不加锁；
 * 并发与断电语义不承诺强于对象存储。
 */
@Slf4j
public class LocalStorageProvider implements StorageProvider {

    /** 临时文件后缀：先写这个再原子移动到目标名，避免读到写了一半的对象 */
    private static final String TMP_SUFFIX = ".part";

    private final Path root;

    /**
     * 构造本地存储后端。
     *
     * @param def 数据源定义（根目录取自其参数）
     */
    public LocalStorageProvider(StorageSourceDef def) {
        this.root = Paths.get(def.param(StorageSourceDef.KEY_ROOT_DIR)).toAbsolutePath().normalize();
    }

    /**
     * 存储类型。
     *
     * @return 本地存储
     */
    @Override
    public StorageType type() {
        return StorageType.LOCAL;
    }

    /**
     * 建根目录，重复调用无副作用。
     */
    @Override
    public void initialize() {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("本地存储根目录创建失败: " + root, e);
        }
        log.info("本地存储根目录: {}", root);
    }

    /**
     * 写入对象：先写临时文件再原子移动，避免读到写了一半的对象。
     *
     * @param bucket      桶名（根目录下的一级子目录）
     * @param key         对象名
     * @param in          内容流
     * @param size        内容字节数
     * @param contentType 内容类型
     */
    @Override
    public void put(String bucket, String key, InputStream in, long size, String contentType) {
        Path target = resolve(bucket, key);
        Path tmp = target.resolveSibling(target.getFileName() + TMP_SUFFIX);
        try {
            // 只建父目录：createDirectories(target) 会把 target 本身建成目录，随后 copy 必然失败
            Files.createDirectories(parentOf(target));
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new IllegalStateException("本地对象写入失败: " + bucket + "/" + key, e);
        }
    }

    /**
     * 打开对象内容流。
     *
     * @param bucket 桶名
     * @param key    对象名
     * @return 内容流
     */
    @Override
    public InputStream get(String bucket, String key) {
        Path target = resolve(bucket, key);
        ThrowUtil.throwIf(!Files.isRegularFile(target),
                () -> new IllegalStateException("本地对象不存在: " + bucket + "/" + key));
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new IllegalStateException("本地对象读取失败: " + bucket + "/" + key, e);
        }
    }

    /**
     * 对象是否存在。
     *
     * @param bucket 桶名
     * @param key    对象名
     * @return 存在为 true
     */
    @Override
    public boolean exists(String bucket, String key) {
        return Files.isRegularFile(resolve(bucket, key));
    }

    /**
     * 解析对象路径并校验没有逃出根目录。
     *
     * <p>**前置校验 + 事后兜底**：{@code ..} 与绝对路径在拼进 root 之前先拒，
     * 拼接后再用 {@code startsWith} 校验一次。
     *
     * @param bucket 桶名（本地实现里是根目录下的一级子目录）
     * @param key    对象名（fileId 或 sha256）
     * @return 规范化后的绝对路径（必然含有父目录：至少包含 bucket 与 key 两段）
     * @throws IllegalArgumentException 路径为空、含越级片段或绝对路径
     */
    private Path resolve(String bucket, String key) {
        ThrowUtil.throwIf(NullUtil.isNull(bucket) || bucket.isBlank() || NullUtil.isNull(key) || key.isBlank(),
                () -> new IllegalArgumentException("bucket 与 key 均不可为空"));
        ThrowUtil.throwIf(containsTraversal(bucket) || containsTraversal(key),
                () -> new IllegalArgumentException("对象路径含越级片段: " + bucket + "/" + key));
        Path candidate = root.resolve(bucket).resolve(key).normalize();
        ThrowUtil.throwIf(!candidate.startsWith(root),
                () -> new IllegalArgumentException("对象路径越出存储根目录: " + bucket + "/" + key));
        return candidate;
    }

    /** 是否含 {@code ..} 片段或以路径分隔符开头（后者会让 resolve 丢弃 root） */
    private boolean containsTraversal(String segment) {
        if (segment.startsWith("/") || segment.startsWith("\\")) {
            return true;
        }
        for (String part : segment.split("[/\\\\]")) {
            if ("..".equals(part)) {
                return true;
            }
        }
        return false;
    }

    /** 取对象路径的父目录：无父目录时抛明确错误，不返回 null */
    private Path parentOf(Path target) {
        Path parent = target.getParent();
        ThrowUtil.throwIf(NullUtil.isNull(parent),
                () -> new IllegalArgumentException("对象路径缺少父目录: " + target));
        return parent;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不影响主流程；残留的 .part 文件不会被 exists/get 命中
        }
    }
}
