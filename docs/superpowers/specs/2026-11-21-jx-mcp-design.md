# jx-mcp 设计文档 — PostgreSQL 数据 CRUD + RAG 向量检索 MCP Server

- 日期：2026-11-21
- 状态：待用户批准
- 需求来源：与用户的 brainstorming 对话

## 意图与背景（Why）

用户需要一个 MCP Server，让 LLM Agent（本地工具如 Claude Desktop / ZCode / Cursor，以及远程 Agent）能够：

1. 安全地操作一个 PostgreSQL 业务库的**数据**（增删改查）；
2. 用同一个库做 **RAG 向量存储与检索**（写入自动向量化，检索走相似度）。

原始需求是 MySQL；对话中升级为 PostgreSQL + pgvector，因为 MySQL 无原生向量索引能力（向量只能存 BLOB、Java 里暴力算余弦，无法上量），而 Spring AI 自带 PgVectorStore 成熟实现。**一个 PG 同时当业务库和向量库，架构最简。**

## 目标

- Spring AI MCP Server，**同时支持 STDIO 与 Streamable HTTP 两种传输**（单 jar 双 profile）
- 数据 CRUD 工具：仅 DML（SELECT / INSERT / UPDATE / DELETE），**禁止一切 DDL**
- RAG 向量能力：基于 Spring AI PgVectorStore，写入自动 embedding，检索支持 topK + 元数据过滤
- 数据库连接、账密、embedding 提供方**全部可配置**（application.yml + 环境变量），密钥不进代码库
- 提供开箱即用的环境准备脚本（Docker 起 PG + pgvector、建受限角色、Ollama 拉模型）

## 非目标

- 不做 DDL（建表、改结构、删表、truncate）——明确禁止，见"DDL 防御"
- 不做数据库管理功能（备份、账号管理、监控等）
- 不内置文档解析 pipeline（PDF/Word 解析等）——工具只接收文本；长文本分块用内置简单策略
- 不做多数据库切换、多租户

## 反目标（技术上成功了也算失败）

- LLM 通过任何工具执行了 DDL，或绕过了 DML 白名单
- 密码 / API key 硬编码进代码，或真实密钥被提交进仓库
- 向量检索退化成全表扫描 + Java 暴力算相似度（必须走 pgvector HNSW 索引）

## 约束

- Java 21 + Spring Boot 4.1.x + Spring AI 2.1.0 GA（实现计划时已按官方文档核实：MCP 工具注解为 `@McpTool`/`@McpToolParam`，Streamable HTTP 走 webmvc starter + `spring.ai.mcp.server.protocol=STREAMABLE`）
- 构建工具：Maven（我的选择，用户未指定）
- PostgreSQL 15+，安装 pgvector 扩展（Docker 镜像 `pgvector/pgvector:pg17` 即满足）
- MCP 连接默认使用**受限数据库角色**（仅 DML 权限），见"DDL 防御"

## 对话中确定的决策

| 决策 | 内容 | 谁定的 |
|---|---|---|
| 用途 | RAG 向量存储 | 用户 |
| 数据库 | PostgreSQL + pgvector（由 MySQL 升级而来） | 用户提出，双方确认 |
| CRUD 范围 | 仅数据 DML，禁止 DDL / 禁止改删建表；**所有表（含向量表）都开放 DML**，不做硬拦截 | 用户 |
| 传输协议 | STDIO 与 Streamable HTTP 都要 | 用户 |
| Embedding | 抽象为 Spring AI `EmbeddingModel` 接口；默认 Ollama + bge-m3（本地、免费、中文好）；可换智谱 embedding-3 / OpenAI，只改配置 | 我的设计建议，用户未反对 |
| 双传输实现 | 单 jar 双 profile（`stdio` / `http`），工具代码共用 | 我的设计决定 |
| 业务库起点 | 全新环境：环境脚本造 demo 业务表 + 种子数据 + 受限角色，开箱可验收 | 用户 |
| 换 embedding 模型 | **原地重嵌**：提供 re-embedding 维护命令，读原文重新向量化后回写 | 用户 |
| Java / Maven | Java 21 + Maven | 我的选择 |
| HTTP 鉴权 | http profile 下可选 Bearer Token，默认关闭 | 我的设计决定 |

## MCP 工具集（暴露给 client 的 tools）

元数据（只读）：

1. `list_tables` — 列出 schema 中的表（information_schema）
2. `describe_table` — 指定表的列名 / 类型 / 注释

数据 DML：

3. `query` — 执行 SELECT；强制 LIMIT 上限（默认 1000，可配置）
4. `insert` / `update` / `delete` — 结构化参数化操作（表名 + 列值 / 条件），PreparedStatement 参数绑定
5. `execute_dml` — 原始 SQL 工具，仅放行 DML，供灵活场景；过 JSqlParser 白名单校验 + 只允许单语句。UPDATE/DELETE 不带 WHERE 默认拒绝（可配置放开）

向量（RAG）：

6. `vector_upsert` — 写入/更新文档片段（id、content、metadata），自动调 EmbeddingModel 向量化；长文本可选内置分块
7. `vector_search` — 相似度检索（query 文本、topK、metadata 过滤）
8. `vector_delete` — 按 id 或 metadata 过滤删除

工具粒度与参数 schema 细节实现时可微调，不影响架构。

## DDL 防御（两层）

1. **代码层**：JSqlParser 真正解析 SQL（非正则匹配），语句类型白名单仅 SELECT/INSERT/UPDATE/DELETE；拒绝多语句拼接；拒绝注释绕过
2. **数据库层（保底）**：README/db-init 脚本建受限角色，仅 `GRANT SELECT, INSERT, UPDATE, DELETE`，无 DDL 权限——即使代码层被绕过，库本身拒绝

## 向量库初始化与防护（builder check 后补充）

- **向量表的 DDL 由一次性特权脚本负责**：`CREATE EXTENSION vector`、建 `vector_store` 表、建 HNSW 索引全部放进 db-init 脚本，由管理员用高权限账号执行一次；应用以受限角色 + `initialize-schema=false` 运行——受限角色本就无 DDL 权限，配置错了会启动失败暴露问题，而不是静默降级
- **通用 DML 工具不屏蔽任何表**（用户明确：所有表都支持 DML）。直写向量表会绕过 embedding 造成内容与向量不一致，缓解措施：① 在 MCP 工具描述中向 LLM 说明该风险，引导 RAG 数据走 `vector_upsert`；② README 注明；③ `--reembed` 维护命令可作为修复手段（从原文重建全部向量）
- **启动时维度校验**：向量列维度 ≠ 所配 embedding 模型维度时直接报错拒启，防止静默检索质量劣化

## 传输层设计（单 jar 双 profile）

- `stdio` profile：`spring.main.web-application-type=none`；**日志全部重定向到文件**（stdout 是协议通道，绝不能被日志污染）
- `http` profile：Web 服务，Streamable HTTP 端点（如 `/mcp`）；可选 Bearer Token 鉴权
- 工具用 Spring AI `@Tool` 注解实现，与传输层完全解耦，两 profile 共用
- 本地 Agent 接入示例：`java -jar jx-mcp.jar --spring.profiles.active=stdio`

## 配置项（全部支持环境变量覆盖）

| 配置 | 说明 | 默认 |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | PG 连接（默认用受限角色） | 无，必填 |
| embedding provider 切换 | `ollama`（默认）/ `zhipu` / `openai` | ollama |
| `OLLAMA_BASE_URL` / `OLLAMA_MODEL` | 默认 `http://localhost:11434` / `bge-m3` | — |
| `ZHIPU_API_KEY` / `OPENAI_API_KEY` | 换云厂商时必填 | — |
| 向量维度 | 须与所选模型一致（bge-m3 = 1024），PgVectorStore 初始化用 | 1024 |
| SELECT 强制 LIMIT 上限 | `query` 工具保护 | 1000 |
| http profile 端口 / token | 可选鉴权 | 8080 / 关 |

## 换 embedding 模型流程（原地重嵌，用户选定）

1. 改配置指向新模型（维度可能不同，如 bge-m3 1024 → 智谱 2048）
2. 若维度变化：管理员执行一次特权 SQL 调整向量列维度（README 提供）
3. 运行维护命令 `java -jar jx-mcp.jar --reembed`：分批读取向量表原文 → 新模型重新向量化 → 回写；幂等可重跑，带进度日志
4. 实现形态是 **CLI 维护命令而非 MCP 工具**——避免 LLM 误触发批量重写（我的设计决定）

## 环境准备（README 提供）

1. `docker run`（pgvector/pgvector:pg17）+ 建扩展 `CREATE EXTENSION vector` + 建向量表与 HNSW 索引 + 建受限角色（db-init 特权脚本，管理员执行一次）
2. demo 业务表 + 种子数据（全新环境，开箱即可跑通 CRUD 与 RAG 验收）
3. `ollama pull bge-m3`（或配置云厂商 key）
4. 两种模式的启动命令与 Claude Desktop / ZCode 的 MCP 接入配置示例

## 留给实现者的

- Spring AI 依赖的确切 GAV 与版本锁定（写实现计划时核实最新 GA）
- 各工具的参数 JSON schema、返回格式与错误消息文案
- 测试策略（建议 Testcontainers 起 PG + pgvector；embedding 用 mock）
- 分块策略细节（默认按字符数 + 重叠，简单实现即可）
