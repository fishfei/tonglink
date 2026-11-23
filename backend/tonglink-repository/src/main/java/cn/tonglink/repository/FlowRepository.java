/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.repository;

import cn.tonglink.model.entity.Flow;
import cn.tonglink.model.entity.FlowVersion;
import cn.tonglink.model.entity.NodePackage;
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
        SqliteSchemaMigration.ensure(jdbc);
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
