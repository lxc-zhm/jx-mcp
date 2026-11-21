package com.jxmcp.crud;

import com.jxmcp.config.JxProperties;
import com.jxmcp.security.SqlGuard;
import com.jxmcp.security.SqlGuardException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 参数化 DML 执行：SqlGuard 白名单 + 标识符校验 + 命名参数绑定。
 */
@Service
public class CrudService {

    private static final Pattern IDENTIFIER =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$");

    private final NamedParameterJdbcTemplate jdbc;
    private final JxProperties props;
    private final SqlGuard guard;

    public CrudService(NamedParameterJdbcTemplate jdbc, JxProperties props) {
        this.jdbc = jdbc;
        this.props = props;
        this.guard = new SqlGuard(props.isAllowWhereLessMutation());
    }

    public List<Map<String, Object>> query(String sql) {
        Statement st = guard.parseAndValidate(sql);
        if (SqlGuard.typeOf(st) != SqlGuard.SqlType.SELECT) {
            throw new SqlGuardException("query 只接受 SELECT");
        }
        String effective = sql;
        // 无 LIMIT 的普通 SELECT 强制加 LIMIT；UNION/CTE 等复合形式保持原样
        if (st instanceof PlainSelect plain && plain.getLimit() == null) {
            Limit limit = new Limit();
            limit.setRowCount(new LongValue(props.getForcedLimit()));
            plain.setLimit(limit);
            effective = st.toString();
        }
        return jdbc.queryForList(effective, Map.of());
    }

    public int executeDml(String sql, Map<String, Object> params) {
        Statement st = guard.parseAndValidate(sql);
        if (SqlGuard.typeOf(st) == SqlGuard.SqlType.SELECT) {
            throw new SqlGuardException("executeDml 不接受 SELECT，请用 query");
        }
        return jdbc.update(sql, params == null ? Map.of() : params);
    }

    public int insert(String table, Map<String, Object> row) {
        String t = requireIdentifier(table);
        if (row == null || row.isEmpty()) {
            throw new IllegalArgumentException("插入内容为空");
        }
        row.keySet().forEach(this::requireIdentifier);
        String cols = String.join(", ", row.keySet());
        String placeholders = row.keySet().stream().map(c -> ":" + c).collect(Collectors.joining(", "));
        return jdbc.update("INSERT INTO " + t + " (" + cols + ") VALUES (" + placeholders + ")", row);
    }

    public int update(String table, Map<String, Object> set, String where, Map<String, Object> params) {
        String t = requireIdentifier(table);
        if (set == null || set.isEmpty()) {
            throw new IllegalArgumentException("缺少更新内容");
        }
        set.keySet().forEach(this::requireIdentifier);
        Map<String, Object> all = new HashMap<>();
        set.forEach((k, v) -> all.put("set_" + k, v));
        if (params != null) {
            all.putAll(params);
        }
        String assignments = set.keySet().stream()
                .map(c -> c + " = :set_" + c)
                .collect(Collectors.joining(", "));
        return jdbc.update("UPDATE " + t + " SET " + assignments + whereClause("UPDATE", where), all);
    }

    public int delete(String table, String where, Map<String, Object> params) {
        String t = requireIdentifier(table);
        return jdbc.update("DELETE FROM " + t + whereClause("DELETE", where),
                params == null ? Map.of() : params);
    }

    public List<String> listTables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """, Map.of(), String.class);
    }

    public List<Map<String, Object>> describeTable(String table) {
        return jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = :t
                ORDER BY ordinal_position
                """, Map.of("t", requireIdentifier(table)));
    }

    private String whereClause(String action, String where) {
        if (where == null || where.isBlank()) {
            if (!props.isAllowWhereLessMutation()) {
                throw new SqlGuardException(
                        action + " 缺少 WHERE 条件，默认拒绝（可用 jx.allow-where-less-mutation 放开）");
            }
            return "";
        }
        try {
            CCJSqlParserUtil.parseExpression(where);
        } catch (JSQLParserException e) {
            throw new SqlGuardException("WHERE 条件解析失败: " + e.getMessage());
        }
        return " WHERE " + where;
    }

    private String requireIdentifier(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("非法标识符: " + name);
        }
        return name;
    }
}
