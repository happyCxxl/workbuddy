package com.workbuddy.model.service.support.route;

/**
 * 容量快照：RPM/TPM 剩余比例（0~1）。
 *
 * @param rpmRatio RPM 剩余比例
 * @param tpmRatio TPM 剩余比例
 */
public record CapacitySnapshot(double rpmRatio, double tpmRatio) {

    /** 未登记限额时的空快照 */
    public static final CapacitySnapshot EMPTY = new CapacitySnapshot(0, 0);

    /**
     * 综合容量分：取两个比例的最小值，越低越靠后。
     *
     * @return 0~1 的容量分
     */
    public double score() {
        return Math.min(rpmRatio, tpmRatio);
    }
}
