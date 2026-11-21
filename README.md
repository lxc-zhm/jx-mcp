# jx-mcp

Spring AI MCP Server：PostgreSQL 数据 DML 工具 + RAG 向量检索（pgvector）。单 jar 双 profile：**STDIO**（本地 Agent：Claude Desktop / ZCode / Cursor）与 **Streamable HTTP**（远程 Agent）。

> 设计文档：`docs/superpowers/specs/2026-11-21-jx-mcp-design.md` · 实现计划：`docs/superpowers/plans/2026-11-21-jx-mcp.md`

## 工具一览

| 工具 | 说明 |
|---|---|
| `list_tables` / `describe_table` | 元数据（information_schema，不含系统表） |
| `query` | 只读 SELECT；**强制 LIMIT**（默认 1000） |
| `insert` / `update` / `delete` | 结构化参数化 DML；update/delete 缺 WHERE 默认拒绝 |
| `execute_dml` | 单条原始 DML，过白名单校验 |
| `vector_upsert` | RAG 写入：自动分块 + embedding（同 doc_id 替换） |
| `vector_search` | 相似度检索（topK + 元数据过滤，走 HNSW 索引） |
| `vector_delete` | 按 doc_id 删除向量块 |

**DDL 一律不可执行**（CREATE/ALTER/DROP/TRUNCATE…），两层防御：JSqlParser 语句级白名单 + 数据库受限角色（见安全说明）。

## 环境准备（从零开始）

```bash
# 1. PostgreSQL 17 + pgvector
docker run -d --name jx-pg \
  -e POSTGRES_USER=admin -e POSTGRES_PASSWORD=admin -e POSTGRES_DB=jx \
  -p 5432:5432 pgvector/pgvector:pg17

# 2. 特权脚本（管理员执行一次）：扩展、向量表+HNSW 索引、受限角色、demo 数据
for f in db-init/01_extensions.sql db-init/02_vector_store.sql db-init/03_role.sql db-init/04_demo_data.sql; do
  docker exec -i jx-pg psql -U admin -d jx -q < "$f"
done

# 3. embedding 模型（默认 ollama + bge-m3，本地免费）
ollama pull bge-m3
```

构建：`mvn package -DskipTests` → `target/jx-mcp-0.1.0.jar`。

## 配置（环境变量优先）

| 变量 | 说明 | 默认 |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | PG 连接；**默认用受限角色 jx_mcp_app** | `jdbc:postgresql://localhost:5432/jx` / `jx_mcp_app` / `jx_mcp_app` |
| `EMBEDDING_PROVIDER` | `ollama`（默认）/ `openai` | `ollama` |
| `EMBEDDING_DIM` | 向量维度，须与模型一致 | `1024`（bge-m3） |
| `OLLAMA_BASE_URL` / `OLLAMA_MODEL` | ollama 地址 / 模型 | `http://localhost:11434` / `bge-m3` |
| `OPENAI_API_KEY` | embedding=openai 时必填 | — |
| `OPENAI_BASE_URL` | 兼容端点；**智谱填 `https://open.bigmodel.cn/api/paas/v4`**（embedding-3 维度按模型设置 EMBEDDING_DIM） | OpenAI 官方 |
| `HTTP_PORT` | http profile 端口 | `8080` |
| `jx.http-token` | http 模式 Bearer Token（配置后 /mcp 需鉴权） | 空 = 不鉴权 |
| `jx.allow-where-less-mutation` | 放开无 WHERE 的 UPDATE/DELETE | `false` |
| `jx.chunk-size` / `jx.chunk-overlap` | RAG 分块参数 | `800` / `120` |

## 启动与接入

**STDIO（本地 Agent）**

```bash
java -jar target/jx-mcp-0.1.0.jar --spring.profiles.active=stdio
```

Claude Desktop / ZCode 的 MCP 配置示例：

```json
{
  "mcpServers": {
    "jx-mcp": {
      "command": "java",
      "args": ["-jar", "/absolute/path/target/jx-mcp-0.1.0.jar",
               "--spring.profiles.active=stdio",
               "--spring.datasource.url=jdbc:postgresql://localhost:5432/jx"]
    }
  }
}
```

**Streamable HTTP（远程 Agent）**

```bash
HTTP_PORT=8080 jx.http-token=secret java -jar target/jx-mcp-0.1.0.jar --spring.profiles.active=http
```

客户端指向 `http://<host>:8080/mcp`（配了 token 则带 `Authorization: Bearer <token>`）。

## 换 embedding 模型（原地重嵌）

1. 改 `EMBEDDING_PROVIDER` / `OLLAMA_MODEL`（或智谱 `OPENAI_BASE_URL` + key）与 `EMBEDDING_DIM`
2. 维度变化时管理员执行一次（清空向量列，HNSW 上限 2000 维）：
   ```sql
   ALTER TABLE vector_store ALTER COLUMN embedding TYPE vector(N) USING NULL;
   DROP INDEX IF EXISTS vector_store_embedding_hnsw_idx;
   CREATE INDEX vector_store_embedding_hnsw_idx ON vector_store USING hnsw (embedding vector_cosine_ops);
   ```
3. 重嵌：`java -jar target/jx-mcp-0.1.0.jar --spring.profiles.active=reembed`（分批重写、幂等可重跑、带进度日志）
4. 应用启动时校验维度一致性，不一致直接拒启（不会静默劣化检索）

## 安全说明

- **数据库层**：默认连接 `jx_mcp_app` 受限角色，仅 `SELECT/INSERT/UPDATE/DELETE` + 序列权限，无 DDL——代码层被绕过时库本身兜底。生产环境务必改默认密码
- **代码层**：JSqlParser 真解析 SQL，白名单仅单条 DML；拒绝多语句拼接、注释绕过、`SELECT ... INTO`；标识符白名单正则；值全部参数化绑定
- **向量表**：所有表对 DML 开放（用户决策），但直写 `vector_store` 会**绕过 embedding** 造成内容与向量不一致——RAG 数据请走 `vector_upsert`；已污染可用 reembed 修复
- 密钥只走环境变量；`logs/` 下为运行日志，无敏感配置落盘
