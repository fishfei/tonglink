/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.model.entity;

/**
 * 节点级执行记录：前端「节点时间线」的数据来源。
 *
 * <p>输入输出超过 64KB 时本应转存对象存储，轻量模式下直接内联，
 * 由 {@code ExecutionService} 控制截断，避免单行过大拖慢页面。</p>
 *
 * @param id           记录 ID
 * @param executionId  所属执行
 * @param nodeId       节点 ID
 * @param nodeTitle    节点在画布上的显示名称（历史记录为空时前端回退显示 nodeId）
 * @param nodeType     节点大类，如 source / code / output
 * @param nodeVersion  节点版本
 * @param status       节点状态：SUCCESS / FAILED / SKIPPED
 * @param durationMs   耗时（毫秒）
 * @param inputJson    节点输入 JSON
 * @param outputJson   节点输出 JSON
 * @param errorMessage 失败原因
 * @param retryCount   已重试次数
 */
public record ExecutionNode(String id, String executionId, String nodeId, String nodeTitle,
                            String nodeType, String nodeVersion, String status, long durationMs,
                            String inputJson, String outputJson, String errorMessage, int retryCount) {

    /** 兼容未记录节点名称的老调用。 */
    public ExecutionNode(String id, String executionId, String nodeId, String nodeType,
                         String nodeVersion, String status, long durationMs, String inputJson,
                         String outputJson, String errorMessage, int retryCount) {
        this(id, executionId, nodeId, "", nodeType, nodeVersion, status, durationMs,
                inputJson, outputJson, errorMessage, retryCount);
    }
}
