import { Alert, Button, Card, Collapse, Empty, Space, Spin, Table, Tag, Tooltip, Typography } from 'antd'
import { ReactNode, useEffect, useMemo, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import { Background, Edge, Node, ReactFlow, ReactFlowProvider } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { MonitorInfo, MonitorNode, MonitorRun, monitorApi, workflowApi } from '../api'
import WorkflowNode from '../components/workflow/WorkflowNode'

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

type MonitorNodeData={nodeType:string;name:string;config:Record<string,unknown>;statusClass?:string;tooltipContent?:ReactNode}
function WrappedWorkflowNode({data,...rest}:any){
  const node=<WorkflowNode data={data} {...rest}/>
  return data.tooltipContent?<Tooltip title={data.tooltipContent} overlayClassName="monitor-config-tooltip" placement="right">{node}</Tooltip>:node
}
const workflowNodeTypes={workflow:WrappedWorkflowNode}

function MonitorFlow({nodes,edges}:{nodes:Node<MonitorNodeData>[];edges:Edge[]}){
  return <ReactFlow
    nodes={nodes}
    edges={edges}
    nodeTypes={workflowNodeTypes}
    nodesDraggable={false}
    nodesConnectable={false}
    elementsSelectable={false}
    fitView
    panOnDrag
    zoomOnScroll
    zoomOnPinch={false}
    zoomOnDoubleClick={false}
    minZoom={0.3}
    maxZoom={1.5}
  ><Background/></ReactFlow>
}

export default function WorkflowMonitorPage(){
  const workflowId=Number(useParams().workflowId)
  const [info,setInfo]=useState<MonitorInfo>()
  const [recent,setRecent]=useState<MonitorRun[]>([])
  const [history,setHistory]=useState<MonitorRun[]>([])
  const [hasMore,setHasMore]=useState(false)
  const [historyLoading,setHistoryLoading]=useState(false)
  const historyPage=useRef(0)
  const [loading,setLoading]=useState(true)
  const [error,setError]=useState('')
  const [reloadTick,setReloadTick]=useState(0)
  const [toggling,setToggling]=useState(false)

  useEffect(()=>{
    let active=true
    historyPage.current=0;setInfo(undefined);setRecent([]);setHistory([]);setHasMore(false);setHistoryLoading(false);setLoading(true);setError('')
    const refresh=async()=>{
      if(!Number.isFinite(workflowId)){setError('工作流编号不正确');setLoading(false);return}
      try{
        const [nextInfo,nextRuns]=await Promise.all([monitorApi.info(workflowId),monitorApi.runs(workflowId,0)])
        if(active){setInfo(nextInfo);setRecent(nextRuns.list);if(historyPage.current===0)setHasMore(nextRuns.hasMore);setError('')}
      }catch(e){if(active)setError((e as Error).message)}finally{if(active)setLoading(false)}
    }
    refresh();const timer=window.setInterval(refresh,4000);return()=>{active=false;window.clearInterval(timer)}
  },[workflowId,reloadTick])

  const enableMonitor=async()=>{
    setToggling(true)
    try{
      const detail=await workflowApi.detail(workflowId)
      await workflowApi.update(workflowId,{name:detail.name,description:detail.description,status:detail.status,config:{...(detail.config??{}),monitorEnabled:true}})
      setReloadTick(t=>t+1)
    }catch(e){setError((e as Error).message)}finally{setToggling(false)}
  }

  const loadHistory=async()=>{
    if(historyLoading||!hasMore)return
    setHistoryLoading(true)
    try{const nextPage=historyPage.current+1;const result=await monitorApi.runs(workflowId,nextPage);setHistory(current=>[...current,...result.list]);historyPage.current=nextPage;setHasMore(result.hasMore)}
    catch(e){setError((e as Error).message)}finally{setHistoryLoading(false)}
  }
  const runs=[...recent,...history]
  const latest=recent[0]
  const passedKeys=useMemo(()=>new Set((latest?.nodeResults??[]).map(item=>item.nodeKey)),[latest])

  const {flowNodes,flowEdges}=useMemo(()=>{
    const nodeList=info?.nodes??[]
    const fn:Node<MonitorNodeData>[]=[]
    const fe:Edge[]=[]
    for(const node of nodeList){
      const failed=latest?.status==='failed'&&latest.failedNode===node.nodeName
      const passed=passedKeys.has(node.nodeKey)
      const statusClass=failed?'failed':passed?'passed':''
      fn.push({id:node.nodeKey,type:'workflow',position:{x:node.positionX||0,y:node.positionY||0},data:{nodeType:node.nodeType,name:node.nodeName,config:{},statusClass:statusClass||undefined,tooltipContent:configTooltip(node)}})
      for(const target of node.outgoing??[]){fe.push({id:`${node.nodeKey}-${target}`,source:node.nodeKey,target})}
    }
    return {flowNodes:fn,flowEdges:fe}
  },[info,latest,passedKeys])

  if(loading)return <div className="editor-loading"><Spin size="large"/></div>
  const monitorNotEnabled = error.includes('未开启实时执行过程')
  if(error&&!info)return <div className="monitor-page">{monitorNotEnabled?<Alert type="info" showIcon message="该工作流未开启实时执行过程" description="点击下方按钮开启后即可查看实时执行过程。" action={<Button size="small" type="primary" loading={toggling} onClick={enableMonitor}>开启监控</Button>}/>:<Alert type="error" showIcon message="无法查看执行过程" description={error}/>}</div>
  return <div className="monitor-page">
    {error&&<Alert type="warning" showIcon message="刷新失败，页面将继续自动重试" description={error} style={{marginBottom:16}}/>}
    <Card className="monitor-summary">
      <Typography.Text type="secondary">下次执行时间</Typography.Text>
      <div className="monitor-next-time">{time(info?.nextFireTime)}</div>
      <Space size="large" wrap style={{marginTop:16}}><Typography.Title level={3} style={{margin:0}}>{info?.workflowName??'工作流执行过程'}</Typography.Title><div><Typography.Text type="secondary">定时表达式：</Typography.Text>{info?.cron||'未启用定时执行'}</div></Space>
    </Card>
    <div className="monitor-content">
      <Card title="节点执行状态" className="monitor-nodes" styles={{body:{padding:0}}}>
        {!flowNodes.length?<Empty description="该工作流暂无节点" style={{padding:24}}/>:<div className="monitor-flow-wrapper"><ReactFlowProvider><MonitorFlow nodes={flowNodes} edges={flowEdges}/></ReactFlowProvider></div>}
      </Card>
      <Card title={<Space>执行记录{latest&&<><Tag color={statusColor[latest.status]||'default'}>{statusText[latest.status]||latest.status}</Tag></>}</Space>} className="monitor-runs">
        {!runs.length?<Empty description="暂无执行记录"/>:<div className="monitor-run-list">{runs.map(run=><div key={run.id} className="monitor-run-item">
          <div className="monitor-run-header"><Space><Typography.Text strong>执行 #{run.id}</Typography.Text><Tag color={statusColor[run.status]||'default'}>{statusText[run.status]||run.status}</Tag></Space><Typography.Text type="secondary">{time(run.startedAt)}{run.finishedAt?` 至 ${time(run.finishedAt)}`:''}</Typography.Text></div>
          {run.status==='failed'&&<Alert type="error" showIcon message={run.failedNode?`失败节点：${run.failedNode}`:'工作流执行失败'} description={run.error||'未记录失败原因'} style={{marginTop:12}}/>}
          <Collapse ghost size="small" items={[{key:'results',label:`出参信息（${run.nodeResults.length}）`,children:run.nodeResults.length?<Table size="small" rowKey="nodeKey" pagination={false} dataSource={run.nodeResults} columns={[{title:'节点',dataIndex:'nodeName',width:130},{title:'类型',dataIndex:'nodeType',width:90},{title:'出参',dataIndex:'output',render:(value:unknown)=><pre className="monitor-output">{outputText(value)}</pre>}]}/>:<Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无节点出参"/>}]}/>
        </div>)}{hasMore&&<div style={{textAlign:'center',paddingTop:16}}><Button loading={historyLoading} onClick={loadHistory}>{history.length?'加载更多':'加载历史记录'}</Button></div>}</div>}
      </Card>
    </div>
  </div>
}
