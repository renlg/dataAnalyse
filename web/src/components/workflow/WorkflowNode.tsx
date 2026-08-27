import { Handle, NodeProps, Position } from '@xyflow/react'
import { ClockCircleOutlined, CodeOutlined, RobotOutlined } from '@ant-design/icons'
import { Tag } from 'antd'

const meta:Record<string,{color:string;label:string;icon:React.ReactNode;circle?:boolean}>={start:{color:'#000',label:'开始',icon:<ClockCircleOutlined/>,circle:true},end:{color:'#000',label:'结束',icon:<ClockCircleOutlined/>,circle:true},taiwei:{color:'#8854d0',label:'taiwei',icon:<RobotOutlined/>},llm:{color:'#3867d6',label:'LLM',icon:<RobotOutlined/>},h2sql:{color:'#f7b731',label:'H2SQL',icon:<CodeOutlined/>},sqlitesql:{color:'#4b6584',label:'SQLiteSQL',icon:<CodeOutlined/>},python:{color:'#20bf6b',label:'运行 Python',icon:<CodeOutlined/>}}
export default function WorkflowNode({data,selected}:NodeProps){const d=data as {nodeType:string;name:string;config:Record<string,unknown>},m=meta[d.nodeType]??meta.llm;
if(d.nodeType==='start'||d.nodeType==='end'){
  const filled=d.nodeType==='end';
  return <div className={`flow-node-circle ${selected?'selected':''} ${filled?'filled':''}`}>{d.nodeType!=='start'&&<Handle type="target" position={Position.Left}/>}<span className="flow-node-circle-dot"/>{d.nodeType!=='end'&&<Handle type="source" position={Position.Right}/>}<div className="flow-node-circle-label">{m.label}</div></div>;
}
return <div className={`workflow-node ${selected?'selected':''}`} style={{borderTopColor:m.color}}>{d.nodeType!=='start'&&<Handle type="target" position={Position.Left}/>}<div className="node-title"><span style={{color:m.color}}>{m.icon}</span><strong>{d.name||m.label}</strong></div><div className="node-kind">{m.label}</div>{(d.nodeType==='h2sql'||d.nodeType==='sqlitesql')&&<div className="node-summary">{Boolean(d.config?.dataSourceId)?'已选择数据源':'未选择数据源'}</div>}{d.nodeType!=='end'&&<Handle type="source" position={Position.Right}/>}</div>}
export {meta as workflowNodeMeta}
