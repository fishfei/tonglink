import { describe, expect, it } from 'vitest'
import { filterDatasources, hasCycle } from './graph'

describe('流程图工具', () => {
  it('按节点类型过滤数据源', () => {
    const sources = [
      { id: 'DS-001', protocol: 'OPC UA' },
      { id: 'DS-002', protocol: 'Modbus TCP' },
      { id: 'DS-003', protocol: 'MQTT' }
    ]
    expect(filterDatasources(sources, 'opcua')).toHaveLength(1)
    expect(filterDatasources(sources, 'modbus')).toHaveLength(1)
    expect(filterDatasources(sources, 'mysql')).toHaveLength(0)
  })

  it('识别无环 DAG', () => {
    expect(hasCycle(['n1', 'n2', 'n3'], [
      { source: 'n1', target: 'n2' }, { source: 'n2', target: 'n3' }
    ])).toBe(false)
  })

  it('识别环路', () => {
    expect(hasCycle(['n1', 'n2'], [
      { source: 'n1', target: 'n2' }, { source: 'n2', target: 'n1' }
    ])).toBe(true)
  })
})
