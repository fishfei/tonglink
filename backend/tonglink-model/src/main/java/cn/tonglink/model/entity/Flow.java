/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.model.entity;

/**
 * 流程定义：草稿与发布版本合一的记录。
 *
 * <p>{@code graphJson} 始终保存最新草稿；{@code publishedGraphJson} 是发布瞬间的不可变快照。
 * 执行引擎对 {@code status=active} 的流程只读快照，因此编辑草稿不会影响正在运行的定时任务；
 * 对未发布流程手动点「运行」时才使用草稿，方便在发布前验证。</p>
 *
 * @param id                 流程 ID
 * @param projectId          所属项目
 * @param name               流程名称
 * @param description        说明
 * @param status             状态：active（已发布运行）/ paused（草稿或已下线）
 * @param graphJson          最新草稿图 JSON 文本
 * @param publishedGraphJson 最近一次发布的图快照，空串表示从未发布
 * @param publishedVersion   已发布版本号，0 表示从未发布
 * @param nextRun            下次计划执行时间
 * @param lastRun            最近一次执行时间
 * @param records            累计采集/写入记录数
 * @param successRate        成功率展示值
 * @param createdAt          创建时间
 * @param updatedAt          更新时间
 */
public record Flow(String id, String projectId, String name, String description, String status,
                   String graphJson, String publishedGraphJson, int publishedVersion, String nextRun,
                   String lastRun, long records, String successRate, String createdAt, String updatedAt) {

    /** 兼容未显式指定发布快照的构造调用（快照默认为空）。 */
    public Flow(String id, String projectId, String name, String description, String status,
                String graphJson, int publishedVersion, String nextRun, String lastRun,
                long records, String successRate, String createdAt, String updatedAt) {
        this(id, projectId, name, description, status, graphJson, "", publishedVersion, nextRun,
                lastRun, records, successRate, createdAt, updatedAt);
    }
}
