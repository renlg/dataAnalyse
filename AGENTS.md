# dataAnalyse — Architecture Guide (for AI coding agents)

dataAnalyse is a **data analysis platform** built with Spring Boot 3 (Java 17) + React 18 (Vite + Ant Design). It provides a **数据源管理 (data source management)** module (SQLite / H2 / MySQL sources with SQL query) and a **数据分析 (data analysis)** module built around a **visual workflow designer** (drawing-style node editor). Workflow nodes can be scheduled with **cron** expressions, can call the **taiwei** agent through its OpenAI-compatible API, can call LLMs through the **OpenAI protocol**, and can run **configured SQL statements** against the data sources (H2 SQL / SQLite SQL nodes).

This document is the authoritative architecture spec. Implement ALL of it. The user speaks Chinese and the UI is in Simplified Chinese.

## Project Layout

```
dataAnalyse/
├── pom.xml                     # Maven, Spring Boot 3 parent
├── README.md
├── AGENTS.md
├── backend/                    # Spring Boot 3 application (Java 17)
│   └── src/main/java/com/dataanalyse/
│       ├── DataAnalyseApplication.java
│       ├── config/             # WebMvcConfig, CORS, Jackson, datasource beans
│       ├── common/             # Result wrapper, exception handler, constants
│       ├── datasource/         # 数据源管理 module
│       │   ├── controller/     # DataSourceController (REST)
│       │   ├── entity/         # DataSourceEntity
│       │   ├── service/        # DataSourceService, JdbcExecutor
│       │   └── repo/           # Spring Data JPA repository
│       ├── workflow/           # 数据分析 workflow module
│       │   ├── controller/     # WorkflowController, WorkflowRunController
│       │   ├── entity/         # WorkflowEntity, WorkflowNodeEntity, WorkflowRunEntity
│       │   ├── engine/         # WorkflowEngine, node executors
│       │   ├── schedule/       # cron scheduling (SchedulerConfig)
│       │   └── repo/           # repositories
│       └── llm/                # LLM invocation (OpenAI protocol, httpclient)
│           └── LlmClient.java  # chat completions client
│   └── src/main/resources/
│       ├── application.yml
│       └── schema.sql          # DDL for the 4 management tables
│   └── src/test/java/          # tests
└── web/                        # React 18 + Vite + Ant Design
    ├── package.json
    ├── vite.config.ts
    ├── index.html
    └── src/
        ├── main.tsx
        ├── App.tsx
        ├── api/                # axios clients
        ├── pages/
        │   ├── DataSourcePage.tsx   # 数据源管理
        │   ├── AnalysisListPage.tsx # 数据分析列表
        │   └── WorkflowEditorPage.tsx # 工作流绘制编辑器
        └── components/
            └── workflow/       # workflow node components
```

## Tech Stack

- Backend: Spring Boot 3.2.x, Java 17, Maven, Spring Data JPA, H2 (runtime DB for the management tables), HikariCP, Spring JDBC (`JdbcTemplate`)
- Frontend: React 18, TypeScript, Vite 5, Ant Design 5, reactflow (@xyflow/react)
- Runtime data source support: **SQLite**, **H2**, **MySQL** (JDBC drivers: `org.xerial:sqlite-jdbc`, `com.h2database:h2`, `com.mysql:mysql-connector-j`)

## Database

The application itself stores its management data (data sources, workflows, workflow nodes, workflow runs) in an embedded **H2** database (`jdbc:h2:file:./data/dataanalyse;AUTO_SERVER=TRUE`). Use `schema.sql` DDL (executed via `spring.sql.init`) for the 4 tables. This is the APP's own metadata DB — it is different from the runtime data sources the user configures.

Management tables:

1. `data_sources` — id, name, type (sqlite|h2|mysql), host, port, database_name, username, password, jdbc_url, created_at
2. `workflows` — id, name, description, status (draft|active|disabled), created_at, updated_at
3. `workflow_nodes` — id, workflow_id, node_key (unique within a workflow), node_type (start|end|taiwei|llm|h2sql|sqlitesql), name, position_x, position_y, config_json (TEXT), created_at
4. `workflow_runs` — id, workflow_id, status (running|success|failed), started_at, finished_at, logs (TEXT)
5. `users` — id, username (unique), password_hash, created_at
6. `auth_tokens` — token (PK), username, created_at, expires_at

## Core Requirements

### 0. 登录认证 (Login / Authentication) — ADDED 2026-08-26

The platform requires a login page and token-based authentication before any feature can be used.

- **用户表**: extend the metadata schema with a `users` table — `id`, `username` (unique), `password_hash` (BCrypt), `created_at`. Seed one default admin account on startup if no user exists: username `admin`, password `admin123` (overridable via env `DATA_ANALYSE_ADMIN_PASSWORD`, and via env `DATA_ANALYSE_ADMIN_USERNAME`). Do NOT store plaintext passwords.
- **登录接口**: `POST /api/auth/login` `{username, password}` → `{"code":0,"message":"ok","data":{"token":"<random 32-hex>","username":"admin"}}`; wrong credentials → 401 with Chinese message `用户名或密码错误`. Optionally support `POST /api/auth/logout` to invalidate the token.
- **Token 持久化**: keep a `auth_tokens` table (`token`, `username`, `created_at`, `expires_at`, 7-day expiry) so a restart does NOT log the user out. On startup, expired tokens are cleaned.
- **鉴权拦截器**: a `HandlerInterceptor` guards all `/api/**` except `/api/auth/login` (and health if present). Accepts `Authorization: Bearer <token>`; missing/invalid/expired → 401 `未登录或登录已过期`. Static resources (web/dist) are NOT guarded.
- **前端登录页**: route `/login` — centered Ant Design card with username + password inputs, submit button, loading state, error message. On app load, call a `GET /api/auth/me` (returns current username from token) — if it 401s, redirect to `/login`. After login, store the token in localStorage and attach `Authorization: Bearer <token>` on every axios request (request interceptor); on any 401 response, clear the token and redirect to `/login`. Add a logout button in the layout header.

### 1. 数据源管理 (Data Source Management)

REST CRUD for data sources. Supports three types: `sqlite`, `h2`, `mysql`.

- Create: name + type + connection params. For **sqlite**: a file path (`database_name` holds the file path, jdbc URL auto-built as `jdbc:sqlite:<path>`). For **h2**: file path or memory DB name, jdbc URL auto-built as `jdbc:h2:file:<path>` (or `jdbc:h2:mem:` for memory). For **mysql**: host/port/database/username/password, jdbc URL auto-built as `jdbc:mysql://<host>:<port>/<db>?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true`.
- Password must be stored encrypted (AES, key from env `DATA_ANALYSE_SECRET`, default a dev key) and never returned to the client (mask as `***`).
- `POST /api/datasources/{id}/test` — test the connection with a real JDBC `DriverManager.getConnection`.
- `POST /api/datasources/{id}/query` — execute an arbitrary SQL statement, return column names + rows (max 1000 rows). Use a fresh JDBC connection per request (never pool across different source types).
- `GET /api/datasources` — list all, with an `online` flag computed by a lightweight connection test (fail → false).

### 2. 数据分析 (Data Analysis) — workflow management

- `GET /api/workflows` — list workflows with node counts.
- `POST /api/workflows` — create workflow.
- `PUT /api/workflows/{id}` — update name/description/status.
- `DELETE /api/workflows/{id}` — delete workflow + its nodes (cascade).
- `GET /api/workflows/{id}` — full detail including nodes (for the editor to load).
- Node CRUD:
  - `PUT /api/workflows/{id}/nodes` — **batch save all nodes** of a workflow (replace-all). This is how the editor saves the whole canvas. Each node: `nodeKey`, `nodeType`, `name`, `positionX`, `positionY`, `config` (object).
  - `GET /api/workflows/{id}/nodes` — get nodes.
- Node types: `start`, `end`, `taiwei`, `llm`, `h2sql`, `sqlitesql`.
- `POST /api/workflows/{id}/run` — trigger a manual run of the workflow; returns the run id and runs async (use a `@Async` task / ThreadPoolTaskExecutor).
- `GET /api/workflows/{id}/runs` — run history for a workflow.
- `GET /api/runs/{runId}` — run detail with logs.

### 3. 数据分析 — 工作流绘制编辑器 (Workflow Canvas Editor)

The "新建" button on the analysis list page opens a new page that is a **visual workflow designer**.

- Use **@xyflow/react (React Flow)**.
- **Left palette (左边是插件)**: draggable node types — `开始 (start)`, `结束 (end)`, `taiwei`, `LLM`, `H2SQL`, `SQLiteSQL`. Drag from palette onto the canvas to add a node.
- Node rendering:
  - `start` node: config = cron expression field (定时执行周期, supports cron expression). Show a tag "定时执行: <cron>".
  - `end` node: config = optional output field (e.g. which previous node's result to output).
  - `taiwei` node: config = base URL, api key, model (OpenAI-compatible chat completions), and a prompt/task text.
  - `llm` node: config = base URL, api key, model, system prompt, user prompt (OpenAI protocol).
  - `h2sql` / `sqlitesql` nodes: config = data source id (select from configured data sources of matching type) + **SQL statement** to execute.
- Connect nodes with edges to define flow. When a node runs, inject its inputs (the output of connected predecessor nodes) into its config/prompt via simple template placeholders like `{{input}}` / `{{prev.output}}`.
- Canvas toolbar: 保存 (save), 运行 (run), 返回 (back to list). Add edge between nodes by dragging from node handle to node handle.

### 4. Workflow Execution Engine

- Topological execution: start node → downstream nodes → end node. Each node executes when all its input edges are resolved.
- Node execution:
  - `start`: just passes a trigger payload through; its cron field is used by the scheduler.
  - `taiwei`: POST to `{baseUrl}/v1/chat/completions` (OpenAI format) with model, messages (system=prompt), streaming disabled. Return the first choice's content. This is exactly the protocol taiwei exposes.
  - `llm`: same OpenAI protocol call but simpler config (system + user prompt).
  - `h2sql` / `sqlitesql`: open a JDBC connection to the configured data source (matching its type), run the configured SQL, return rows. Multi-row results are serialized to text (JSON) for downstream nodes. Injection of upstream node outputs into the SQL via `{{...}}` placeholders is supported.
- Each node's execution result is stored in memory per-run and passed downstream; a run's overall output is the `end` node's output.
- Async execution: `POST /api/workflows/{id}/run` returns immediately with runId; execution happens on a thread pool; run status/logs written to `workflow_runs`.

### 5. Cron Scheduling (定时执行)

- The `start` node's cron field defines the schedule.
- Use Spring `@Scheduled` + `CronTrigger` or a simple `TaskScheduler` with `CronTrigger`: on app startup and after every workflow save, (re)register a scheduled task per workflow that has an active `start` node with a valid cron expression AND workflow.status == active.
- When a cron fires, it triggers the same async run as the manual run button.
- Guard against duplicate registrations (cancel previous task for the same workflow id before registering anew). Wrap registration in try/catch — invalid cron must not crash startup.
- Expose `GET /api/workflows/{id}/schedule` → current cron + next fire time (for display).

### 6. Result API & Errors

- All REST responses: `{"code": 0, "message": "ok", "data": ...}` wrapper. Business errors: `{"code": 4xx/5xx, "message": "<中文错误信息>", "data": null}`.
- Global exception handler (`@RestControllerAdvice`) maps exceptions to Chinese messages.
- Backend validation: required fields checked, data source type enum validated, invalid cron → 400.

## Frontend pages (Chinese UI)

1. **数据源管理页** `/datasources` — table of data sources (name/type/online/createdAt), 新建/编辑/删除 buttons, a modal form (name, type radio, per-type fields), "测试连接" button inside the form, and a "SQL查询" action per row that opens a modal with a SQL textarea + run button + result table (dynamic columns from the query result).
2. **数据分析页** `/analysis` — table of workflows (name/status/nodeCount/createdAt/updatedAt), a **新建** button that creates a workflow and navigates to the editor, plus 编辑/删除/运行/运行历史 actions.
3. **工作流编辑器页** `/analysis/:id` — the React Flow canvas described in Core Requirement 3.

All API calls through axios with a baseURL `/api`. Use Ant Design components throughout. Mobile-friendliness is NOT required — desktop only.

## Constraints

- Backend language Java 17, build with Maven (`mvn`). Frontend build with Vite (`npm run build` must produce `web/dist/`).
- Spring Boot 3.x with Jakarta namespace. Do NOT use `spring.jpa.hibernate.ddl-auto=create` for the management tables — use `schema.sql` (spring.sql.init) so the schema is explicit and versioned.
- Use `JdbcTemplate`/plain JDBC for runtime data source queries (NOT JPA entities for the user's data sources — those are arbitrary external DBs).
- The management metadata DB is H2; runtime sources can be SQLite/H2/MySQL.
- Do NOT add lombok unless it is already available — keep it simple (write plain getters/setters if needed).
- Tests: at least a Spring Boot context smoke test + datasource service test (connection building) + a workflow engine unit test. `mvn test` must pass.
- README.md must be updated with full feature list, run instructions, and the workflow node catalog.
- Commit logically (backend, frontend, docs separate commits at minimum). Commit messages in Chinese.
- Do NOT attempt to deploy or run a server process long-term; build + test + commit + push is the goal. Do NOT add any external services (no Redis, no Docker).

## Verification

- `mvn test` passes in `backend/`.
- `npm run build` passes in `web/`.
- After both pass, `git add -A && git commit && git push origin main`.
- Print a final summary in Chinese + `git log --oneline -10`.
