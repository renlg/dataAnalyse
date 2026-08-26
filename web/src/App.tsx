import { DatabaseOutlined, DeploymentUnitOutlined, LogoutOutlined } from '@ant-design/icons'
import { Layout, Menu, Typography, Button, message } from 'antd'
import { useEffect, useState } from 'react'
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import DataSourcePage from './pages/DataSourcePage'
import AnalysisListPage from './pages/AnalysisListPage'
import WorkflowEditorPage from './pages/WorkflowEditorPage'
import LoginPage from './pages/LoginPage'
import { authApi } from './api'

export default function App() {
  const nav = useNavigate()
  const location = useLocation()
  const [authChecked, setAuthChecked] = useState(false)
  const [authed, setAuthed] = useState(false)
  const [currentUser, setCurrentUser] = useState('')

  useEffect(() => {
    const token = localStorage.getItem('authToken')
    if (!token) {
      if (location.pathname !== '/login') nav('/login')
      setAuthChecked(true)
      return
    }
    authApi.me().then(r => {
      setCurrentUser(r.username)
      setAuthed(true)
      setAuthChecked(true)
      if (location.pathname === '/login') nav('/')
    }).catch(() => {
      localStorage.removeItem('authToken')
      setAuthChecked(true)
      if (location.pathname !== '/login') nav('/login')
    })
  }, [])

  const logout = async () => {
    try { await authApi.logout() } catch { /* ignore */ }
    localStorage.removeItem('authToken')
    message.success('已退出登录')
    nav('/login')
  }

  if (!authChecked) return <div className="editor-loading">加载中...</div>

  if (location.pathname === '/login') {
    return <Routes><Route path="/login" element={<LoginPage />} /><Route path="*" element={<Navigate to="/login" />} /></Routes>
  }

  if (!authed) return <Navigate to="/login" />

  const editor = /^\/analysis\/\d+/.test(location.pathname)
  if (editor) return <Routes><Route path="/analysis/:id" element={<WorkflowEditorPage />} /><Route path="*" element={<Navigate to="/analysis" />} /></Routes>

  return <Layout className="app-shell"><Layout.Sider width={224} theme="light" className="side"><div className="brand"><span className="brand-mark">D</span><div><Typography.Title level={4}>dataAnalyse</Typography.Title><Typography.Text type="secondary">数据分析平台</Typography.Text></div></div><Menu mode="inline" selectedKeys={[location.pathname]} onClick={({ key }) => nav(key)} items={[{ key: '/datasources', icon: <DatabaseOutlined />, label: '数据源管理' }, { key: '/analysis', icon: <DeploymentUnitOutlined />, label: '数据分析' }]} /></Layout.Sider><Layout.Content className="page-content"><div className="app-header-bar"><span className="app-user">{currentUser}</span><Button type="text" icon={<LogoutOutlined />} onClick={logout}>退出登录</Button></div><Routes><Route path="/datasources" element={<DataSourcePage />} /><Route path="/analysis" element={<AnalysisListPage />} /><Route path="*" element={<Navigate to="/datasources" />} /></Routes></Layout.Content></Layout>}
