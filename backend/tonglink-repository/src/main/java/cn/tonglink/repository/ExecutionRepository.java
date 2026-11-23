/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.repository;

import cn.tonglink.common.IdUtil;
import cn.tonglink.common.JsonUtil;
import cn.tonglink.model.entity.Execution;
import cn.tonglink.model.entity.ExecutionLog;
import cn.tonglink.model.entity.ExecutionNode;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 执行相关持久化：执行记录、节点时间线、日志、采集指标。
 *
 * <p>使用 JdbcTemplate 手写 SQL 而非 JPA：这些表写入频繁、结构扁平，
 * 手写 SQL 更可控，也避免为 SQLite / PostgreSQL 维护两套方言映射。
 * 所有方法只做单表操作，事务边界由上层 {@code ExecutionService} 决定。</p>
 */
@Repository
public class ExecutionRepository {

    private static final RowMapper<Execution> EXECUTION = (rs, i) -> new Execution(
            rs.getString("id"),
            rs.getString("flow_id"),
            rs.getInt("flow_version"),
            rs.getString("trigger_type"),
            rs.getString("status"),
            rs.getString("start_time"),
            rs.getString("end_time"),
            rs.getLong("records"),
            rs.getString("error_summary"),
            rs.getString("idempotency_key"));

    private static final RowMapper<ExecutionNode> EXECUTION_NODE = (rs, i) -> new ExecutionNode(
            rs.getString("id"),
            rs.getString("execution_id"),
            rs.getString("node_id"),
            nullToEmpty(rs.getString("node_title")),
            rs.getString("node_type"),
            rs.getString("node_version"),
            rs.getString("status"),
            rs.getLong("duration_ms"),
            rs.getString("input_json"),
            rs.getString("output_json"),
            rs.getString("error_message"),
            rs.getInt("retry_count"));

    private static final RowMapper<ExecutionLog> EXECUTION_LOG = (rs, i) -> new ExecutionLog(
            rs.getString("id"),
            rs.getString("execution_id"),
            rs.getString("node_id"),
            rs.getLong("seq"),
            rs.getString("level"),
            rs.getString("message"),
            rs.getString("ts"));

    private final JdbcTemplate jdbc;

    public ExecutionRepository(JdbcTemplate jdbc) {
        SqliteSchemaMigration.ensure(jdbc);
        this.jdbc = jdbc;
    }

    /** 新建执行记录（入队时状态为 PENDING）。 */
    public void saveExecution(Execution e) {
        jdbc.update("""
                INSERT INTO execution(id,flow_id,flow_version,trigger_type,status,start_time,end_time,
                                      records,error_summary,idempotency_key)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, e.id(), e.flowId(), e.flowVersion(), e.triggerType(), e.status(), e.startTime(),
                e.endTime(), e.records(), e.errorSummary(), e.idempotencyKey());
    }

    /** 更新状态、结束时间、条数与失败摘要（其余字段不可变）。 */
    public void updateExecution(Execution e) {
        jdbc.update("UPDATE execution SET status=?,end_time=?,records=?,error_summary=? WHERE id=?",
                e.status(), e.endTime(), e.records(), e.errorSummary(), e.id());
    }

    /**
     * 查询执行列表，按开始时间倒序。
     *
     * @param flowId 可选，按流程过滤
     * @param status 可选，按状态过滤
     * @param limit  最多返回条数
     */
    public List<Execution> executions(String flowId, String status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM execution WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (flowId != null && !flowId.isBlank()) {
            sql.append(" AND flow_id=?");
            args.add(flowId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            args.add(status);
        }
        sql.append(" ORDER BY start_time DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), EXECUTION, args.toArray());
    }

    /**
     * 按条件统计执行记录总数，供分页控件计算总页数。
     */
    public long count(String flowId, String status) {
        return count(flowId, status, null);
    }

    /** 按条件统计执行记录总数，支持按 tag 过滤。 */
    public long count(String flowId, String status, String tag) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(1) FROM execution WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (flowId != null && !flowId.isBlank()) {
            sql.append(" AND flow_id=?");
            args.add(flowId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            args.add(status);
        }
        appendTagFilter(sql, args, tag);
        Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return n == null ? 0L : n;
    }

    /**
     * 分页查询执行记录，按开始时间倒序。
     *
     * @param page 从 1 开始
     * @param size 每页条数
     */
    public List<Execution> executionsPage(String flowId, String status, int page, int size) {
        return executionsPage(flowId, status, page, size, null);
    }

    /** 分页查询执行记录，支持按 tag 过滤。 */
    public List<Execution> executionsPage(String flowId, String status, int page, int size, String tag) {
        StringBuilder sql = new StringBuilder("SELECT * FROM execution WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (flowId != null && !flowId.isBlank()) {
            sql.append(" AND flow_id=?");
            args.add(flowId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            args.add(status);
        }
        appendTagFilter(sql, args, tag);
        sql.append(" ORDER BY start_time DESC LIMIT ? OFFSET ?");
        args.add(size);
        args.add(Math.max(0, page - 1) * size);
        return jdbc.query(sql.toString(), EXECUTION, args.toArray());
    }

    private static void appendTagFilter(StringBuilder sql, List<Object> args, String tag) {
        if (tag != null && !tag.isBlank()) {
            sql.append(" AND tags LIKE ?");
            args.add("%\"" + tag.trim() + "\"%");
        }
    }

    /** 读取一条执行记录的标签。 */
    public List<String> tags(String executionId) {
        String raw = jdbc.queryForObject("SELECT tags FROM execution WHERE id=?", String.class, executionId);
        if (raw == null || raw.isBlank()) return List.of();
        try {
            List<String> parsed = JsonUtil.parse(raw, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 覆盖一条执行记录的标签（去重、去空）。 */
    public void setTags(String executionId, List<String> tags) {
        List<String> clean = tags == null ? List.of() : tags.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim).distinct().toList();
        jdbc.update("UPDATE execution SET tags=? WHERE id=?", JsonUtil.stringify(clean), executionId);
    }

    /** 查询单条执行记录，不存在返回 null。 */
    public Execution execution(String id) {
        List<Execution> rows = jdbc.query("SELECT * FROM execution WHERE id=?", EXECUTION, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 保存节点级执行结果。 */
    public void saveNode(ExecutionNode n) {
        jdbc.update("""
                INSERT INTO execution_node(id,execution_id,node_id,node_title,node_type,node_version,status,
                                          duration_ms,input_json,output_json,error_message,retry_count)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, n.id(), n.executionId(), n.nodeId(), n.nodeTitle(), n.nodeType(), n.nodeVersion(),
                n.status(), n.durationMs(), n.inputJson(), n.outputJson(), n.errorMessage(), n.retryCount());
    }

    /** 查询某次执行的节点时间线。 */
    public List<ExecutionNode> nodes(String executionId) {
        return jdbc.query("SELECT * FROM execution_node WHERE execution_id=? ORDER BY id",
                EXECUTION_NODE, executionId);
    }

    /** 追加一条日志。 */
    public void saveLog(ExecutionLog log) {
        jdbc.update("INSERT INTO execution_log(id,execution_id,node_id,seq,level,message,ts) VALUES(?,?,?,?,?,?,?)",
                log.id(), log.executionId(), log.nodeId(), log.seq(), log.level(), log.message(), log.ts());
    }

    /**
     * 增量读取日志，供 WebSocket 断线重连后补齐。
     *
     * @param after 只返回 seq 大于该值的记录
     */
    public List<ExecutionLog> logs(String executionId, long after, int limit) {
        return jdbc.query("SELECT * FROM execution_log WHERE execution_id=? AND seq>? ORDER BY seq LIMIT ?",
                EXECUTION_LOG, executionId, after, limit);
    }

    /** 取当前最大序号，新日志在其基础上 +1，保证严格递增。 */
    public long maxSeq(String executionId) {
        Long n = jdbc.queryForObject("SELECT COALESCE(MAX(seq),0) FROM execution_log WHERE execution_id=?",
                Long.class, executionId);
        return n == null ? 0L : n;
    }

    /** 写入一条采集指标样本，看板趋势与异常检测的基础数据。 */
    public void saveMetric(String ts, String flowId, String nodeId, String tags, String values) {
        jdbc.update("INSERT INTO metric_point(id,ts,flow_id,node_id,tags,values_json) VALUES(?,?,?,?,?,?)",
                IdUtil.next(), ts, flowId, nodeId, tags, values);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
