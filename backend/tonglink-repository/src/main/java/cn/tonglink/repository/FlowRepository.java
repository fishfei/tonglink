/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.repository;

import cn.tonglink.model.entity.Flow;
import cn.tonglink.model.entity.FlowVersion;
import cn.tonglink.model.entity.NodePackage;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 流程、版本与节点包的持久化。
 *
 * <p>流程列表按 {@code updated_at} 倒序，最近编辑的排在最前，符合编排页面的使用习惯。</p>
 */
@Repository
public class FlowRepository {

    private static final RowMapper<Flow> FLOW = (rs, i) -> new Flow(
            rs.getString("id"),
            rs.getString("project_id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getString("graph_json"),
            nullToEmpty(rs.getString("published_graph_json")),
            rs.getInt("published_version"),
            rs.getString("next_run"),
            rs.getString("last_run"),
            rs.getLong("records"),
            rs.getString("success_rate"),
            rs.getString("created_at"),
            rs.getString("updated_at"));

    private static final RowMapper<FlowVersion> VERSION = (rs, i) -> new FlowVersion(
            rs.getString("id"),
            rs.getString("flow_id"),
            rs.getInt("version"),
            rs.getString("graph_json"),
            rs.getString("changelog"),
            rs.getString("created_at"));

    private static final RowMapper<NodePackage> PACKAGE = (rs, i) -> new NodePackage(
            rs.getString("id"),
            rs.getString("package_id"),
            rs.getString("version"),
            rs.getString("manifest_json"),
            rs.getString("status"),
            rs.getString("installed_at"));

    private final JdbcTemplate jdbc;

    public FlowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 全部流程，最近编辑优先。 */
    public List<Flow> flows() {
        return jdbc.query("SELECT * FROM flow_definition ORDER BY updated_at DESC", FLOW);
    }

    /** 指定项目下的流程。 */
    public List<Flow> flowsByProject(String projectId) {
        return jdbc.query("SELECT * FROM flow_definition WHERE project_id=? ORDER BY updated_at DESC",
                FLOW, projectId);
    }

    /** 按 ID 查询流程，不存在返回 null。 */
    public Flow flow(String id) {
        List<Flow> rows = jdbc.query("SELECT * FROM flow_definition WHERE id=?", FLOW, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 新建流程。 */
    public void saveFlow(Flow f) {
        jdbc.update("""
                INSERT INTO flow_definition(id,project_id,name,description,status,graph_json,
                                            published_graph_json,published_version,next_run,last_run,
                                            records,success_rate,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, f.id(), f.projectId(), f.name(), f.description(), f.status(), f.graphJson(),
                f.publishedGraphJson(), f.publishedVersion(), f.nextRun(), f.lastRun(), f.records(),
                f.successRate(), f.createdAt(), f.updatedAt());
    }

    /** 全量更新流程（ID 与创建时间不变）。 */
    public void updateFlow(Flow f) {
        jdbc.update("""
                UPDATE flow_definition SET project_id=?,name=?,description=?,status=?,graph_json=?,
                       published_graph_json=?,published_version=?,next_run=?,last_run=?,records=?,
                       success_rate=?,updated_at=?
                WHERE id=?
                """, f.projectId(), f.name(), f.description(), f.status(), f.graphJson(),
                f.publishedGraphJson(), f.publishedVersion(), f.nextRun(), f.lastRun(), f.records(),
                f.successRate(), f.updatedAt(), f.id());
    }

    /**
     * 条件认领下次调度时间：仅当数据库中的值仍等于 expected 时才推进。
     *
     * <p>多实例共用同一数据库时，只有一个实例能成功推进；其余实例据此放弃本次触发，
     * 无需引入分布式锁。占位值（空/—）视为「尚未初始化」，允许初始化认领。</p>
     *
     * @return 是否认领成功
     */
    public boolean claimNextRun(String id, String expected, String next) {
        String now = LocalDateTime.now().withNano(0).toString();
        int rows;
        if (expected == null || expected.isBlank() || "—".equals(expected)) {
            rows = jdbc.update("""
                    UPDATE flow_definition SET next_run=?, updated_at=?
                    WHERE id=? AND (next_run IS NULL OR next_run='' OR next_run='—')
                    """, next, now, id);
        } else {
            rows = jdbc.update("""
                    UPDATE flow_definition SET next_run=?, updated_at=? WHERE id=? AND next_run=?
                    """, next, now, id, expected);
        }
        return rows > 0;
    }

    /**
     * 执行结束时只刷新统计字段，绝不覆盖 {@code next_run}。
     *
     * <p>调度器在提交执行前就已推进 {@code next_run}，若这里沿用整行 updateFlow，
     * 会用执行开始时读到的旧值把它改回去，造成同一周期重复触发。</p>
     */
    public void updateFlowStats(String id, String lastRun, long records) {
        jdbc.update("""
                UPDATE flow_definition SET last_run=?, records=?, success_rate='100%', updated_at=?
                WHERE id=?
                """, lastRun, records, LocalDateTime.now().withNano(0).toString(), id);
    }

    /** 删除流程；版本快照保留，便于审计与回溯。 */
    public void deleteFlow(String id) {
        jdbc.update("DELETE FROM flow_definition WHERE id=?", id);
    }

    /** 某流程的全部版本，最新在前。 */
    public List<FlowVersion> versions(String flowId) {
        return jdbc.query("SELECT * FROM flow_version WHERE flow_id=? ORDER BY version DESC",
                VERSION, flowId);
    }

    /** 追加版本快照。 */
    public void saveVersion(FlowVersion v) {
        jdbc.update("INSERT INTO flow_version(id,flow_id,version,graph_json,changelog,created_at) VALUES(?,?,?,?,?,?)",
                v.id(), v.flowId(), v.version(), v.graphJson(), v.changelog(), v.createdAt());
    }

    /** 读取指定发布版本冻结的图，供历史执行回溯使用。 */
    public String versionGraph(String flowId, int version) {
        List<String> rows = jdbc.query("SELECT graph_json FROM flow_version WHERE flow_id=? AND version=?",
                (rs, i) -> rs.getString(1), flowId, version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 已安装的节点包。 */
    public List<NodePackage> nodePackages() {
        return jdbc.query("SELECT * FROM node_package ORDER BY installed_at DESC", PACKAGE);
    }

    /** 记录节点包安装。 */
    public void saveNodePackage(NodePackage p) {
        jdbc.update("INSERT INTO node_package(id,package_id,version,manifest_json,status,installed_at) VALUES(?,?,?,?,?,?)",
                p.id(), p.packageId(), p.version(), p.manifestJson(), p.status(), p.installedAt());
    }

    /** 卸载节点包。 */
    public void deleteNodePackage(String packageId) {
        jdbc.update("DELETE FROM node_package WHERE package_id=?", packageId);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
