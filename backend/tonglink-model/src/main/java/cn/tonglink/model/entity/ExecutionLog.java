/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.model.entity;

/**
 * 执行日志：按 {@code seq} 严格递增，保证前端顺序与断线补齐。
 *
 * @param id          日志 ID
 * @param executionId 所属执行
 * @param nodeId      产生日志的节点，整体级日志为空串
 * @param seq         序号，从 1 递增
 * @param level       级别：DEBUG / INFO / WARN / ERROR
 * @param message     日志内容
 * @param ts          时间戳
 */
public record ExecutionLog(String id, String executionId, String nodeId, long seq, String level,
                           String message, String ts) {
}
