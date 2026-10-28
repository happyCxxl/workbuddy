package com.workbuddy.biz.service.support.artifact;

import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.biz.service.support.stats.StatsSupport;

import java.nio.charset.StandardCharsets;

/**
 * 环节产物读取器：统一产物本体的读取与读取异常映射。
 *
 * <p>读取失败原样抛出，由调用方按存储类/其余失败分别处理：存储类（40454 / 40455）向上抛出，
 * 其余记日志并留空条目。
 */
public final class StageArtifactReader {

    private StageArtifactReader() {
    }

    /**
     * 读产物本体：对象位置取自产物行（存储类型 + 桶名 + sha256）。
     *
     * @param fileStorage 对象存储
     * @param ref         产物对象位置（含存储类型与桶名），可空
     * @param type        产物本体类型
     * @return 产物本体；对象为空或读不到时返回 null
     */
    public static <T> T read(FileStorage fileStorage, ObjectRef ref, Class<T> type) {
        if (NullUtil.isNull(fileStorage) || NullUtil.isNull(ref)) {
            return null;
        }
        byte[] content = fileStorage.getObject(ref);
        if (NullUtil.isNull(content)) {
            return null;
        }
        return JsonUtil.toObject(new String(content, StandardCharsets.UTF_8), type);
    }

    /**
     * 存储类读取失败识别：对象在所属存储中不存在（40454）或记录所属后端未配置（40455）。
     *
     * @param e 读取过程中抛出的异常
     * @return 命中则返回该异常，可原样抛出；其余情况返回 null
     */
    public static WorkbuddyException storageFailureOf(Exception e) {
        return StatsSupport.storageFailureOf(e);
    }
}
