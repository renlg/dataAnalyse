# dataAnalyse 数据分析平台

基于 **Spring Boot 3 (Java 17) + React 18 (Vite + Ant Design)** 的数据分析平台，包含两大模块：

## 功能特性

### 1. 数据源管理
- 支持 **SQLite / H2 / MySQL** 三种数据源
- 数据源 CRUD + 连接测试
- 可视化 SQL 查询执行（返回动态列结果表）
- 密码 AES 加密存储，永不回显

### 2. 数据分析（工作流）
- 工作流列表 + 新建/编辑/删除/运行/运行历史
- **可视化工作流绘制编辑器**（React Flow）
  - 左侧组件面板：开始 / 结束 / taiwei / LLM / H2SQL / SQLiteSQL
  - 拖拽节点、连线编排执行流程
- **开始节点**：支持 cron 表达式定时执行
- **taiwei 节点**：通过 OpenAI 兼容协议调用 taiwei 智能体
- **LLM 节点**：通过 OpenAI 协议调用大模型
- **H2SQL / SQLiteSQL 节点**：配置 SQL 语句执行（H2 / SQLite 数据源）

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Spring Boot 3.2、Java 17、Maven、Spring Data JPA、JdbcTemplate |
| 前端 | React 18、TypeScript、Vite、Ant Design 5、@xyflow/react |
| 元数据库 | H2（应用自身管理数据） |
| 运行数据源 | SQLite / H2 / MySQL |

## 快速开始

```bash
# 后端
cd backend
mvn spring-boot:run        # 默认 http://localhost:8080

# 前端
cd web
npm install
npm run dev                # 默认 http://localhost:5173（代理 /api 到 8080）
```

## 工作流节点目录

| 节点 | 用途 | 关键配置 |
|------|------|---------|
| 开始 | 流程起点 | cron 定时周期表达式 |
| 结束 | 流程终点 | 输出字段 |
| taiwei | 调用 taiwei 智能体 | baseUrl / apiKey / model / 提示词 |
| LLM | 调用大模型 | baseUrl / apiKey / model / system / user |
| H2SQL | 执行 H2 SQL | 数据源 + SQL 语句 |
| SQLiteSQL | 执行 SQLite SQL | 数据源 + SQL 语句 |

节点输出可通过 `{{input}}` / `{{prev.output}}` 模板占位符在后续节点中引用。
