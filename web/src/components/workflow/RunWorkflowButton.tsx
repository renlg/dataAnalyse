import { PlayCircleOutlined, SettingOutlined } from '@ant-design/icons'
import { Button, Input, message, Modal, Space, Typography } from 'antd'
import { useState } from 'react'
import { workflowApi } from '../../api'

interface Props { workflowId:number; beforeRun?:()=>void|Promise<void>; buttonType?:'default'|'primary'|'dashed'|'link'|'text' }

export default function RunWorkflowButton({workflowId,beforeRun,buttonType='default'}:Props){
  const [open,setOpen]=useState(false),[json,setJson]=useState(''),[running,setRunning]=useState(false)
  const run=async(contextParams?:Record<string,unknown>)=>{setRunning(true);try{await beforeRun?.();const result=await workflowApi.run(workflowId,contextParams);message.success(`运行已启动 #${result.runId}`);setOpen(false)}catch(e){message.error((e as Error).message)}finally{setRunning(false)}}
  const runWithParams=async()=>{try{const value=json.trim()?JSON.parse(json):{};if(value===null||Array.isArray(value)||typeof value!=='object')throw new Error('上下文参数必须是 JSON 对象');await run(Object.keys(value).length?value:undefined)}catch(e){message.error(e instanceof SyntaxError?'上下文参数 JSON 格式错误':(e as Error).message)}}
  return <><Space size={0}><Button type={buttonType} icon={<PlayCircleOutlined/>} loading={running&&!open} onClick={()=>run()}>运行</Button><Button type={buttonType} icon={<SettingOutlined/>} onClick={()=>setOpen(true)}>带参数</Button></Space><Modal title="带上下文参数运行" open={open} confirmLoading={running} okText="运行" cancelText="取消" onOk={runWithParams} onCancel={()=>setOpen(false)}><Typography.Paragraph type="secondary">输入 key-value JSON。节点模板可用 <code>${'{参数名}'}</code>，Python 可从 <code>params</code> 或 <code>contextParams</code> 读取。</Typography.Paragraph><Input.TextArea value={json} onChange={e=>setJson(e.target.value)} rows={8} placeholder={'{\n  "digestDate": "2026-08-30"\n}'}/></Modal></>
}
