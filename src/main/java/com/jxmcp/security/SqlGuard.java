package com.jxmcp.security;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.ParenthesedStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;

import java.util.List;

/**
 * 语句级 DML 白名单：只放行单条 SELECT / INSERT / UPDATE / DELETE。
 * 这是 DDL 防御的代码层；数据库层的保底是受限角色（见 db-init/03_role.sql）。
 */
public final class SqlGuard {

    public enum SqlType { SELECT, INSERT, UPDATE, DELETE }

    private final boolean allowWhereLessMutation;

    public SqlGuard(boolean allowWhereLessMutation) {
        this.allowWhereLessMutation = allowWhereLessMutation;
    }

    public Statement parseAndValidate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new SqlGuardException("SQL 为空");
        }
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException e) {
            throw new SqlGuardException("SQL 解析失败，已拒绝: " + e.getMessage());
        }
        if (statements.size() != 1) {
            throw new SqlGuardException("只允许单条语句，检测到 " + statements.size() + " 条");
        }
        Statement st = statements.get(0);
        SqlType type = typeOf(st);
        validateWithItems(st);
        switch (type) {
            case SELECT -> {
                if (st instanceof PlainSelect plain) {
                    boolean hasInto = (plain.getIntoTables() != null && !plain.getIntoTables().isEmpty())
                            || plain.getIntoTempTable() != null;
                    if (hasInto) {
                        throw new SqlGuardException("SELECT ... INTO 属于 DDL，已拒绝");
                    }
                }
            }
            case UPDATE -> {
                Update update = (Update) st;
                if (update.getWhere() == null && !allowWhereLessMutation) {
                    throw new SqlGuardException("UPDATE 缺少 WHERE 条件，默认拒绝（可用 jx.allow-where-less-mutation 放开）");
                }
            }
            case DELETE -> {
                Delete delete = (Delete) st;
                if (delete.getWhere() == null && !allowWhereLessMutation) {
                    throw new SqlGuardException("DELETE 缺少 WHERE 条件，默认拒绝（可用 jx.allow-where-less-mutation 放开）");
                }
            }
            default -> { /* INSERT 无 WHERE 要求 */ }
        }
        return st;
    }

    public static SqlType typeOf(Statement st) {
        if (st instanceof Select) {
            return SqlType.SELECT;
        }
        if (st instanceof Insert) {
            return SqlType.INSERT;
        }
        if (st instanceof Update) {
            return SqlType.UPDATE;
        }
        if (st instanceof Delete) {
            return SqlType.DELETE;
        }
        throw new SqlGuardException(
                "只允许 SELECT / INSERT / UPDATE / DELETE，已拒绝语句类型: " + st.getClass().getSimpleName());
    }

    /**
     * CTE 体递归校验：WITH 内不允许数据修改语句。
     * 否则 "WITH d AS (DELETE ... RETURNING *) SELECT ..." 这类外壳语句会把
     * INSERT/UPDATE/DELETE 藏进顶层 SELECT 里绕过白名单与 WHERE 防护。
     */
    private static void validateWithItems(Statement st) {
        List<WithItem<?>> items = null;
        if (st instanceof Select select) {
            items = select.getWithItemsList();
        } else if (st instanceof Delete delete) {
            items = delete.getWithItemsList();
        } else if (st instanceof Update update) {
            items = update.getWithItemsList();
        } else if (st instanceof Insert insert) {
            items = insert.getWithItemsList();
        }
        if (items == null) {
            return;
        }
        for (WithItem<?> item : items) {
            ParenthesedStatement inner = item.getStatement();
            if (inner instanceof Delete || inner instanceof Update || inner instanceof Insert) {
                throw new SqlGuardException(
                        "CTE \"" + item.getAliasName() + "\" 中不允许 DELETE/INSERT/UPDATE，已拒绝");
            }
            if (inner instanceof ParenthesedSelect paren) {
                validateNestedWithItems(paren.getWithItemsList(), item.getAliasName());
            }
        }
    }

    private static void validateNestedWithItems(List<WithItem<?>> items, String outerName) {
        if (items == null) {
            return;
        }
        for (WithItem<?> item : items) {
            ParenthesedStatement inner = item.getStatement();
            if (inner instanceof Delete || inner instanceof Update || inner instanceof Insert) {
                throw new SqlGuardException(
                        "嵌套 CTE \"" + item.getAliasName() + "\"（位于 \"" + outerName + "\" 内）不允许 DELETE/INSERT/UPDATE，已拒绝");
            }
            if (inner instanceof ParenthesedSelect paren) {
                validateNestedWithItems(paren.getWithItemsList(), item.getAliasName());
            }
        }
    }
}
