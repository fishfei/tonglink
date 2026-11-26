/* 模拟数据：全部为静态内容，不发起任何网络请求 */
(function () {
  window.Proto = window.Proto || {};

  Proto.clone = function (obj) { return JSON.parse(JSON.stringify(obj)); };
  function code(lines) { return lines.join('\n'); }

  /* 节点类型目录（用于左侧面板拖拽新增） */
  Proto.NODE_CATALOG = [
    { group: '触发器', items: [
      { type: 'trigger', subtype: 'schedule', title: '定时触发', icon: 'clock', color: 'orange' },
      { type: 'trigger', subtype: 'manual', title: '手动触发', icon: 'play', color: 'orange' }
    ] },
    { group: '数据源', items: [
      { type: 'source', subtype: 'opcua', title: 'OPC UA 读取', icon: 'cpu', color: 'teal' },
      { type: 'source', subtype: 'modbus', title: 'Modbus 读取', icon: 'cpu', color: 'teal' },
      { type: 'source', subtype: 'mqtt', title: 'MQTT 订阅', icon: 'cloud', color: 'teal' },
      { type: 'source', subtype: 's7comm', title: 'S7comm 读取', icon: 'cpu', color: 'teal' },
      { type: 'source', subtype: 'http', title: 'HTTP 请求', icon: 'api', color: 'blue' },
      { type: 'source', subtype: 'kafka', title: 'Kafka 消费', icon: 'layers', color: 'blue' }
    ] },
    { group: '数据库（支持增删改查）', items: [
      { type: 'source', subtype: 'mysql', title: 'MySQL 查询', icon: 'database', color: 'blue' },
      { type: 'source', subtype: 'oracle', title: 'Oracle 查询', icon: 'database', color: 'blue' },
      { type: 'source', subtype: 'postgres', title: 'PostgreSQL 查询', icon: 'database', color: 'blue' },
      { type: 'source', subtype: 'sqlserver', title: 'SQL Server 查询', icon: 'database', color: 'blue' },
      { type: 'source', subtype: 'mongodb', title: 'MongoDB 查询', icon: 'database', color: 'blue' }
    ] },
    { group: '代码与逻辑', items: [
      { type: 'code', subtype: 'nodejs', title: 'Node 代码', icon: 'code', color: 'purple' },
      { type: 'code', subtype: 'python', title: 'Python 代码', icon: 'code', color: 'purple' },
      { type: 'logic', subtype: 'condition', title: '条件分支', icon: 'branch', color: 'orange' },
      { type: 'logic', subtype: 'filter', title: '数据过滤', icon: 'filter', color: 'orange' },
      { type: 'logic', subtype: 'merge', title: '数据合并', icon: 'layers', color: 'orange' },
      { type: 'logic', subtype: 'delay', title: '延时等待', icon: 'clock', color: 'orange' }
    ] },
    { group: '输出', items: [
      { type: 'output', subtype: 'timeseries', title: '写入时序库', icon: 'database', color: 'green' },
      { type: 'output', subtype: 'http', title: 'HTTP 推送', icon: 'webhook', color: 'green' },
      { type: 'output', subtype: 'feishu_bitable', title: '飞书多维表格', icon: 'table', color: 'green' },
      { type: 'output', subtype: 'alert', title: '告警通知', icon: 'alert', color: 'green' }
    ] }
  ];

  /* 可独立安装的节点包（原型内置一个模拟市场） */
  Proto.NODE_MARKET = [
    { key: 'pkg-redis', type: 'output', subtype: 'redis', title: 'Redis 写入', icon: 'layers', color: 'green',
      desc: '将采集结果写入 Redis List / Stream', author: 'TongLink 官方', version: '1.2.0',
      schema: { title: 'Redis 写入', note: '安装后即可在节点库使用；凭据请选择已配置的 Redis 密码。', fields: [
        { key: 'credential', label: 'Redis 凭据', type: 'credential', required: true },
        { key: 'keyPattern', label: 'Key 模板', type: 'text', placeholder: 'plant:{tag}:{date}', required: true },
        { key: 'dataType', label: '写入结构', type: 'select', options: ['List', 'Stream', 'String'], default: 'List' },
        { key: 'ttl', label: '过期时间（秒）', type: 'number', placeholder: '3600' }
      ] } },
    { key: 'pkg-influxdb', type: 'output', subtype: 'influxdb', title: 'InfluxDB 写入', icon: 'database', color: 'green',
      desc: '按measurement与tag写入时序数据', author: '社区贡献 · factory-io', version: '0.9.3',
      schema: { title: 'InfluxDB 写入', note: '支持行协议批量写入，需选择已建数据源。', fields: [
        { key: 'datasource', label: '数据源', type: 'datasource', required: true },
        { key: 'measurement', label: 'Measurement', type: 'text', placeholder: 'telemetry', required: true },
        { key: 'tags', label: 'Tag 字段', type: 'text', placeholder: 'device_id, tag' },
        { key: 'batchSize', label: '批量大小', type: 'number', default: '500' }
      ] } },
    { key: 'pkg-wecom', type: 'output', subtype: 'wecom', title: '企业微信机器人', icon: 'webhook', color: 'green',
      desc: '推送文本/Markdown 消息到群机器人', author: '社区贡献 · ops-lab', version: '1.0.1',
      schema: { title: '企业微信机器人', note: 'Webhook 地址建议存入凭据后下拉选择。', fields: [
        { key: 'credential', label: '机器人凭据', type: 'credential', required: true },
        { key: 'msgType', label: '消息类型', type: 'select', options: ['text', 'markdown'], default: 'markdown' },
        { key: 'content', label: '消息模板', type: 'textarea', rows: 3, placeholder: '设备 {{ $json.device }} 告警' }
      ] } }
  ];

  /* 节点类型配色 */
  Proto.NODE_THEME = {
    trigger: { color: '#D97706', soft: '#FEF3C7', icon: 'clock' },
    source:  { color: '#0F766E', soft: '#CCFBF1', icon: 'cpu' },
    code:    { color: '#7C3AED', soft: '#F3E8FF', icon: 'code' },
    logic:   { color: '#D97706', soft: '#FEF3C7', icon: 'branch' },
    output:  { color: '#059669', soft: '#D1FAE5', icon: 'database' }
  };

  Proto.DATA = {};

  /* 每个节点的属性定义：配置抽屉据此渲染，保证字段合理且可维护 */
  function f(key, label, type, extra) {
    return Object.assign({ key: key, label: label, type: type || 'text' }, extra || {});
  }
  function dbSchema(engine, extraHint) {
    return {
      title: engine + ' 数据库',
      note: '支持增删改查；字段按需显示。连接建议引用凭据，不要在流程中明文写密码。' + (extraHint || ''),
      fields: [
        f('connection', '连接数据源', 'datasource', { required: true, hint: '从已创建的数据源中选择，无需手填连接串' }),
        f('operation', '操作类型', 'select', { options: ['select', 'insert', 'update', 'delete', 'execute'], default: 'select', required: true, hint: 'SELECT / INSERT / UPDATE / DELETE / 执行原始 SQL' }),
        f('table', '数据表', 'text', { placeholder: 'schema.table', required: true, showWhen: { key: 'operation', in: ['select', 'insert', 'update', 'delete'] } }),
        f('columns', '字段列表', 'text', { placeholder: 'id, order_no, updated_at', hint: 'SELECT / INSERT 使用', showWhen: { key: 'operation', in: ['select', 'insert'] } }),
        f('where', 'WHERE 条件', 'text', { placeholder: 'synced = 0 AND updated_at > {{ $now.minus(5, "minutes") }}', hint: '参数可用 {{ $vars.xxx }} 引用', showWhen: { key: 'operation', in: ['select', 'update', 'delete'] } }),
        f('values', '写入值 (JSON)', 'textarea', { rows: 4, placeholder: '{"order_no": "SO-2026-001"}', hint: 'INSERT 的字段值 / UPDATE 的 SET 值', showWhen: { key: 'operation', in: ['insert', 'update'] } }),
        f('sql', '自定义 SQL', 'textarea', { rows: 5, placeholder: 'SELECT ... FROM ... WHERE ...', hint: '仅“执行原始 SQL”时启用', showWhen: { key: 'operation', in: ['execute'] } }),
        f('limit', '最大返回行数', 'number', { placeholder: '500', default: '500', showWhen: { key: 'operation', in: ['select'] } }),
        f('conflict', '冲突处理', 'text', { placeholder: 'ON CONFLICT (id) DO NOTHING', hint: '可选，留空则使用默认策略', showWhen: { key: 'operation', in: ['insert'] } }),
        f('timeout', '超时时间', 'text', { placeholder: '30000ms', default: '30000ms' })
      ]
    };
  }

  Proto.NODE_SCHEMAS = {
    'trigger:schedule': { title: '定时触发器', note: '常用间隔可直接选择；复杂调度再切换到 Cron 表达式（秒 分 时 日 月 周）。', fields: [
      f('mode', '调度方式', 'select', { options: ['minutes', 'seconds', 'cron'], default: 'minutes', required: true,
        labels: { minutes: '每隔 N 分钟', seconds: '每隔 N 秒', cron: 'Cron 表达式（高级）' }, hint: '简单周期用间隔，复杂规则用 Cron' }),
      f('everyMinutes', '间隔（分钟）', 'number', { placeholder: '5', default: '5', required: true, showWhen: { key: 'mode', in: ['minutes'] }, hint: '每 5 分钟即从整点起 00:00、00:05、00:10… 执行' }),
      f('everySeconds', '间隔（秒）', 'number', { placeholder: '30', default: '30', required: true, showWhen: { key: 'mode', in: ['seconds'] }, hint: '秒级调度请确保单次执行时长小于间隔' }),
      f('cron', 'Cron 表达式', 'text', { placeholder: '0/5 * * * * ?', required: true, showWhen: { key: 'mode', in: ['cron'] }, hint: 'Spring Cron：秒 分 时 日 月 周，例如 0 0 8 * * ? 表示每天 08:00' }),
      f('timezone', '时区', 'text', { placeholder: 'Asia/Shanghai', default: 'Asia/Shanghai' }),
      f('enabled', '启用调度', 'select', { options: ['是', '否'], default: '是' }),
      f('catchUp', '错过执行补偿', 'select', { options: ['跳过', '立即补跑'], default: '跳过', hint: '服务重启后对遗漏执行点的处理策略' }),
      f('desc', '说明', 'text', { placeholder: '每 5 分钟采集一次' })
    ] },
    'trigger:manual': { title: '手动触发器', note: '由用户在画布点击“运行”或调用 API 触发。', fields: [
      f('allowParams', '允许运行参数', 'select', { options: ['是', '否'], default: '否' }),
      f('params', '参数模板 (JSON)', 'textarea', { rows: 3, placeholder: '{"startDate": "2026-11-21"}', showWhen: { key: 'allowParams', in: ['是'] } }),
      f('desc', '说明', 'text', { placeholder: '按需手动执行' })
    ] },

    'source:opcua': { title: 'OPC UA 读取', note: '采集前请确认服务端证书信任与节点访问权限。', fields: [
      f('datasource', '数据源', 'datasource', { required: true, hint: '从数据源管理中选择已配置的 OPC UA 连接' }),
      f('nodeId', '节点 ID', 'text', { placeholder: 'ns=3;s=Temperature.PT101', required: true }),
      f('readMode', '读取方式', 'select', { options: ['订阅 (Subscription)', '轮询 (Poll)'], default: '订阅 (Subscription)' }),
      f('interval', '采集间隔', 'text', { placeholder: '1000ms', default: '1000ms' }),
      f('qualityFilter', '质量戳过滤', 'select', { options: ['仅 GOOD', '不过滤'], default: '仅 GOOD' }),
      f('timeout', '超时时间', 'text', { placeholder: '5000ms', default: '5000ms' })
    ] },
    'source:modbus': { title: 'Modbus 读取', note: '寄存器地址从 40001 起算时，请在工具中确认与协议地址的偏移关系。', fields: [
      f('datasource', '数据源', 'datasource', { required: true, hint: '从数据源管理中选择已配置的 Modbus TCP 连接' }),
      f('port', '端口', 'number', { placeholder: '502', default: '502' }),
      f('slaveId', '从站号', 'number', { placeholder: '1', default: '1' }),
      f('registerType', '寄存器类型', 'select', { options: ['保持寄存器', '输入寄存器', '线圈', '离散输入'], default: '保持寄存器' }),
      f('startAddress', '起始地址', 'text', { placeholder: '40001', required: true }),
      f('count', '读取数量', 'number', { placeholder: '10', default: '10' }),
      f('byteOrder', '字节序', 'select', { options: ['ABCD', 'CDAB', 'BADC', 'DCBA'], default: 'ABCD' }),
      f('pollInterval', '轮询间隔', 'text', { placeholder: '500ms', default: '500ms' })
    ] },
    'source:mqtt': { title: 'MQTT 订阅', note: '支持通配符主题；生产环境建议启用认证并使用 TLS。', fields: [
      f('datasource', '数据源', 'datasource', { required: true, hint: '从数据源管理中选择已配置的 MQTT Broker' }),
      f('topic', '订阅主题', 'text', { placeholder: 'plant/+/temp', required: true }),
      f('qos', 'QoS', 'select', { options: ['0', '1', '2'], default: '1' }),
      f('clientId', 'Client ID', 'text', { placeholder: '留空自动生成' }),
      f('payloadFormat', '消息格式', 'select', { options: ['JSON', '原始字符串'], default: 'JSON' }),
      f('cleanSession', 'Clean Session', 'select', { options: ['是', '否'], default: '是' }),
      f('username', '用户名凭据', 'credential', { hint: '从变量配置 → 凭据中选择' }),
      f('password', '密码凭据', 'credential', { hint: '从变量配置 → 凭据中选择' })
    ] },
    'source:s7comm': { title: 'S7comm 读取', note: '面向西门子 S7-300/400/1200/1500 PLC 的数据块读取。', fields: [
      f('datasource', '数据源', 'datasource', { required: true, hint: '从数据源管理中选择已配置的 S7comm 连接' }),
      f('rack', '机架号', 'number', { placeholder: '0', default: '0' }),
      f('slot', '槽号', 'number', { placeholder: '1', default: '1' }),
      f('dbNumber', 'DB 块号', 'number', { placeholder: '10', required: true }),
      f('offset', '起始偏移（字节）', 'number', { placeholder: '0', default: '0' }),
      f('length', '读取长度（字节）', 'number', { placeholder: '32', default: '32' }),
      f('pollInterval', '轮询间隔', 'text', { placeholder: '2000ms', default: '2000ms' })
    ] },
    'source:http': { title: 'HTTP 请求', note: '请求头与查询参数使用 JSON 格式，敏感值请引用凭据。', fields: [
      f('method', '请求方法', 'select', { options: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'], default: 'GET' }),
      f('url', '请求地址', 'text', { placeholder: 'https://mes.factory.local/api/v1/orders', required: true }),
      f('query', '查询参数 (JSON)', 'textarea', { rows: 3, placeholder: '{"date": "today"}' }),
      f('headers', '请求头 (JSON)', 'textarea', { rows: 3, placeholder: '{"Content-Type": "application/json"}' }),
      f('auth', '认证凭据', 'credential', { hint: '从变量配置 → 凭据中选择，例如 MES_API_TOKEN' }),
      f('expectedStatus', '期望状态码', 'text', { placeholder: '200', default: '200' }),
      f('timeout', '超时时间', 'text', { placeholder: '10000ms', default: '10000ms' })
    ] },
    'source:kafka': { title: 'Kafka 消费', note: '消费位点与分组提交策略会影响数据重复性。', fields: [
      f('datasource', '数据源', 'datasource', { required: true, hint: '从数据源管理中选择已配置的 Kafka 连接' }),
      f('topic', 'Topic', 'text', { placeholder: 'factory.events', required: true }),
      f('groupId', 'Consumer Group', 'text', { placeholder: 'dfs-collector', required: true }),
      f('offsetReset', '位点策略', 'select', { options: ['earliest', 'latest'], default: 'latest' }),
      f('deserializer', '反序列化', 'select', { options: ['JSON', 'String'], default: 'JSON' }),
      f('batchSize', '批量大小', 'number', { placeholder: '100', default: '100' }),
      f('autoCommit', '自动提交位点', 'select', { options: ['是', '否'], default: '否' })
    ] },

    'source:mysql': dbSchema('MySQL'),
    'source:oracle': dbSchema('Oracle', ' Oracle 表名通常为大写，注意引号与大小写。'),
    'source:postgres': dbSchema('PostgreSQL'),
    'source:sqlserver': dbSchema('SQL Server'),
    'source:mongodb': { title: 'MongoDB', note: '查询条件与更新内容使用 JSON 格式。', fields: [
      f('connection', '连接数据源', 'datasource', { required: true, hint: '从数据源管理中选择已配置的 MongoDB 连接' }),
      f('operation', '操作类型', 'select', { options: ['find', 'insertOne', 'insertMany', 'updateMany', 'deleteMany', 'aggregate'], default: 'find' }),
      f('collection', '集合', 'text', { placeholder: 'plant.events', required: true }),
      f('filter', '查询条件 (JSON)', 'textarea', { rows: 3, placeholder: '{"status": "new"}' }),
      f('documents', '写入文档 (JSON)', 'textarea', { rows: 4, placeholder: '[{"name": "PT101"}]', showWhen: { key: 'operation', in: ['insertOne', 'insertMany'] } }),
      f('update', '更新内容 (JSON)', 'textarea', { rows: 3, placeholder: '{"$set": {"status": "done"}}', showWhen: { key: 'operation', in: ['updateMany'] } }),
      f('pipeline', '聚合管道 (JSON)', 'textarea', { rows: 4, placeholder: '[{"$match": {}}]', showWhen: { key: 'operation', in: ['aggregate'] } }),
      f('limit', '最大返回条数', 'number', { placeholder: '100', default: '100' }),
      f('timeout', '超时时间', 'text', { placeholder: '30000ms', default: '30000ms' })
    ] },

    'code:nodejs': { title: 'Node 代码', note: '生产环境在独立进程/容器中执行，配置模块白名单与资源上限。可用 $input / $json / $vars / $env。', fields: [
      f('code', '脚本', 'code', { required: true }),
      f('timeout', '任务超时', 'text', { placeholder: '300s', default: '300s' }),
      f('memoryLimit', '内存上限', 'select', { options: ['128MB', '256MB', '512MB'], default: '256MB' }),
      f('allowedModules', '允许模块（白名单）', 'text', { placeholder: 'lodash, dayjs', hint: '逗号分隔；内置模块也需显式授权' }),
      f('inputMode', '输入模式', 'select', { options: ['逐条执行', '整批执行'], default: '整批执行' }),
      f('retry', '失败重试次数', 'select', { options: ['0', '1', '2', '3'], default: '0' })
    ] },
    'code:python': { title: 'Python 代码', note: '生产环境在独立 Python 进程中执行；使用 _items 读取输入，返回列表作为输出。', fields: [
      f('code', '脚本', 'code', { required: true }),
      f('timeout', '任务超时', 'text', { placeholder: '300s', default: '300s' }),
      f('memoryLimit', '内存上限', 'select', { options: ['128MB', '256MB', '512MB'], default: '256MB' }),
      f('allowedModules', '允许第三方库', 'text', { placeholder: 'numpy, requests', hint: '逗号分隔；标准库白名单由服务端配置' }),
      f('inputMode', '输入模式', 'select', { options: ['逐条执行', '整批执行'], default: '整批执行' }),
      f('retry', '失败重试次数', 'select', { options: ['0', '1', '2', '3'], default: '0' })
    ] },

    'logic:condition': { title: '条件分支', note: '根据表达式结果将数据分流到 true / false 端口。', fields: [
      f('expression', '条件表达式', 'text', { placeholder: '{{ $json.temp > $vars.TEMP_THRESHOLD }}', required: true }),
      f('mode', '分支模式', 'select', { options: ['二分支 (true/false)', '多分支 (Switch)'], default: '二分支 (true/false)' }),
      f('unmatched', '未匹配处理', 'select', { options: ['丢弃', '走默认分支'], default: '丢弃' }),
      f('desc', '说明', 'text', { placeholder: '温度超阈值时进入告警分支' })
    ] },
    'logic:filter': { title: '数据过滤', note: '仅保留满足表达式的数据项。', fields: [
      f('expression', '过滤表达式', 'text', { placeholder: '{{ $json.quality === "GOOD" }}', required: true }),
      f('maxItems', '最大保留条数', 'number', { placeholder: '1000', default: '1000' }),
      f('desc', '说明', 'text', { placeholder: '过滤无效质量戳' })
    ] },
    'logic:merge': { title: '数据合并', note: '将多路输入合并为单一输出。', fields: [
      f('strategy', '合并策略', 'select', { options: ['追加', '按字段合并', '覆盖'], default: '追加' }),
      f('matchField', '匹配字段', 'text', { placeholder: 'device_id', showWhen: { key: 'strategy', in: ['按字段合并'] } }),
      f('dedupe', '去重', 'select', { options: ['是', '否'], default: '否' }),
      f('maxItems', '最大条数', 'number', { placeholder: '5000', default: '5000' })
    ] },
    'logic:delay': { title: '延时等待', note: '用于错峰写入或等待上游设备就绪。', fields: [
      f('duration', '延时时长', 'number', { placeholder: '5', required: true }),
      f('unit', '单位', 'select', { options: ['毫秒', '秒', '分钟'], default: '秒' }),
      f('blockDownstream', '阻塞下游', 'select', { options: ['是', '否'], default: '是' }),
      f('desc', '说明', 'text', { placeholder: '等待 PLC 数据就绪' })
    ] },

    'output:timeseries': { title: '写入时序库', note: '建议批量写入并指定时间字段与标签字段。', fields: [
      f('connection', '目标数据源', 'datasource', { required: true, hint: '从数据源管理中选择，不再手填 DS ID' }),
      f('table', '表 / 测量', 'text', { placeholder: 'plant_a.telemetry', required: true }),
      f('writeMode', '写入模式', 'select', { options: ['追加', '覆盖', 'UPSERT'], default: '追加' }),
      f('batchSize', '批量大小', 'number', { placeholder: '500', default: '500' }),
      f('timeField', '时间字段', 'text', { placeholder: 'ts', default: 'ts' }),
      f('tagFields', '标签字段', 'text', { placeholder: 'device_id, tag', hint: '逗号分隔' }),
      f('outOfOrder', '乱序数据处理', 'select', { options: ['丢弃', '缓冲重排'], default: '丢弃' }),
      f('timeout', '超时时间', 'text', { placeholder: '10000ms', default: '10000ms' })
    ] },
    'output:http': { title: 'HTTP 推送', note: '失败时按配置重试；请求体支持 {{ $json.xxx }} 模板变量。', fields: [
      f('method', '请求方法', 'select', { options: ['POST', 'PUT', 'PATCH', 'GET'], default: 'POST' }),
      f('url', '目标地址', 'text', { placeholder: 'https://mes.factory.local/api/v1/oee', required: true }),
      f('auth', '认证凭据', 'credential', { hint: '从变量配置 → 凭据中选择' }),
      f('bodyTemplate', '请求体模板', 'textarea', { rows: 4, placeholder: '{"value": "{{ $json.oee }}"}' }),
      f('retry', '失败重试次数', 'select', { options: ['0', '1', '2', '3'], default: '2' }),
      f('timeout', '超时时间', 'text', { placeholder: '10000ms', default: '10000ms' }),
      f('successCodes', '成功状态码', 'text', { placeholder: '200,201', default: '200,201' })
    ] },
    'output:feishu_bitable': { title: '飞书多维表格', note: '通过飞书开放平台「多维表格」API 写入记录；需先在变量配置中创建 App ID / App Secret 凭据。', fields: [
      f('credential', '飞书应用凭据', 'credential', { required: true, hint: '下拉选择已建的飞书应用凭据（App ID / App Secret）' }),
      f('appToken', '多维表格 App Token', 'text', { placeholder: 'bascnCMII2O...', required: true, hint: '打开多维表格后，URL 中 /base/ 后面的一段' }),
      f('tableId', '数据表 ID (table_id)', 'text', { placeholder: 'tblsRc9GRRXKqhvW', required: true, hint: '以 tbl 开头，可在表格 URL 或 API 中获取' }),
      f('operation', '操作类型', 'select', { options: ['新增记录', '更新记录', '查询记录', '删除记录'], default: '新增记录', required: true }),
      f('viewId', '视图 ID', 'text', { placeholder: 'vewXXXXXX（可选）', showWhen: { key: 'operation', in: ['查询记录'] } }),
      f('filter', '筛选条件', 'text', { placeholder: '当前温度>85', showWhen: { key: 'operation', in: ['查询记录'] } }),
      f('matchField', '匹配字段', 'text', { placeholder: '设备编号', hint: '按该字段定位要更新的记录', showWhen: { key: 'operation', in: ['更新记录', '删除记录'] } }),
      f('fieldMapping', '字段映射 (JSON)', 'textarea', { rows: 4, placeholder: '{"设备编号": "{{ $json.device }}", "温度": "{{ $json.temp }}"}', hint: '左侧为多维表格字段名，右侧可用 {{ $json.xxx }} 模板变量', required: true, showWhen: { key: 'operation', in: ['新增记录', '更新记录'] } }),
      f('batchSize', '批量大小', 'number', { placeholder: '100', default: '100', hint: '飞书接口单次上限 1000 条' }),
      f('retry', '失败重试次数', 'select', { options: ['0', '1', '2', '3'], default: '2' }),
      f('timeout', '超时时间', 'text', { placeholder: '10000ms', default: '10000ms' })
    ] },
    'output:alert': { title: '告警通知', note: '支持重复告警抑制，避免刷屏。', fields: [
      f('channel', '告警渠道', 'select', { options: ['企业微信', '钉钉', '邮件', 'Webhook'], default: '企业微信' }),
      f('credential', '通知凭据', 'credential', { hint: '可选，从变量配置 → 凭据中选择' }),
      f('webhook', 'Webhook / 收件人', 'text', { placeholder: 'https://qyapi.weixin.qq.com/... 或 ops@factory.local' }),
      f('titleTemplate', '标题模板', 'text', { placeholder: '【告警】{{ $json.device }} 温度过高' }),
      f('messageTemplate', '消息模板', 'textarea', { rows: 4, placeholder: '设备：{{ $json.device }}\n当前温度：{{ $json.temp }}℃' }),
      f('severity', '严重级别', 'select', { options: ['严重', '警告', '提醒'], default: '警告' }),
      f('suppressMinutes', '重复抑制（分钟）', 'number', { placeholder: '10', default: '10' })
    ] }
  };

  Proto.schemaFor = function (node) {
    return Proto.NODE_SCHEMAS[node.type + ':' + node.subtype] || {
      title: '节点配置', note: '', fields: [f('desc', '说明', 'text', { placeholder: '节点用途说明' })]
    };
  };

  /* ---------- 项目（流程的分类管理） ---------- */
  Proto.DATA.projects = [
    { id: 'P-001', name: '车间A · 设备监控', desc: '反应釜、产线 A 的实时采集与分析', color: '#2563EB' },
    { id: 'P-002', name: '全厂 · 能源与告警', desc: '能耗采集、阈值告警与通知', color: '#0F766E' },
    { id: 'P-003', name: '信息中心 · 业务集成', desc: 'MES / ERP 等 IT 系统数据集成', color: '#7C3AED' }
  ];

  /* ---------- 数据源 ---------- */
  Proto.DATA.sources = [
    { id: 'DS-001', name: 'OPC UA-反应釜温度', category: 'OT', protocol: 'OPC UA', address: 'opc.tcp://10.20.3.11:4840', detail: 'ns=3;s=Temperature.PT101', area: '车间A', freq: '1s', status: 'online', last: '2026-11-21 15:22:08' },
    { id: 'DS-002', name: 'ModbusTCP-产线A寄存器', category: 'OT', protocol: 'Modbus TCP', address: '10.20.3.20:502', detail: '寄存器 40001-40010 / 从站 1', area: '车间A', freq: '500ms', status: 'online', last: '2026-11-21 15:22:10' },
    { id: 'DS-003', name: 'PLC-S7-1500装配线', category: 'OT', protocol: 'S7comm', address: '10.20.4.8:102', detail: 'DB10,0 / 装配线节拍', area: '装配车间', freq: '2s', status: 'online', last: '2026-11-21 15:22:05' },
    { id: 'DS-004', name: 'MQTT-边缘网关 edge-gw-01', category: 'OT', protocol: 'MQTT', address: 'mqtt://broker.factory.local:1883', detail: '主题 plant/+/telemetry', area: '全厂', freq: '实时', status: 'offline', last: '2026-11-21 14:58:31' },
    { id: 'DS-005', name: 'MES工单API', category: 'IT', protocol: 'HTTP/REST', address: 'https://mes.factory.local/api/v1/orders', detail: 'GET /orders?date=today', area: '信息中心', freq: '5min', status: 'online', last: '2026-11-21 15:20:03' },
    { id: 'DS-006', name: '历史时序库', category: 'IT', protocol: 'PostgreSQL', address: '10.20.1.30:5432/timeseries', detail: 'schema: plant_a', area: '信息中心', freq: '按需', status: 'online', last: '2026-11-21 15:20:04' },
    { id: 'DS-007', name: 'Kafka-factory.events', category: 'IT', protocol: 'Kafka', address: 'kafka://10.20.1.40:9092', detail: 'Topic: factory.events', area: '信息中心', freq: '实时', status: 'error', last: '2026-11-21 15:11:47' },
    { id: 'DS-008', name: 'MES业务库-MySQL', category: 'IT', protocol: 'MySQL', address: '10.20.1.31:3306/mes', detail: 'schema: mes · 只读账号', area: '信息中心', freq: '5min', status: 'online', last: '2026-11-21 15:25:01' }
  ];

  Proto.SOURCE_PROTOCOL_FIELDS = {
    'OPC UA': ['节点路径', '命名空间'],
    'Modbus TCP': ['寄存器地址', '从站号'],
    'MQTT': ['订阅主题', 'QoS'],
    'HTTP/REST': ['请求方法', '请求路径'],
    'PostgreSQL': ['数据库', 'Schema'],
    'MySQL': ['数据库', 'Schema'],
    'Oracle': ['服务名', 'Schema'],
    'SQL Server': ['数据库', 'Schema'],
    'MongoDB': ['数据库', '集合'],
    'Kafka': ['Topic', 'Consumer Group'],
    'S7comm': ['DB 块', '起始偏移']
  };

  /* ---------- 变量与凭据 ---------- */
  Proto.DATA.variables = [
    { id: 'V-001', name: 'OPC_SERVER_URL', value: 'opc.tcp://10.20.3.11:4840', scope: 'prod', desc: 'OPC UA 服务器地址', updated: '2026-11-18 10:22' },
    { id: 'V-002', name: 'MODBUS_HOST', value: '10.20.3.20', scope: 'prod', desc: '产线 A Modbus 网关', updated: '2026-11-18 10:24' },
    { id: 'V-003', name: 'TEMP_THRESHOLD', value: '85', scope: 'global', desc: '高温告警阈值（℃）', updated: '2026-11-20 16:05' },
    { id: 'V-004', name: 'FLUSH_INTERVAL', value: '5000', scope: 'global', desc: '批量写入间隔（ms）', updated: '2026-11-20 16:06' },
    { id: 'V-005', name: 'LOG_LEVEL', value: 'DEBUG', scope: 'test', desc: '测试环境日志级别', updated: '2026-11-19 09:12' }
  ];

  Proto.DATA.credentials = [
    { id: 'C-001', name: 'MES_API_TOKEN', type: 'Bearer Token', value: 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...', scope: 'prod', desc: 'MES 接口访问令牌', updated: '2026-11-17 14:30' },
    { id: 'C-002', name: 'TIMESERIES_DB_PASSWORD', type: '数据库密码', value: 'Tsdb#2026!Factory', scope: 'prod', desc: '时序库写入账号', updated: '2026-11-17 14:35' },
    { id: 'C-003', name: 'KAFKA_SASL', type: 'Scram-SHA-256', value: 'factory-rw-secret', scope: 'prod', desc: 'Kafka 生产环境凭据', updated: '2026-11-16 11:20' },
    { id: 'C-004', name: 'TEST_WEBHOOK_KEY', type: 'API Key', value: 'test-wh-7f3a91', scope: 'test', desc: '测试告警 webhook', updated: '2026-11-19 09:15' },
    { id: 'C-005', name: 'FEISHU_BITABLE_APP', type: '飞书应用凭据', value: 'cli_a5d8f2b9e3c1... / dfgX8s2...', scope: 'prod', desc: '飞书多维表格应用 App ID / Secret', updated: '2026-11-21 10:12' },
    { id: 'C-006', name: 'REDIS_PASSWORD', type: '数据库密码', value: 'redis#2026!', scope: 'prod', desc: 'Redis 写入凭据（供安装节点使用）', updated: '2026-11-21 11:03' }
  ];

  /* ---------- 流程（DAG） ---------- */
  function node(id, type, subtype, title, subtitle, x, y, config) {
    return { id: id, type: type, subtype: subtype, title: title, subtitle: subtitle, x: x, y: y, config: config || {} };
  }
  function edge(id, from, to) {
    return { id: id, from: { nodeId: from, port: 'out' }, to: { nodeId: to, port: 'in' } };
  }

  Proto.DATA.flows = [
    {
      id: 'flow-1', projectId: 'P-001', name: '车间温度实时采集', desc: '每 5 分钟读取 OPC UA 温度点位，清洗后写入时序库',
      status: 'running', nextRun: '2026-11-21 15:25:00', records: 48210, successRate: '99.2%', lastRun: '15:20:03',
      nodes: [
        node('n11', 'trigger', 'schedule', '定时触发', '每 5 分钟', 60, 170, { mode: 'minutes', everyMinutes: '5', cron: '0/5 * * * * ?', timezone: 'Asia/Shanghai' }),
        node('n12', 'trigger', 'manual', '手动触发', '按需执行', 60, 320, {}),
        node('n13', 'source', 'opcua', 'OPC UA 读取', 'ns=3;s=Temperature.PT101', 350, 230, { datasource: 'DS-001', nodeId: 'ns=3;s=Temperature.PT101', timeout: '5000ms' }),
        node('n14', 'code', 'nodejs', '数据清洗', 'Node.js · 异常值过滤与单位转换', 650, 230, {
          code: code([
            '// 输入: items[] 每个元素含 json 字段',
            'const results = [];',
            'for (const item of $input.all()) {',
            '  const raw = item.json.value;',
            '  if (typeof raw !== "number" || raw > 200 || raw < -50) continue;',
            '  results.push({ json: {',
            '    tag: item.json.tag,',
            '    temperature: Number(raw.toFixed(1)),',
            '    ts: new Date().toISOString()',
            '  }});',
            '}',
            'return results;'
          ]),
          timeout: '300s', sandbox: '独立进程 + 白名单模块'
        }),
        node('n15', 'output', 'timeseries', '写入时序库', 'PostgreSQL · plant_a.telemetry', 950, 230, { connection: 'DS-006', table: 'plant_a.telemetry', batchSize: '500', writeMode: '批量写入' })
      ],
      connections: [edge('e11', 'n11', 'n13'), edge('e12', 'n12', 'n13'), edge('e13', 'n13', 'n14'), edge('e14', 'n14', 'n15')]
    },
    {
      id: 'flow-2', projectId: 'P-001', name: '产线OEE日报', desc: '每天 08:00 读取 Modbus 寄存器，计算设备综合效率并推送 MES',
      status: 'running', nextRun: '2026-11-22 08:00:00', records: 1152, successRate: '96.8%', lastRun: '08:00:02',
      nodes: [
        node('n21', 'trigger', 'schedule', '定时触发', '每天 08:00 · cron', 60, 220, { mode: 'cron', everyMinutes: '5', everySeconds: '30', cron: '0 0 8 * * ?', timezone: 'Asia/Shanghai' }),
        node('n22', 'source', 'modbus', 'Modbus 读取', '寄存器 40001-40010', 350, 220, { datasource: 'DS-002', port: '502', registers: '40001-40010', slaveId: '1' }),
        node('n23', 'code', 'python', 'OEE 计算', 'Python · 稼动率/性能/质量', 650, 220, {
          code: code([
            '# 输入: _items，每个元素为 {"json": {...}}',
            'availability = float(_items[0]["json"]["reg40001"]) / 100',
            'performance  = float(_items[0]["json"]["reg40002"]) / 100',
            'quality      = float(_items[0]["json"]["reg40003"]) / 100',
            '',
            'oee = availability * performance * quality',
            'result = [{',
            '    "json": {',
            '        "date": _items[0]["json"]["date"],',
            '        "availability": round(availability, 4),',
            '        "performance": round(performance, 4),',
            '        "quality": round(quality, 4),',
            '        "oee": round(oee, 4)',
            '    }',
            '}]'
          ]),
          timeout: '300s', sandbox: 'Python 独立进程 · 标准库白名单'
        }),
        node('n24', 'output', 'http', 'HTTP 推送', 'POST MES /api/v1/oee', 950, 220, { method: 'POST', url: 'https://mes.factory.local/api/v1/oee', auth: 'C-001' })
      ],
      connections: [edge('e21', 'n21', 'n22'), edge('e22', 'n22', 'n23'), edge('e23', 'n23', 'n24')]
    },
    {
      id: 'flow-3', projectId: 'P-002', name: '设备高温告警', desc: '订阅 MQTT 温度主题，超过阈值时推送企业微信告警',
      status: 'paused', nextRun: '—', records: 327, successRate: '100%', lastRun: '14:32:18',
      nodes: [
        node('n31', 'source', 'mqtt', 'MQTT 订阅', 'plant/+/temp', 60, 220, { datasource: 'DS-004', topic: 'plant/+/temp', qos: '1' }),
        node('n32', 'code', 'nodejs', '阈值判断', 'Node.js · TEMP_THRESHOLD', 350, 220, { code: 'const th = Number($env.TEMP_THRESHOLD || 85);\nreturn $input.all().filter(i => i.json.temp > th);', timeout: '60s' }),
        node('n33', 'logic', 'condition', '条件分支', '温度 > 85℃', 650, 220, { expression: '{{ $json.temp > $vars.TEMP_THRESHOLD }}' }),
        node('n34', 'output', 'alert', '企业微信告警', 'Webhook · 告警机器人', 950, 220, { channel: '企业微信', webhook: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send' })
      ],
      connections: [edge('e31', 'n31', 'n32'), edge('e32', 'n32', 'n33'), edge('e33', 'n33', 'n34')]
    },
    {
      id: 'flow-4', projectId: 'P-003', name: 'MES 工单增量同步', desc: '从 MySQL 读取新增工单，写入 PostgreSQL 并回写同步标记',
      status: 'running', nextRun: '2026-11-21 15:30:00', records: 8640, successRate: '99.8%', lastRun: '15:25:01',
      nodes: [
        node('n41', 'trigger', 'schedule', '定时触发', '每 5 分钟', 60, 220, { mode: 'minutes', everyMinutes: '5', cron: '0/5 * * * * ?', timezone: 'Asia/Shanghai' }),
        node('n42', 'source', 'mysql', 'MySQL 查询', 'SELECT · mes.orders', 350, 220, { connection: 'DS-008', operation: 'select', table: 'mes.orders', columns: 'id, order_no, status, updated_at', where: 'synced = 0', limit: '500', timeout: '30000ms' }),
        node('n43', 'code', 'nodejs', '字段映射', 'Node.js · 字段对齐与类型转换', 650, 220, { code: 'return $input.all().map(item => ({ json: {\n  order_no: item.json.order_no,\n  status: item.json.status.toUpperCase(),\n  synced_at: new Date().toISOString()\n}}));', timeout: '120s' }),
        node('n44', 'source', 'postgres', '写入同步结果', 'INSERT · warehouse.orders', 950, 220, { connection: 'DS-006', operation: 'insert', table: 'warehouse.orders', columns: 'order_no, status, synced_at', conflict: 'ON CONFLICT (order_no) DO NOTHING', timeout: '30000ms' })
      ],
      connections: [edge('e41', 'n41', 'n42'), edge('e42', 'n42', 'n43'), edge('e43', 'n43', 'n44')]
    },
    {
      id: 'flow-5', projectId: 'P-003', name: '设备巡检记录同步飞书', desc: '定时从 MySQL 读取巡检记录，写入飞书多维表格供协作查看',
      status: 'paused', nextRun: '—', records: 0, successRate: '—', lastRun: '—',
      nodes: [
        node('n51', 'trigger', 'schedule', '定时触发', '每 30 秒', 60, 220, { mode: 'seconds', everySeconds: '30', timezone: 'Asia/Shanghai' }),
        node('n52', 'source', 'mysql', 'MySQL 查询', 'SELECT · mes.inspection', 350, 220, { connection: 'DS-008', operation: 'select', table: 'mes.inspection', columns: 'device, temp, checked_at', where: 'synced = 0', limit: '100' }),
        node('n53', 'output', 'feishu_bitable', '写入飞书多维表格', '新增记录 · 设备巡检台账', 650, 220, {
          credential: 'C-005', appToken: 'bascnCMII2OaZ8sQ5', tableId: 'tblsRc9GRRXKqhvW', operation: '新增记录',
          fieldMapping: '{"设备编号": "{{ $json.device }}", "温度": "{{ $json.temp }}"}', batchSize: '100', timeout: '10000ms'
        })
      ],
      connections: [edge('e51', 'n51', 'n52'), edge('e52', 'n52', 'n53')]
    }
  ];

  /* ---------- 执行日志 ---------- */
  Proto.DATA.executions = [
    {
      id: 'EXP-20261121-0043', short: '#EXP-0043', flowId: 'flow-1', flowName: '车间温度实时采集', trigger: '手动', status: 'running',
      start: '2026-11-21 15:22:12', end: '—', duration: '进行中',
      nodes: [
        { title: '手动触发', status: 'success', duration: '0.02s', input: {}, output: { json: { manual: true, user: 'admin' } } },
        { title: 'OPC UA 读取', status: 'running', duration: '0.8s', input: { json: { tag: 'PT101' } }, output: null },
        { title: '数据清洗', status: 'waiting', duration: '—', input: null, output: null },
        { title: '写入时序库', status: 'waiting', duration: '—', input: null, output: null }
      ]
    },
    {
      id: 'EXP-20261121-0042', short: '#EXP-0042', flowId: 'flow-1', flowName: '车间温度实时采集', trigger: '定时', status: 'success',
      start: '2026-11-21 15:20:03', end: '15:20:04', duration: '1.2s', records: 312,
      nodes: [
        { title: '定时触发', status: 'success', duration: '0.01s', input: {}, output: { json: { cron: '0/5 * * * * ?' } } },
        { title: 'OPC UA 读取', status: 'success', duration: '0.64s', input: { json: { nodeId: 'ns=3;s=Temperature.PT101' } }, output: { json: { tag: 'PT101', value: 82.36, quality: 'GOOD' } } },
        { title: '数据清洗', status: 'success', duration: '0.11s', input: { json: { value: 82.36 } }, output: { json: { tag: 'PT101', temperature: 82.4, ts: '2026-11-21T15:20:03.812Z' } } },
        { title: '写入时序库', status: 'success', duration: '0.44s', input: { json: { temperature: 82.4 } }, output: { json: { inserted: 312, table: 'plant_a.telemetry' } } }
      ]
    },
    {
      id: 'EXP-20261121-0041', short: '#EXP-0041', flowId: 'flow-2', flowName: '产线OEE日报', trigger: '定时', status: 'error',
      start: '2026-11-21 08:00:02', end: '08:00:03', duration: '0.8s',
      errorType: 'ValueError',
      error: "ValueError: could not convert string to float: 'N/A'\n  File \"<python>\", line 2, in <module>\n    availability = float(_items[0]['json']['reg40001']) / 100",
      failedNode: 'OEE 计算',
      nodes: [
        { title: '定时触发', status: 'success', duration: '0.01s', input: {}, output: { json: { cron: '0 0 8 * * ?' } } },
        { title: 'Modbus 读取', status: 'success', duration: '0.52s', input: { json: { registers: '40001-40010' } }, output: { json: { reg40001: 'N/A', reg40002: '93.4', reg40003: '98.8', date: '2026-11-21' } } },
        { title: 'OEE 计算', status: 'error', duration: '0.21s', input: { json: { reg40001: 'N/A', reg40002: '93.4', reg40003: '98.8' } }, output: null },
        { title: 'HTTP 推送', status: 'skipped', duration: '—', input: null, output: null }
      ]
    },
    {
      id: 'EXP-20261121-0040', short: '#EXP-0040', flowId: 'flow-3', flowName: '设备高温告警', trigger: '手动', status: 'success',
      start: '2026-11-21 14:32:18', end: '14:32:18', duration: '0.3s', records: 6,
      nodes: [
        { title: 'MQTT 订阅', status: 'success', duration: '0.15s', input: {}, output: { json: { topic: 'plant/A/temp', temp: 87.2 } } },
        { title: '阈值判断', status: 'success', duration: '0.03s', input: { json: { temp: 87.2 } }, output: { json: { temp: 87.2, over: true } } },
        { title: '条件分支', status: 'success', duration: '0.01s', input: { json: { over: true } }, output: { json: { branch: 'true' } } },
        { title: '企业微信告警', status: 'success', duration: '0.11s', input: { json: { temp: 87.2 } }, output: { json: { errcode: 0, errmsg: 'ok' } } }
      ]
    },
    {
      id: 'EXP-20261121-0039', short: '#EXP-0039', flowId: 'flow-3', flowName: '设备高温告警', trigger: '手动', status: 'error',
      start: '2026-11-21 13:05:41', end: '13:05:42', duration: '0.9s',
      errorType: 'ECONNREFUSED',
      error: 'Error: connect ECONNREFUSED 10.20.1.50:8080\n    at TCPConnectWrap.afterConnect [as oncomplete]',
      failedNode: '企业微信告警',
      nodes: [
        { title: 'MQTT 订阅', status: 'success', duration: '0.18s', input: {}, output: { json: { temp: 91.5 } } },
        { title: '阈值判断', status: 'success', duration: '0.02s', input: { json: { temp: 91.5 } }, output: { json: { over: true } } },
        { title: '条件分支', status: 'success', duration: '0.01s', input: { json: { over: true } }, output: { json: { branch: 'true' } } },
        { title: '企业微信告警', status: 'error', duration: '0.62s', input: { json: { temp: 91.5 } }, output: null }
      ]
    }
  ];

  /* ---------- 看板 ---------- */
  Proto.DATA.dashboard = {
    '24h': {
      kpis: [
        { label: '活跃流程', value: '12', foot: '较昨日 +1', trend: 'up', spark: [6, 7, 7, 8, 8, 9, 11, 12] },
        { label: '今日执行', value: '1,284', foot: '较昨日 +8.4%', trend: 'up', spark: [80, 95, 88, 120, 140, 132, 160, 180] },
        { label: '执行成功率', value: '98.6%', foot: '较昨日 -0.2%', trend: 'down', spark: [99, 99.2, 98.8, 99.1, 98.9, 98.7, 98.6, 98.6] },
        { label: '待处理告警', value: '3', foot: '2 条严重', trend: 'down', spark: [1, 2, 1, 3, 2, 4, 3, 3] }
      ],
      labels: ['00', '02', '04', '06', '08', '10', '12', '14', '16', '18', '20', '22'],
      series: [
        { name: '成功', color: '#2563EB', values: [42, 38, 35, 46, 92, 118, 134, 126, 141, 132, 110, 96] },
        { name: '失败', color: '#DC2626', values: [1, 0, 2, 1, 3, 1, 2, 4, 1, 0, 1, 0] }
      ],
      anomaly: { x: 7, label: '14:00', value: 4, text: '14:20 温度由 82℃ 骤降至 -40℃，疑似传感器断线' },
      bars: [
        { name: '温度采集', value: 48210 }, { name: '设备振动', value: 12904 },
        { name: 'OEE日报', value: 1152 }, { name: '高温告警', value: 327 }, { name: '能耗采集', value: 286 }
      ],
      donut: [{ name: '成功', value: 1266, color: '#2563EB' }, { name: '失败', value: 12, color: '#DC2626' }, { name: '运行中', value: 6, color: '#D97706' }]
    },
    '7d': {
      kpis: [
        { label: '活跃流程', value: '12', foot: '本周持平', trend: 'up', spark: [10, 10, 11, 11, 12, 12, 12] },
        { label: '本周执行', value: '8,942', foot: '较上周 +5.1%', trend: 'up', spark: [110, 130, 125, 140, 150, 165, 172] },
        { label: '执行成功率', value: '98.2%', foot: '较上周 -0.4%', trend: 'down', spark: [99, 98.8, 98.9, 98.4, 98.6, 98.3, 98.2] },
        { label: '待处理告警', value: '3', foot: '本周新增 11', trend: 'down', spark: [4, 6, 5, 8, 7, 5, 3] }
      ],
      labels: ['周一', '周二', '周三', '周四', '周五', '周六', '周日'],
      series: [
        { name: '成功', color: '#2563EB', values: [1180, 1265, 1310, 1288, 1420, 1362, 1105] },
        { name: '失败', color: '#DC2626', values: [18, 12, 21, 15, 26, 14, 9] }
      ],
      anomaly: { x: 4, label: '周五', value: 26, text: '周五出现 26 次失败，集中在 OEE 日报的 Modbus 点位' },
      bars: [
        { name: '温度采集', value: 312480 }, { name: '设备振动', value: 90412 },
        { name: 'OEE日报', value: 8064 }, { name: '高温告警', value: 2289 }, { name: '能耗采集', value: 1988 }
      ],
      donut: [{ name: '成功', value: 8810, color: '#2563EB' }, { name: '失败', value: 115, color: '#DC2626' }, { name: '运行中', value: 17, color: '#D97706' }]
    },
    '30d': {
      kpis: [
        { label: '活跃流程', value: '12', foot: '本月新增 2', trend: 'up', spark: [9, 9, 10, 10, 11, 12, 12, 12] },
        { label: '本月执行', value: '38,610', foot: '较上月 +11.7%', trend: 'up', spark: [100, 115, 122, 130, 145, 158, 170, 185] },
        { label: '执行成功率', value: '98.8%', foot: '较上月 +0.3%', trend: 'up', spark: [98.4, 98.5, 98.6, 98.5, 98.7, 98.8, 98.8, 98.8] },
        { label: '待处理告警', value: '3', foot: '本月累计 42', trend: 'down', spark: [12, 10, 9, 8, 7, 6, 4, 3] }
      ],
      labels: ['1日', '5日', '9日', '13日', '17日', '21日'],
      series: [
        { name: '成功', color: '#2563EB', values: [5100, 6280, 6410, 6820, 6905, 7100] },
        { name: '失败', color: '#DC2626', values: [72, 61, 58, 44, 39, 31] }
      ],
      anomaly: { x: 0, label: '1日', value: 72, text: '月初失败较多，主要来自 MQTT 网关离线' },
      bars: [
        { name: '温度采集', value: 1352000 }, { name: '设备振动', value: 412800 },
        { name: 'OEE日报', value: 34560 }, { name: '高温告警', value: 9860 }, { name: '能耗采集', value: 8420 }
      ],
      donut: [{ name: '成功', value: 38120, color: '#2563EB' }, { name: '失败', value: 315, color: '#DC2626' }, { name: '运行中', value: 175, color: '#D97706' }]
    }
  };

  Proto.DATA.flowStatus = Proto.DATA.flows.map(function (f) {
    return { id: f.id, projectId: f.projectId, name: f.name, status: f.status, nextRun: f.nextRun, records: f.records, successRate: f.successRate, lastRun: f.lastRun };
  });

  /* ---------- AI 预置内容 ---------- */
  Proto.DATA.ai = {
    diagnoses: {
      VAL_ERR: {
        title: '失败诊断 · OEE 计算节点',
        level: '高',
        cause: '字符串 <code>N/A</code> 无法转换为浮点数。大概率是 Modbus 寄存器 40001 尚未就绪或点位映射配置错误，上游读取到了无效占位值。',
        evidence: ['失败节点：OEE 计算（Python 代码节点）', '错误类型：ValueError（第 2 行 float() 转换）', '输入片段：{"reg40001": "N/A", "reg40002": "93.4"}'],
        steps: ['检查寄存器 40001 的从站映射与 PLC 侧数据就绪时序', '在代码节点增加空值兜底：try: float(x) except: None', '增加上游数据质量校验节点，过滤 quality != GOOD 的点位', '重跑流程并观察日志'],
        node: 'OEE 计算', flowId: 'flow-2'
      },
      ECONN: {
        title: '失败诊断 · 企业微信告警节点',
        level: '中',
        cause: '目标地址 <code>10.20.1.50:8080</code> 拒绝连接。告警 webhook 服务可能未启动、端口变更或网络策略拦截。',
        evidence: ['失败节点：企业微信告警（HTTP 推送）', '错误类型：connect ECONNREFUSED', '上游数据正常：temp = 91.5℃，条件分支命中 true'],
        steps: ['确认告警网关服务状态与监听端口', '检查 OT 网络到信息中心的 ACL 策略', '将 webhook 地址改为变量引用以便快速切换', '配置失败重试与本地落盘兜底'],
        node: '企业微信告警', flowId: 'flow-3'
      },
      DEFAULT: {
        title: '失败诊断',
        level: '待确认',
        cause: '根据执行日志初步判断为节点运行时异常，建议结合节点输入 JSON 进一步排查。',
        evidence: ['请展开失败节点的输入/输出以查看上下文'],
        steps: ['查看失败节点输入数据', '检查变量与凭据是否配置正确', '重跑流程验证'],
        node: '', flowId: ''
      }
    },
    anomalyAnswer: [
      { keys: ['温度', '突降', '异常', '-40', '传感器'], html: '<p>检测到 <strong>14:20</strong> 温度由 82℃ 骤降至 <strong>-40℃</strong>，持续约 3 分钟后恢复。</p><div class="diag-card"><h5>AI 判断</h5><p>该数值低于工艺合理范围（-20℃ ~ 120℃），大概率是 <strong>PT101 传感器断线或处于维护窗口</strong>，而非真实工艺降温。</p></div><p><strong>建议：</strong></p><ol><li>检查 OPC UA 节点 <code>ns=3;s=Temperature.PT101</code> 的质量戳（quality）</li><li>确认车间 A 是否有计划性维护</li><li>在代码节点增加合理范围校验，过滤 quality != GOOD 的数据</li></ol>' },
      { keys: ['告警', '失败'], html: '<p>当前共有 <strong>3</strong> 条待处理告警：2 条严重、1 条警告。其中 OEE 日报的 Modbus 点位读取失败重复出现 2 次。</p><p>建议优先处理温度传感器的异常读数，再检查告警网关连通性。</p>' },
      { keys: ['成功率', '执行'], html: '<p>近 24 小时执行成功率 <strong>98.6%</strong>，共 12 次失败。失败集中在两个节点：Python 代码节点（数据类型转换）与 HTTP 推送节点（网络不可达）。</p><p>这两类问题都可以通过输入校验 + 失败重试解决。</p>' }
    ],
    generic: '<p>我可以帮你<strong>生成或修改采集流程</strong>、<strong>诊断执行失败</strong>、<strong>分析采集数据异常</strong>。</p><p>试试点击下方快捷指令，或在流程编排 / 执行日志 / 看板中点击 AI 入口。</p>',
    generateAnswer: {
      temp: '<p>已理解你的需求：每 5 分钟从 OPC UA 读取温度点位，过滤异常值后写入时序库。</p><p>我将按以下结构生成流程：</p><ol><li>定时触发器（cron 0/5）</li><li>OPC UA 读取节点</li><li>Node.js 数据清洗节点</li><li>写入时序库节点</li></ol>',
      oee: '<p>已理解：每天 08:00 读取 Modbus 寄存器，用 Python 计算 OEE，再推送到 MES。</p><p>已生成 4 个节点的流程，代码节点包含异常值兜底逻辑。</p>',
      alert: '<p>已理解：订阅 MQTT 温度主题，超过阈值时推送告警。</p><p>已生成 MQTT 订阅 → 阈值判断 → 条件分支 → 告警通知的流程。</p>',
      default: '<p>已根据你的描述生成一版流程草案，节点与连线已加载到画布，可直接调整参数或继续补充需求。</p>'
    }
  };
})();
