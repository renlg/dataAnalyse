import { DatabaseOutlined, DeploymentUnitOutlined } from '@ant-design/icons'
import { Layout, Menu, Typography } from 'antd'
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import DataSourcePage from './pages/DataSourcePage'
import AnalysisListPage from './pages/AnalysisListPage'
import WorkflowEditorPage from './pages/WorkflowEditorPage'

export default function App(){const nav=useNavigate(),location=useLocation();const editor=/^\/analysis\/\d+/.test(location.pathname);if(editor)return <Routes><Route path="/analysis/:id" element={<WorkflowEditorPage/>}/><Route path="*" element={<Navigate to="/analysis"/>}/></Routes>;
return <Layout className="app-shell"><Layout.Sider width={224} theme="light" className="side"><div className="brand"><span className="brand-mark">D</span><div><Typography.Title level={4}>dataAnalyse</Typography.Title><Typography.Text type="secondary">数据分析平台</Typography.Text></div></div><Menu mode="inline" selectedKeys={[location.pathname]} onClick={({key})=>nav(key)} items={[{key:'/datasources',icon:<DatabaseOutlined/>,label:'数据源管理'},{key:'/analysis',icon:<DeploymentUnitOutlined/>,label:'数据分析'}]}/></Layout.Sider><Layout.Content className="page-content"><Routes><Route path="/datasources" element={<DataSourcePage/>}/><Route path="/analysis" element={<AnalysisListPage/>}/><Route path="*" element={<Navigate to="/datasources"/>}/></Routes></Layout.Content></Layout>}
