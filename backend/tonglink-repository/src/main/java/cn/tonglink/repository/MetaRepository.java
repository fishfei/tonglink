/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.repository;

import cn.tonglink.model.entity.Credential;
import cn.tonglink.model.entity.DataSource;
import cn.tonglink.model.entity.Project;
import cn.tonglink.model.entity.Variable;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 配置类元数据持久化：项目、数据源、凭据、变量。
 *
 * <p>这四类数据的共同点是小、读多写少、需要频繁按 ID 查询，因此各自提供
 * 「列表 / 单查 / 新增 / 更新 / 删除」五个方法，不做通用泛型抽象，保持可读性。</p>
 */
@Repository
public class MetaRepository {

    private static final RowMapper<Project> PROJECT = (rs, i) -> new Project(
            rs.getString("id"), rs.getString("name"), rs.getString("code"), rs.getString("color"),
            rs.getString("description"), rs.getString("created_at"), rs.getString("updated_at"));

    private static final RowMapper<DataSource> SOURCE = (rs, i) -> new DataSource(
            rs.getString("id"), rs.getString("name"), rs.getString("category"), rs.getString("protocol"),
            rs.getString("config_json"), rs.getString("status"), rs.getString("last_test"),
            rs.getString("created_at"), rs.getString("updated_at"));

    private static final RowMapper<Credential> CREDENTIAL = (rs, i) -> new Credential(
            rs.getString("id"), rs.getString("name"), rs.getString("type"), rs.getString("scope"),
            rs.getString("value_cipher"), rs.getString("mask"), rs.getString("description"),
            rs.getString("created_at"), rs.getString("updated_at"));

    private static final RowMapper<Variable> VARIABLE = (rs, i) -> new Variable(
            rs.getString("id"), rs.getString("name"), rs.getString("key_name"), rs.getString("env"),
            rs.getString("value_cipher"), rs.getString("scope"), rs.getString("description"),
            rs.getString("created_at"), rs.getString("updated_at"));

    private final JdbcTemplate jdbc;

    public MetaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 统一的「当前时间」来源，秒级精度，便于前端直接展示与排序。 */
    public String now() {
        return LocalDateTime.now().withNano(0).toString();
    }

    // ---------------- 项目 ----------------

    public List<Project> projects() {
        return jdbc.query("SELECT * FROM project ORDER BY created_at DESC", PROJECT);
    }

    public Project project(String id) {
        List<Project> rows = jdbc.query("SELECT * FROM project WHERE id=?", PROJECT, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void saveProject(Project p) {
        jdbc.update("INSERT INTO project(id,name,code,color,description,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                p.id(), p.name(), p.code(), p.color(), p.description(), p.createdAt(), p.updatedAt());
    }

    public void updateProject(Project p) {
        jdbc.update("UPDATE project SET name=?,code=?,color=?,description=?,updated_at=? WHERE id=?",
                p.name(), p.code(), p.color(), p.description(), p.updatedAt(), p.id());
    }

    public void deleteProject(String id) {
        jdbc.update("DELETE FROM project WHERE id=?", id);
    }

    // ---------------- 数据源 ----------------

    public List<DataSource> sources() {
        return jdbc.query("SELECT * FROM datasource ORDER BY created_at DESC", SOURCE);
    }

    public DataSource source(String id) {
        List<DataSource> rows = jdbc.query("SELECT * FROM datasource WHERE id=?", SOURCE, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void saveSource(DataSource s) {
        jdbc.update("""
                INSERT INTO datasource(id,name,category,protocol,config_json,status,last_test,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, s.id(), s.name(), s.category(), s.protocol(), s.configJson(), s.status(),
                s.lastTest(), s.createdAt(), s.updatedAt());
    }

    public void updateSource(DataSource s) {
        jdbc.update("""
                UPDATE datasource SET name=?,category=?,protocol=?,config_json=?,status=?,last_test=?,updated_at=?
                WHERE id=?
                """, s.name(), s.category(), s.protocol(), s.configJson(), s.status(), s.lastTest(),
                s.updatedAt(), s.id());
    }

    public void deleteSource(String id) {
        jdbc.update("DELETE FROM datasource WHERE id=?", id);
    }

    // ---------------- 凭据 ----------------

    public List<Credential> credentials() {
        return jdbc.query("SELECT * FROM credential ORDER BY created_at DESC", CREDENTIAL);
    }

    public Credential credential(String id) {
        List<Credential> rows = jdbc.query("SELECT * FROM credential WHERE id=?", CREDENTIAL, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void saveCredential(Credential c) {
        jdbc.update("""
                INSERT INTO credential(id,name,type,scope,value_cipher,mask,description,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, c.id(), c.name(), c.type(), c.scope(), c.valueCipher(), c.mask(),
                c.description(), c.createdAt(), c.updatedAt());
    }

    public void updateCredential(Credential c) {
        jdbc.update("""
                UPDATE credential SET name=?,type=?,scope=?,value_cipher=?,mask=?,description=?,updated_at=?
                WHERE id=?
                """, c.name(), c.type(), c.scope(), c.valueCipher(), c.mask(), c.description(),
                c.updatedAt(), c.id());
    }

    public void deleteCredential(String id) {
        jdbc.update("DELETE FROM credential WHERE id=?", id);
    }

    // ---------------- 变量 ----------------

    public List<Variable> variables() {
        return jdbc.query("SELECT * FROM variable ORDER BY created_at DESC", VARIABLE);
    }

    public Variable variable(String id) {
        List<Variable> rows = jdbc.query("SELECT * FROM variable WHERE id=?", VARIABLE, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void saveVariable(Variable v) {
        jdbc.update("""
                INSERT INTO variable(id,name,key_name,env,value_cipher,scope,description,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, v.id(), v.name(), v.key(), v.env(), v.valueCipher(), v.scope(),
                v.description(), v.createdAt(), v.updatedAt());
    }

    public void updateVariable(Variable v) {
        jdbc.update("""
                UPDATE variable SET name=?,key_name=?,env=?,value_cipher=?,scope=?,description=?,updated_at=?
                WHERE id=?
                """, v.name(), v.key(), v.env(), v.valueCipher(), v.scope(), v.description(),
                v.updatedAt(), v.id());
    }

    public void deleteVariable(String id) {
        jdbc.update("DELETE FROM variable WHERE id=?", id);
    }
}
