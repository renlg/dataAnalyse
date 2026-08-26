import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons'
import { Button, Card, Form, Input, message, Modal, Popconfirm, Radio, Space, Table, Tag, Typography } from 'antd'
import { useEffect, useState } from 'react'
import dayjs from 'dayjs'
import { apiKeyApi, ApiKeyItem } from '../api'

const typeNames: Record<string, string> = { llm: 'LLM', taiwei: 'taiwei' }
export default function ApiKeyPage() {
  const [items, setItems] = useState<ApiKeyItem[]>([])
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<ApiKeyItem>()
  const [form] = Form.useForm()

  const load = async () => { setLoading(true); try { setItems(await apiKeyApi.list()) } catch (e) { message.error((e as Error).message) } finally { setLoading(false) } }
  useEffect(() => { load() }, [])

  const showForm = (record?: ApiKeyItem) => { setEditing(record); form.setFieldsValue(record ?? { type: 'llm' }); setOpen(true) }
  const save = async () => {
    try {
      const value = await form.validateFields()
      if (editing) await apiKeyApi.update(editing.id, value)
      else await apiKeyApi.create(value)
      message.success('保存成功'); setOpen(false); load()
    } catch (e) { if (e instanceof Error) message.error(e.message) }
  }
  const remove = async (id: number) => { await apiKeyApi.remove(id); message.success('已删除'); load() }

  const columns = [
    { title: '名称', dataIndex: 'name' },
    { title: '类型', dataIndex: 'type', render: (v: string) => <Tag color={v === 'taiwei' ? 'purple' : 'blue'}>{typeNames[v] ?? v}</Tag> },
    { title: 'Base URL', dataIndex: 'baseUrl', ellipsis: true },
    { title: '模型', dataIndex: 'model' },
    { title: '备注', dataIndex: 'remark', ellipsis: true },
    { title: '创建时间', dataIndex: 'createdAt', render: (v: string) => v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-' },
    {
      title: '操作', render: (_: unknown, r: ApiKeyItem) => <Space>
        <Button type="link" icon={<EditOutlined />} onClick={() => showForm(r)}>编辑</Button>
        <Popconfirm title="确认删除该 API Key？" onConfirm={() => remove(r.id)}><Button danger type="link" icon={<DeleteOutlined />}>删除</Button></Popconfirm>
      </Space>
    }
  ]

  return <>
    <div className="page-heading"><div><Typography.Title level={2}>API Key 管理</Typography.Title><Typography.Text type="secondary">集中管理 LLM / taiwei 的 API 密钥，工作流节点可直接引用</Typography.Text></div><Button type="primary" icon={<PlusOutlined />} onClick={() => showForm()}>新建 API Key</Button></div>
    <Card><Table rowKey="id" loading={loading} dataSource={items} columns={columns} /></Card>
    <Modal title={editing ? '编辑 API Key' : '新建 API Key'} open={open} onCancel={() => setOpen(false)} onOk={save} width={560}>
      <Form form={form} layout="vertical">
        <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}><Input placeholder="例如：deepseek 主 key" /></Form.Item>
        <Form.Item name="type" label="类型" rules={[{ required: true }]}><Radio.Group disabled={!!editing} options={[{ label: 'LLM', value: 'llm' }, { label: 'taiwei', value: 'taiwei' }]} /></Form.Item>
        <Form.Item name="baseUrl" label="Base URL" rules={[{ required: true, message: '请输入 Base URL' }]}><Input placeholder="https://api.example.com" /></Form.Item>
        <Form.Item name="apiKey" label="API Key" rules={[{ required: !editing, message: '请输入 API Key' }]}><Input.Password placeholder={editing ? '留空或 *** 表示不修改' : '请输入 API Key'} /></Form.Item>
        <Form.Item name="model" label="模型"><Input placeholder="例如：deepseek-chat" /></Form.Item>
        <Form.Item name="remark" label="备注"><Input.TextArea rows={2} /></Form.Item>
      </Form>
    </Modal>
  </>
}
