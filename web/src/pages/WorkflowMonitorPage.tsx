import { Alert, Card, Collapse, Empty, Space, Spin, Table, Tag, Tooltip, Typography } from 'antd'
import { useEffect, useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import { MonitorInfo, MonitorNode, MonitorRun, monitorApi } from '../api'

const statusColor:Record<string,string>={success:'green',failed:'red',running:'blue'}
const statusText:Record<string,string>={success:'成功',failed:'失败',running:'运行中'}

function outputText(output:unknown){if(output==null)return '';return typeof output==='string'?output:JSON.stringify(output,null,2)}
function time(value?:string){return value?new Date(value).toLocaleString():'-'}
function configTooltip(node:MonitorNode){const config=node.configInfo;if(!config)return null
  if((node.nodeType==='h2sql'||node.nodeType==='sqlitesql')&&config.sql)return <div><div className="monitor-config-title">SQL</div><pre className="monitor-config-text">{config.sql}</pre></div>
  if(node.nodeType==='condition'&&config.conditions?.length){const MAX=5;const list=config.conditions as {target:string;targetName:string;condition?:string}[];const shown=list.slice(0,MAX);const rest=list.length-MAX;return <div><div className="monitor-config-title">连线条件</div>{shown.map((item:{target:string;targetName:string;condition?:string},index:number)=><div className="monitor-condition" key={`${item.target}-${index}`}>→ {item.targetName||item.target}：<span>{item.condition||'无条件'}</span></div>)}{rest>0&&<div className="monitor-condition monitor-condition-more">…还有 {rest} 条条件未显示</div>}</div>}
  if(node.nodeType==='llm'&&(config.systemPrompt||config.userPrompt))return <div><div className="monitor-config-title">系统提示词</div><pre className="monitor-config-text">{config.systemPrompt||'未配置'}</pre><div className="monitor-config-title monitor-config-section">用户提示词</div><pre className="monitor-config-text">{config.userPrompt||'未配置'}</pre></div>
  if(node.nodeType==='taiwei'&&config.prompt)return <div><div className="monitor-config-title">提示词</div><pre className="monitor-config-text">{config.prompt}</pre></div>
  return null
}

export default function WorkflowMonitorPage(){
  const workflowId=Number(useParams().workflowId)
  const [info,setInfo]=useState<MonitorInfo>()
  const [runs,setRuns]=useState<MonitorRun[]>([])
  const [loading,setLoading]=useState(true)
  const [error,setError]=useState('')

  useEffect(()=>{
    let active=true
    const refresh=async()=>{if(!Number.isFinite(workflowId)){setError('工作流编号不正确');setLoading(false);return}try{const [nextInfo,nextRuns]=await Promise.all([monitorApi.info(workflowId),monitorApi.runs(workflowId)]);if(active){setInfo(nextInfo);setRuns(nextRuns);setError('')}}catch(e){if(active){setInfo(undefined);setRuns([]);setError((e as Error).message)}}finally{if(active)setLoading(false)}}
    refresh();const timer=window.setInterval(refresh,4000);return()=>{active=false;window.clearInterval(timer)}
  },[workflowId])

  const latest=runs[0]
  const passedKeys=useMemo(()=>new Set((latest?.nodeResults??[]).map(item=>item.nodeKey)),[latest])

  if(loading)return <div className="editor-loading"><Spin size="large"/></div>
  if(error&&!info)return <div className="monitor-page"><Alert type="error" showIcon message="无法查看执行过程" description={error}/></div>
  return <div className="monitor-page">
    {error&&<Alert type="warning" showIcon message="刷新失败，页面将继续自动重试" description={error} style={{marginBottom:16}}/>}
    <Card className="monitor-summary">
      <Typography.Text type="secondary">下次执行时间</Typography.Text>
      <div className="monitor-next-time">{time(info?.nextFireTime)}</div>
      <Space size="large" wrap style={{marginTop:16}}><Typography.Title level={3} style={{margin:0}}>{info?.workflowName??'工作流执行过程'}</Typography.Title><div><Typography.Text type="secondary">定时表达式：</Typography.Text>{info?.cron||'未启用定时执行'}</div></Space>
    </Card>
    <div className="monitor-content">
      <Card title="节点执行状态" className="monitor-nodes">
        {!info?.nodes.length?<Empty description="该工作流暂无节点"/>:<div className="monitor-node-list">{info.nodes.map(node=>{const failed=latest?.status==='failed'&&latest.failedNode===node.nodeName;const passed=passedKeys.has(node.nodeKey);const content=configTooltip(node);const item=<div key={node.nodeKey} className={`monitor-node ${failed?'failed':passed?'passed':''}`}><Typography.Text strong>{node.nodeName}</Typography.Text><Tag>{node.nodeType}</Tag></div>;return content?<Tooltip key={node.nodeKey} title={content} overlayClassName="monitor-config-tooltip" placement="right">{item}</Tooltip>:item})}</div>}
      </Card>
      <Card title="执行记录" className="monitor-runs">
        {!runs.length?<Empty description="暂无执行记录"/>:<div className="monitor-run-list">{runs.map(run=><div key={run.id} className="monitor-run-item">
          <div className="monitor-run-header"><Space><Typography.Text strong>执行 #{run.id}</Typography.Text><Tag color={statusColor[run.status]||'default'}>{statusText[run.status]||run.status}</Tag></Space><Typography.Text type="secondary">{time(run.startedAt)}{run.finishedAt?` 至 ${time(run.finishedAt)}`:''}</Typography.Text></div>
          {run.status==='failed'&&<Alert type="error" showIcon message={run.failedNode?`失败节点：${run.failedNode}`:'工作流执行失败'} description={run.error||'未记录失败原因'} style={{marginTop:12}}/>}
          <Collapse ghost size="small" items={[{key:'results',label:`出参信息（${run.nodeResults.length}）`,children:run.nodeResults.length?<Table size="small" rowKey="nodeKey" pagination={false} dataSource={run.nodeResults} columns={[{title:'节点',dataIndex:'nodeName',width:130},{title:'类型',dataIndex:'nodeType',width:90},{title:'出参',dataIndex:'output',render:(value:unknown)=><pre className="monitor-output">{outputText(value)}</pre>}]}/>:<Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无节点出参"/>}]}/>
        </div>)}</div>}
      </Card>
    </div>
  </div>
}
