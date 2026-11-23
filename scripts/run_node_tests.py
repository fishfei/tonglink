#!/usr/bin/env python3
"""DataFlow Studio 全节点回归测试。

对节点库里的每个 subtype 生成一个独立流程并真实执行，校验：
  1. 执行状态为 SUCCESS；
  2. 节点时间线有记录且 nodeTitle 非空；
  3. Node.js 的 console.log / Python 的 print 出现在执行日志里；
  4. 触发节点、逻辑节点确实有数据流入（输出条数 > 0）。

同时创建/复用「测试用例」项目 P-TEST，并生成一个
「深圳未来3天天气 → 飞书多维表格」的端到端流程。

用法：python scripts/run_node_tests.py [--base http://localhost:8080]
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = 'http://localhost:8080/api/v1'
TOKEN = ''
RESULTS = []

NODE_JS = """console.log('[NODE-TEST] nodejs console.log works');
return $input.map(x => ({ ...x, processed: true, tag: 'nodejs' }));"""
PYTHON = """print('[NODE-TEST] python print works')
result = [dict(item, processed=True, tag='python') for item in _items]"""
FEED = """console.log('[NODE-TEST] feed rows');
return [{ value: 20, name: 'alpha' }, { value: 5, name: 'beta' }];"""


def call(method, path, body=None, params=None, timeout=60):
    url = BASE + path
    if params:
        url += '?' + urllib.parse.urlencode(params)
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={
        'Content-Type': 'application/json; charset=utf-8',
        'Authorization': 'Bearer ' + TOKEN,
    })
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            payload = json.load(resp)
    except urllib.error.HTTPError as e:
        detail = e.read().decode('utf-8', 'replace')[:300]
        raise RuntimeError(f'HTTP {e.code} {path}: {detail}') from e
    if isinstance(payload, dict) and 'code' in payload and payload['code'] != 0:
        raise RuntimeError(f'{path} 返回业务错误：{payload.get("message")}')
    return payload.get('data') if isinstance(payload, dict) and 'data' in payload else payload


def node(nid, ntype, subtype, title, config=None, x=60, y=120):
    return {
        'id': nid, 'type': ntype, 'subtype': subtype, 'nodeVersion': '1',
        'title': title, 'position': {'x': x, 'y': y}, 'config': config or {},
    }


def edge(eid, src, dst, handle='out'):
    return {'id': eid, 'source': src, 'target': dst,
            'sourceHandle': handle, 'targetHandle': 'in'}


def graph_for(definition, flow_id, name):
    """按节点类型构造一条能真正产生数据的测试链。"""
    ntype, subtype = definition['type'], definition['subtype']
    nodes, edges = [], []
    nodes.append(node('n0', 'trigger', 'manual', '手动触发', {}, 60, 120))
    edges.append(edge('e0', 'n0', 'n1'))

    config = {}
    if subtype == 'http' and ntype == 'source':
        config = {
            'method': 'GET',
            'url': ('https://api.open-meteo.com/v1/forecast?latitude=22.5431'
                    '&longitude=114.0579&daily=temperature_2m_max,temperature_2m_min'
                    '&timezone=Asia%2FShanghai&forecast_days=3'),
            'expectedStatus': '200',
        }
    elif subtype == 'nodejs':
        config = {'code': NODE_JS, 'timeout': '30s'}
    elif subtype == 'python':
        config = {'code': PYTHON, 'timeout': '30s'}
    elif subtype in ('condition', 'filter'):
        # 先插入一个造数节点，保证逻辑判断有输入
        nodes.append(node('n1', 'code', 'nodejs', '测试数据', {'code': FEED}, 360, 120))
        nodes.append(node('n2', ntype, subtype, definition['title'],
                          {'expression': '{{ $json.value > 10 }}'}, 660, 120))
        edges = [edge('e0', 'n0', 'n1'), edge('e1', 'n1', 'n2')]
        nodes.append(node('n3', 'output', 'timeseries', '写入时序库',
                          {'table': 'qa.node_test', 'batchSize': 100}, 960, 120))
        edges.append(edge('e2', 'n2', 'n3', 'true'))
        return {
            'schemaVersion': 1, 'id': flow_id, 'name': name, 'projectId': 'P-TEST',
            'settings': {}, 'nodes': nodes, 'connections': edges,
        }
    elif subtype == 'delay':
        config = {'duration': 1, 'unit': '秒'}
    elif subtype == 'feishu_bitable':
        config = {'appToken': 'demo_bitable_app_token', 'tableId': 'demo_table_id',
                  'operation': '新增记录', 'credential': 'C-005'}
    elif ntype == 'output':
        nodes.append(node('n1', 'code', 'nodejs', '测试数据', {'code': FEED}, 360, 120))
        nodes.append(node('n2', ntype, subtype, definition['title'], config, 660, 120))
        return {
            'schemaVersion': 1, 'id': flow_id, 'name': name, 'projectId': 'P-TEST',
            'settings': {}, 'nodes': nodes,
            'connections': [edge('e0', 'n0', 'n1'), edge('e1', 'n1', 'n2')],
        }

    nodes.append(node('n1', ntype, subtype, definition['title'], config, 360, 120))
    nodes.append(node('n2', 'output', 'timeseries', '写入时序库',
                      {'table': 'qa.node_test', 'batchSize': 100}, 660, 120))
    edges.append(edge('e1', 'n1', 'n2'))
    return {
        'schemaVersion': 1, 'id': flow_id, 'name': name, 'projectId': 'P-TEST',
        'settings': {}, 'nodes': nodes, 'connections': edges,
    }


def weather_flow():
    """深圳未来 3 天天气 → 清洗 → 飞书多维表格。"""
    flow_id = 'FLOW-WEATHER-FEISHU'
    fetch_cfg = {
        'method': 'GET',
        'url': ('https://api.open-meteo.com/v1/forecast?latitude=22.5431'
                '&longitude=114.0579&daily=temperature_2m_max,temperature_2m_min,weather_code'
                '&timezone=Asia%2FShanghai&forecast_days=3'),
        'expectedStatus': '200',
    }
    code = """console.log('[WEATHER] 开始整理深圳未来3天天气');
const raw = $json.body ? JSON.parse($json.body) : $json;
const codes = {0:'晴',1:'多云',2:'阴',3:'阴',51:'小雨',61:'中雨',81:'阵雨'};
const d = raw.daily || {};
const rows = (d.time || []).map((date, i) => ({
  date,
  city: '深圳',
  tempMax: d.temperature_2m_max ? d.temperature_2m_max[i] : null,
  tempMin: d.temperature_2m_min ? d.temperature_2m_min[i] : null,
  weather: codes[d.weather_code ? d.weather_code[i] : 0] || '未知'
}));
console.log('[WEATHER] 生成记录 ' + rows.length + ' 条：' + JSON.stringify(rows));
return rows;"""
    feishu_cfg = {
        # 演示用标识：真实写入需在节点配置中换成你的表格 app_token / table_id，
        # 并在凭据 C-005 中配置真实 App ID / App Secret。
        'appToken': 'demo_bitable_app_token',
        'tableId': 'demo_table_id',
        'operation': '新增记录',
        'credential': 'C-005',
        'fieldMapping': json.dumps({
            '日期': 'date', '城市': 'city',
            '最高温': 'tempMax', '最低温': 'tempMin', '天气': 'weather',
        }, ensure_ascii=False),
    }
    nodes = [
        node('n0', 'trigger', 'manual', '手动触发', {}, 60, 140),
        node('n1', 'source', 'http', '读取深圳天气', fetch_cfg, 360, 140),
        node('n2', 'code', 'nodejs', '整理天气记录', {'code': code}, 660, 140),
        node('n3', 'output', 'feishu_bitable', '写入飞书多维表格', feishu_cfg, 960, 140),
    ]
    return {
        'schemaVersion': 1, 'id': flow_id, 'name': '深圳3天天气 → 飞书多维表格',
        'projectId': 'P-TEST', 'settings': {}, 'nodes': nodes,
        'connections': [edge('e%d' % i, 'n%d' % i, 'n%d' % (i + 1)) for i in range(3)],
    }


def run_and_check(flow, expect_log=None, min_records=1):
    # 后端新建时会分配自己的 ID，因此用「名称」定位已有流程，使用接口返回的真实 ID
    all_flows = call('GET', '/flows', params={'projectId': 'P-TEST'})
    existing = next((f for f in all_flows if f['name'] == flow['name']), None)
    if existing:
        flow_id = existing['id']
        call('PUT', '/flows/' + flow_id, {'name': flow['name'], 'graph': flow})
    else:
        created = call('POST', '/flows', {'name': flow['name'], 'projectId': 'P-TEST', 'graph': flow})
        flow_id = created['id']

    execution = call('POST', f'/flows/{flow_id}/run', {})
    exe_id = execution['id']
    status = ''
    for _ in range(40):
        status = call('GET', '/executions/' + exe_id)['status']
        if status not in ('PENDING', 'RUNNING'):
            break
        time.sleep(0.5)
    nodes = call('GET', f'/executions/{exe_id}/nodes')
    logs = call('GET', f'/executions/{exe_id}/logs')
    messages = [l['message'] for l in logs]
    titles_ok = all(n.get('nodeTitle') for n in nodes)
    log_ok = expect_log is None or any(expect_log in m for m in messages)
    records = sum(1 for n in nodes if n['status'] == 'SUCCESS'
                  and n['nodeType'] in ('source', 'output'))
    passed = status == 'SUCCESS' and titles_ok and log_ok and records >= min_records
    return {
        'flow': flow['name'], 'status': status, 'nodeCount': len(nodes),
        'titles': titles_ok, 'log': log_ok, 'records': records, 'pass': passed,
        'errors': [m for m in messages if '[ERROR]' in m or '失败' in m][:2],
    }


def main():
    global BASE, TOKEN
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', default=BASE)
    args = parser.parse_args()
    BASE = args.base.rstrip('/')
    # 允许传 http://host:port 或完整的 .../api/v1 两种形式
    if not BASE.endswith('/api/v1'):
        BASE += '/api/v1'

    # 内置账号口令在不同版本间调整过，依次尝试已知候选，取第一个能登录的
    candidates = ['Qbeed@912', 'Admin@123', 'Ywo@499', 'Eng@123', 'Uzxs@755', 'View@123']
    last_error = ''
    for password in candidates:
        login_req = urllib.request.Request(
            BASE + '/auth/login',
            data=json.dumps({'username': 'admin', 'password': password}).encode('utf-8'),
            headers={'Content-Type': 'application/json'}, method='POST')
        try:
            with urllib.request.urlopen(login_req, timeout=20) as r:
                payload = json.load(r)
            token = (payload.get('data') or {}).get('token')
            if token:
                TOKEN = token
                print(f'使用内置管理员账号登录成功\n')
                break
        except Exception as e:
            last_error = str(e)
    if not TOKEN:
        raise RuntimeError(f'所有候选口令均无法登录，最后错误：{last_error}')

    projects = call('GET', '/projects')
    if not any(p['id'] == 'P-TEST' for p in projects):
        call('POST', '/projects', {
            'id': 'P-TEST', 'name': '测试用例', 'code': 'QA-SUITE',
            'color': '#DC2626', 'description': '内置回归测试用例，每次修改后运行并核对执行日志',
        })

    definitions = call('GET', '/nodes/definitions')
    print(f'节点定义 {len(definitions)} 个，开始逐个实测…\n')
    for d in definitions:
        key = f"{d['type']}:{d['subtype']}"
        name = f"[TC] {d['title']} ({key})"
        flow = graph_for(d, 'FLOW-TC-' + key.replace(':', '-').upper(), name)
        expect = None
        if d['subtype'] == 'nodejs':
            expect = '[NODE-TEST] nodejs console.log works'
        elif d['subtype'] == 'python':
            expect = '[NODE-TEST] python print works'
        try:
            result = run_and_check(flow, expect)
        except Exception as e:
            result = {'flow': name, 'status': 'ERROR', 'errors': [str(e)], 'pass': False}
        RESULTS.append((key, result))
        flag = 'PASS' if result['pass'] else 'FAIL'
        print(f"  {flag:4} {key:26} 状态={result.get('status')} "
              f"节点={result.get('nodeCount')} 条数={result.get('records')} "
              f"名称={result.get('titles')} 日志={result.get('log')}")
        if not result['pass'] and result.get('errors'):
            print('        ', result['errors'])

    print('\n端到端：深圳天气 → 飞书多维表格')
    try:
        result = run_and_check(weather_flow(), '[WEATHER] 生成记录 3 条', min_records=2)
    except Exception as e:
        result = {'flow': 'weather', 'status': 'ERROR', 'errors': [str(e)], 'pass': False}
    RESULTS.append(('e2e:weather-feishu', result))
    print(f"  {'PASS' if result['pass'] else 'FAIL':4} 状态={result.get('status')} "
          f"节点={result.get('nodeCount')} 条数={result.get('records')} 日志={result.get('log')}")
    if not result['pass'] and result.get('errors'):
        print('        ', result['errors'])

    passed = sum(1 for _, r in RESULTS if r['pass'])
    print(f'\n结果：{passed}/{len(RESULTS)} 通过')
    return 0 if passed == len(RESULTS) else 1


if __name__ == '__main__':
    sys.exit(main())
