import { Table, Select, Space, Tag, Drawer, Typography, Button, message, Empty, Popconfirm, Spin } from 'antd'
import { ClearOutlined, ReloadOutlined } from '@ant-design/icons'
import { useEffect, useState, useMemo } from 'react'
import { useSearchParams } from 'react-router-dom'
import { workflowApi, WorkflowItem, RunItem } from '../api'

const statusColor: Record<string, string> = { success: 'green', failed: 'red', running: 'blue', pending: 'orange' }
const statusText: Record<string, string> = { success: '成功', failed: '失败', running: '运行中', pending: '待执行' }

function fmtOutput(o: unknown): string {
  if (o == null) return ''
  if (typeof o === 'string') return o
  return JSON.stringify(o, null, 2)
}

export default function RunLogPage() {
  const [params] = useSearchParams()
  const urlWf = params.get('workflowId')
  const [runs, setRuns] = useState<RunItem[]>([])
  const [flows, setFlows] = useState<WorkflowItem[]>([])
  const [wf, setWf] = useState<number | undefined>(urlWf ? Number(urlWf) : undefined)
  const [loading, setLoading] = useState(false)
  const [cleaning, setCleaning] = useState(false)
  const [detail, setDetail] = useState<RunItem | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(20)
  const [total, setTotal] = useState(0)

  const openDetail = async (r: RunItem) => {
    setDetailLoading(true)
    setDetail(r)
    try {
      const full = await workflowApi.getRun(r.id)
      setDetail(full)
    } catch (e) { message.error((e as Error).message) }
    setDetailLoading(false)
  }

  const load = async (p?: number, s?: number) => {
    setLoading(true)
    const curP = p ?? page
    const curS = s ?? pageSize
    try {
      const data = await workflowApi.listRuns(wf, curP, curS)
      setRuns(data.list)
      setTotal(data.total)
    } catch (e) { message.error((e as Error).message) }
    setLoading(false)
  }

  useEffect(() => { workflowApi.list().then(setFlows).catch(() => {}) }, [])
  useEffect(() => { setPage(0); load(0, pageSize) }, [wf])
  useEffect(() => { load() }, [page, pageSize])

  const cleanupZombies = async () => {
    setCleaning(true)
    try {
      const result = await workflowApi.cleanupZombies(30)
      message.success(`已清理 ${result.cleaned} 条僵尸运行记录`)
      await load()
    } catch (e) { message.error((e as Error).message) }
    setCleaning(false)
  }

  const detailRows = useMemo(() => (detail?.nodeResults || []).map((n, i) => ({ ...n, key: i })), [detail])

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '流程', dataIndex: 'workflowName', width: 140, render: (v: string) => v || '-' },
    { title: '状态', dataIndex: 'status', width: 90, render: (v: string) => <Tag color={statusColor[v] || 'default'}>{statusText[v] || v}</Tag> },
    { title: '开始时间', dataIndex: 'startedAt', width: 170, render: (v: string) => v ? new Date(v).toLocaleString() : '-' },
    { title: '结束时间', dataIndex: 'finishedAt', width: 170, render: (v: string) => v ? new Date(v).toLocaleString() : '-' },
    { title: '操作', width: 80, render: (_: unknown, r: RunItem) => <Button size="small" type="link" onClick={() => openDetail(r)}>详情</Button> },
  ]

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <Space size={12}>
          <Typography.Title level={4} style={{ margin: 0 }}>运行日志</Typography.Title>
          <Select
            allowClear placeholder="全部流程" style={{ width: 200 }} value={wf}
            onChange={(v) => setWf(v)}
            options={flows.map(f => ({ value: f.id, label: f.name }))}
          />
          {urlWf && <Typography.Text type="secondary">已按当前流程筛选</Typography.Text>}
        </Space>
        <Space>
          <Popconfirm title="清理僵尸运行记录" description="将超过 30 分钟且当前无活动任务的运行中记录标记为失败，是否继续？" onConfirm={cleanupZombies} okText="清理" cancelText="取消">
            <Button danger icon={<ClearOutlined />} loading={cleaning}>清理僵尸</Button>
          </Popconfirm>
          <Button icon={<ReloadOutlined />} onClick={() => load()} loading={loading}>刷新</Button>
        </Space>
      </div>
      <Table size="small" rowKey="id" loading={loading} columns={columns} dataSource={runs} pagination={{ current: page + 1, pageSize, total, showSizeChanger: true, pageSizeOptions: [10, 20, 50, 100], showTotal: (t: number) => `共 ${t} 条`, onChange: (p, s) => { setPage(p - 1); setPageSize(s) } }} />
      <Drawer title={`运行详情 #${detail?.id ?? ''}${detail?.workflowName ? ' · ' + detail.workflowName : ''}`} width={720} open={!!detail} onClose={() => setDetail(null)}>
        {detail && detailLoading ? <Spin /> : detail && (
          <>
            <Space style={{ marginBottom: 16 }} wrap>
              <Tag color={statusColor[detail.status] || 'default'}>{statusText[detail.status] || detail.status}</Tag>
              <Typography.Text type="secondary">开始：{detail.startedAt ? new Date(detail.startedAt).toLocaleString() : '-'}</Typography.Text>
              {detail.finishedAt && <Typography.Text type="secondary">结束：{new Date(detail.finishedAt).toLocaleString()}</Typography.Text>}
            </Space>
            {detailRows.length === 0 ? <Empty description="该运行没有节点结果" /> : (
              <Table
                size="small" rowKey="key" dataSource={detailRows} pagination={false}
                rowClassName={(r) => r.error ? 'run-log-error-row' : ''}
                expandable={{ expandedRowRender: (r) => r.error ? <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', maxHeight: 300, overflow: 'auto', background: '#fff1f0', padding: 12, borderRadius: 6, fontSize: 12, color: '#cf1322' }}>{r.error}</pre> : <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', maxHeight: 300, overflow: 'auto', background: '#f6f8fa', padding: 12, borderRadius: 6, fontSize: 12 }}>{fmtOutput(r.output)}</pre> }}
                columns={[
                  { title: '节点', dataIndex: 'nodeName', width: 110, render: (v: string, r) => r.error ? <Typography.Text style={{ color: '#cf1322' }}>{v}</Typography.Text> : v },
                  { title: '类型', dataIndex: 'nodeType', width: 80, render: (v: string) => v || '-' },
                  { title: '返回内容', dataIndex: 'output', ellipsis: true, render: (v: unknown, r) => r.error ? <Typography.Text style={{ color: '#cf1322' }}>执行失败</Typography.Text> : <Typography.Text type="secondary" ellipsis={{ tooltip: fmtOutput(v) }} style={{ maxWidth: 420 }}>{fmtOutput(v)}</Typography.Text> },
                ]}
              />
            )}
          </>
        )}
      </Drawer>
    </div>
  )
}
