package com.jxmcp.crud;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CRUD 工具的 @McpTool 薄壳：纯委托给 CrudService，不吞异常（错误由 MCP 框架回传客户端）。
 * 向量表风险：用 SQL 直写 vector_store 会绕过 embedding，造成内容与向量不一致（RAG 数据请走 vector_upsert）。
 */
@Component
public class CrudTools {

    private final CrudService service;

    public CrudTools(CrudService service) {
        this.service = service;
    }

    @McpTool(name = "list_tables", description = "列出 public schema 下所有业务表（不含系统表）")
    public List<String> list_tables() {
        return service.listTables();
    }

    @McpTool(name = "describe_table", description = "查看指定表的列名、类型、可空性与默认值")
    public List<Map<String, Object>> describe_table(
            @McpToolParam(description = "表名，如 demo_customers") String table) {
        return service.describeTable(table);
    }

    @McpTool(name = "query", description =
            "执行只读 SELECT 查询。结果会被强制加上 LIMIT（默认上限 1000，可用更小的显式 LIMIT）")
    public List<Map<String, Object>> query(
            @McpToolParam(description = "完整 SELECT 语句") String sql) {
        return service.query(sql);
    }

    @McpTool(name = "insert", description =
            "向指定表插入一行（列值参数化绑定）。"
                    + "注意：对 vector_store 表请改用 vector_upsert——直接插入会绕过 embedding，造成内容与向量不一致。")
    public int insert(
            @McpToolParam(description = "表名") String table,
            @McpToolParam(description = "列名到值的映射，如 {\"name\":\"张三\",\"city\":\"北京\"}")
            Map<String, Object> row) {
        return service.insert(table, row);
    }

    @McpTool(name = "update", description =
            "按 WHERE 条件更新指定表的列值（参数化绑定，缺 WHERE 会被拒绝）。"
                    + "注意：对 vector_store 表请改用 vector_upsert——直接更新会绕过 embedding，造成内容与向量不一致。")
    public int update(
            @McpToolParam(description = "表名") String table,
            @McpToolParam(description = "要更新的列名到新值映射") Map<String, Object> set,
            @McpToolParam(description = "WHERE 条件（不含 WHERE 关键字），可用 :name 命名参数") String where,
            @McpToolParam(description = "WHERE 里的命名参数值，如 {\"id\":1}", required = false)
            Map<String, Object> params) {
        return service.update(table, set, where, params);
    }

    @McpTool(name = "delete", description =
            "按 WHERE 条件删除指定表的行（缺 WHERE 会被拒绝）。"
                    + "注意：对 vector_store 表请改用 vector_delete——直接删除不会报错，但可能留下不一致的数据。")
    public int delete(
            @McpToolParam(description = "表名") String table,
            @McpToolParam(description = "WHERE 条件（不含 WHERE 关键字），可用 :name 命名参数") String where,
            @McpToolParam(description = "WHERE 里的命名参数值", required = false)
            Map<String, Object> params) {
        return service.delete(table, where, params);
    }

    @McpTool(name = "execute_dml", description =
            "执行单条原始 INSERT/UPDATE/DELETE（过 DML 白名单校验，命名参数绑定）。"
                    + "注意：对 vector_store 表的直接写入会绕过 embedding，造成内容与向量不一致，RAG 数据请走 vector_upsert。")
    public int execute_dml(
            @McpToolParam(description = "单条 DML 语句") String sql,
            @McpToolParam(description = "命名参数值", required = false) Map<String, Object> params) {
        return service.executeDml(sql, params);
    }
}
