/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.model.entity;

/**
 * 一次流程执行。
 *
 * @param id             执行 ID
 * @param flowId         流程 ID
 * @param flowVersion    执行时的流程版本
 * @param triggerType    触发方式：manual / schedule
 * @param status         状态：PENDING / RUNNING / SUCCESS / FAILED / TIMEOUT
 * @param startTime      开始时间
 * @param endTime        结束时间，运行中为 null
 * @param records        本次采集/写入条数
 * @param errorSummary   失败摘要
 * @param idempotencyKey 幂等键，防止重复投递导致重复执行
 */
public record Execution(String id, String flowId, int flowVersion, String triggerType, String status,
                        String startTime, String endTime, long records, String errorSummary,
                        String idempotencyKey) {
}
