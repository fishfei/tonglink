/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.model.entity;

/**
 * 一次执行中「延时等待」节点的断点。
 *
 * @param id           断点 ID
 * @param executionId  所属执行
 * @param waitUntil    到期时间（ISO-8601 本地时间字符串）
 * @param resumeIndex  恢复时从拓扑序的第几个节点继续
 * @param snapshotJson 恢复所需的上下文快照：执行图、节点输出、分支、变量、累计条数
 * @param nodeId       延时节点 ID，恢复时用于把它标记为成功
 * @param createdAt    创建时间
 */
public record WaitState(String id, String executionId, String waitUntil, int resumeIndex,
                        String snapshotJson, String nodeId, String createdAt) {
}
