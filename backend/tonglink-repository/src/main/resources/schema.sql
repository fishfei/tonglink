-- TongLink Studio 初始化表结构
-- 本文件同时兼容 SQLite（轻量模式）与 PostgreSQL（标准/队列模式）：
-- 使用通用类型 TEXT / VARCHAR / INTEGER / BIGINT，时间统一保存为 ISO-8601 字符串。

CREATE TABLE IF NOT EXISTS sys_user (
  id VARCHAR(64) PRIMARY KEY,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(200) NOT NULL,
  role VARCHAR(32) NOT NULL,
  display_name VARCHAR(100),
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT
);

CREATE TABLE IF NOT EXISTS project (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  code VARCHAR(64),
  color VARCHAR(32),
  description TEXT,
  created_at TEXT,
  updated_at TEXT
);

CREATE TABLE IF NOT EXISTS datasource (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  category VARCHAR(16),
  protocol VARCHAR(64) NOT NULL,
  config_json TEXT,
  status VARCHAR(32),
  last_test TEXT,
  created_at TEXT,
  updated_at TEXT
);

CREATE TABLE IF NOT EXISTS credential (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  type VARCHAR(64),
  scope VARCHAR(64),
  value_cipher TEXT,
  mask TEXT,
  description TEXT,
  created_at TEXT,
  updated_at TEXT
);

CREATE TABLE IF NOT EXISTS variable (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(200),
  key_name VARCHAR(200),
  env VARCHAR(64),
  value_cipher TEXT,
  scope VARCHAR(64),
  description TEXT,
  created_at TEXT,
  updated_at TEXT
);

CREATE TABLE IF NOT EXISTS flow_definition (
  id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64),
  name VARCHAR(200) NOT NULL,
  description TEXT,
  status VARCHAR(32),
  graph_json TEXT,
  published_graph_json TEXT,
  published_version INTEGER DEFAULT 0,
  next_run TEXT,
  last_run TEXT,
  records BIGINT DEFAULT 0,
  success_rate VARCHAR(32),
  created_at TEXT,
  updated_at TEXT
);

CREATE TABLE IF NOT EXISTS flow_version (
  id VARCHAR(64) PRIMARY KEY,
  flow_id VARCHAR(64) NOT NULL,
  version INTEGER NOT NULL,
  graph_json TEXT,
  changelog TEXT,
  created_at TEXT
);

CREATE TABLE IF NOT EXISTS node_package (
  id VARCHAR(64) PRIMARY KEY,
  package_id VARCHAR(128) NOT NULL,
  version VARCHAR(64),
  manifest_json TEXT,
  status VARCHAR(32),
  installed_at TEXT
);

CREATE TABLE IF NOT EXISTS execution (
  id VARCHAR(64) PRIMARY KEY,
  flow_id VARCHAR(64) NOT NULL,
  flow_version INTEGER,
  trigger_type VARCHAR(32),
  status VARCHAR(32),
  start_time TEXT,
  end_time TEXT,
  records BIGINT DEFAULT 0,
  error_summary TEXT,
  idempotency_key VARCHAR(200),
  tags TEXT
);

CREATE TABLE IF NOT EXISTS execution_node (
  id VARCHAR(64) PRIMARY KEY,
  execution_id VARCHAR(64) NOT NULL,
  node_id VARCHAR(64),
  node_title VARCHAR(256),
  node_type VARCHAR(64),
  node_version VARCHAR(32),
  status VARCHAR(32),
  duration_ms BIGINT DEFAULT 0,
  input_json TEXT,
  output_json TEXT,
  error_message TEXT,
  retry_count INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS execution_log (
  id VARCHAR(64) PRIMARY KEY,
  execution_id VARCHAR(64) NOT NULL,
  node_id VARCHAR(64),
  seq BIGINT NOT NULL,
  level VARCHAR(16),
  message TEXT,
  ts TEXT
);

CREATE TABLE IF NOT EXISTS metric_point (
  id VARCHAR(64) PRIMARY KEY,
  ts TEXT,
  flow_id VARCHAR(64),
  node_id VARCHAR(64),
  tags TEXT,
  values_json TEXT
);

CREATE TABLE IF NOT EXISTS ai_conversation (
  id VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(64),
  title TEXT,
  created_at TEXT
);

CREATE TABLE IF NOT EXISTS ai_message (
  id VARCHAR(64) PRIMARY KEY,
  conversation_id VARCHAR(64),
  role VARCHAR(16),
  content_json TEXT,
  created_at TEXT
);

CREATE TABLE IF NOT EXISTS audit_log (
  id VARCHAR(64) PRIMARY KEY,
  actor VARCHAR(64),
  action VARCHAR(128),
  target VARCHAR(128),
  ip VARCHAR(64),
  ts TEXT
);

CREATE INDEX IF NOT EXISTS idx_flow_project ON flow_definition(project_id);
CREATE INDEX IF NOT EXISTS idx_exec_flow ON execution(flow_id);
CREATE INDEX IF NOT EXISTS idx_exec_time ON execution(start_time);
CREATE INDEX IF NOT EXISTS idx_execnode_exec ON execution_node(execution_id);
CREATE INDEX IF NOT EXISTS idx_log_exec_seq ON execution_log(execution_id, seq);
CREATE INDEX IF NOT EXISTS idx_metric_time ON metric_point(ts);
