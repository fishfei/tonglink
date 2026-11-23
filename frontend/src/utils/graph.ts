/** 流程图的纯工具函数，便于单测复用。 */

const PROTOCOL_MAP: Record<string, string> = {
  opcua: 'OPC UA', modbus: 'Modbus', s7comm: 'S7', mqtt: 'MQTT',
  kafka: 'Kafka', http: 'HTTP', mysql: 'MySQL', oracle: 'Oracle',
  postgres: 'PostgreSQL', sqlserver: 'SQL Server', mongodb: 'MongoDB'
}

/** 按节点子类型过滤数据源候选，与后端协议元数据保持一致。 */
export function filterDatasources(sources: any[], subtype: string): any[] {
  const protocol = PROTOCOL_MAP[subtype]
  if (!protocol) return sources
  return sources.filter(s => String(s.protocol).toLowerCase().includes(protocol.toLowerCase()))
}

/** 前端连线前的环检测，避免保存后才发现非法 DAG。 */
export function hasCycle(nodeIds: string[], edges: { source: string; target: string }[]): boolean {
  const indegree = new Map<string, number>()
  nodeIds.forEach(id => indegree.set(id, 0))
  edges.forEach(e => indegree.set(e.target, (indegree.get(e.target) || 0) + 1))
  const queue = nodeIds.filter(id => indegree.get(id) === 0)
  let visited = 0
  while (queue.length) {
    const current = queue.shift()!
    visited++
    edges.filter(e => e.source === current).forEach(e => {
      indegree.set(e.target, (indegree.get(e.target) || 0) - 1)
      if (indegree.get(e.target) === 0) queue.push(e.target)
    })
  }
  return visited !== nodeIds.length
}
