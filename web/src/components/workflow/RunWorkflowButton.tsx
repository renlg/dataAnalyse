import { PlayCircleOutlined } from '@ant-design/icons'
import { Button, Input, message, Modal, Typography } from 'antd'
import { useState } from 'react'
import { workflowApi } from '../../api'

interface Props { workflowId:number; beforeRun?:()=>void|Promise<void>; buttonType?:'default'|'primary'|'dashed'|'link'|'text' }

export default function RunWorkflowButton({workflowId,beforeRun,buttonType='default'}:Props){
  const [open,setOpen]=useState(false),[json,setJson]=useState(''),[running,setRunning]=useState(false)
  const doRun=async(contextParams?:Record<string,unknown>)=>{setRunning(true);try{await beforeRun?.();const result=await workflowApi.run(workflowId,contextParams);message.success(`运行已启动 #${result.runId}`);setOpen(false)}catch(e){message.error((e as Error).message)}finally{setRunning(false)}}
  const runFromModal=async()=>{let value:unknown;try{value=json.trim()?JSON.parse(json):{};if(value===null||Array.isArray(value)||typeof value!=='object')throw new Error('上下文参数必须是 JSON 对象')}catch(e){message.error(e instanceof SyntaxError?'上下文参数 JSON 格式错误':(e as Error).message);return}await doRun(Object.keys(value as object).length?(value as Record<string,unknown>):undefined)}
  return <><Button type={buttonType} icon={<PlayCircleOutlined/>} loading={running} onClick={()=>setOpen(true)}>运行</Button><Modal title="运行工作流" open={open} confirmLoading={running} okText="运行" cancelText="取消" onOk={runFromModal} onCancel={()=>setOpen(false)}><Typography.Paragraph type="secondary">可选填上下文参数，留空则无参数直接运行。节点模板可用 <code>{'${参数名}'}</code>，Python 可从 <code>params</code> 或 <code>contextParams</code> 读取。</Typography.Paragraph><Input.TextArea value={json} onChange={e=>setJson(e.target.value)} rows={6} placeholder={'{\n  "digestDate": "2026-08-30"\n}\n留空则无参数运行'}/></Modal></>
}
