package com.jxmcp.security;

import net.sf.jsqlparser.statement.Statement;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlGuardTest {

    private final SqlGuard guard = new SqlGuard(false);

    @Test
    void select_insert_update_delete_pass() {
        assertType("SELECT id, name FROM demo_customers WHERE city = '北京'", SqlGuard.SqlType.SELECT);
        assertType("INSERT INTO demo_customers (name, city) VALUES ('张三', '上海')", SqlGuard.SqlType.INSERT);
        assertType("UPDATE demo_customers SET city = '广州' WHERE id = 1", SqlGuard.SqlType.UPDATE);
        assertType("DELETE FROM demo_customers WHERE id = 1", SqlGuard.SqlType.DELETE);
    }

    @Test
    void ddl_rejected() {
        assertRejected("CREATE TABLE t(id int)");
        assertRejected("ALTER TABLE t ADD c int");
        assertRejected("DROP TABLE t");
        assertRejected("TRUNCATE TABLE t");
        assertRejected("GRANT SELECT ON t TO u");
    }

    @Test
    void multi_statement_rejected() {
        assertRejected("SELECT 1; DROP TABLE t");
        assertAllowed("SELECT 1;");
        assertAllowed("DELETE FROM t WHERE id=1 -- ; DROP TABLE t");
    }

    @Test
    void select_into_rejected() {
        assertRejected("SELECT * INTO new_t FROM demo");
    }

    @Test
    void update_without_where_rejected_by_default() {
        assertRejected("UPDATE t SET a=1");
    }

    @Test
    void delete_without_where_rejected_by_default() {
        assertRejected("DELETE FROM t");
    }

    @Test
    void where_less_allowed_when_configured() {
        SqlGuard permissive = new SqlGuard(true);
        assertThatCode(() -> permissive.parseAndValidate("UPDATE t SET a=1")).doesNotThrowAnyException();
        assertThatCode(() -> permissive.parseAndValidate("DELETE FROM t")).doesNotThrowAnyException();
    }

    @Test
    void cte_delete_is_delete() {
        Statement st = guard.parseAndValidate("WITH x AS (SELECT 1) DELETE FROM t WHERE id=1");
        assertThat(SqlGuard.typeOf(st)).isEqualTo(SqlGuard.SqlType.DELETE);
    }

    @Test
    void cte_dml_in_select_rejected() {
        // CTE 体是数据修改语句时，顶层 SELECT 只是外壳——必须整体拒绝
        assertRejected("WITH d AS (DELETE FROM t RETURNING *) SELECT count(*) FROM d");
        assertRejected("WITH d AS (INSERT INTO t VALUES (1) RETURNING *) SELECT * FROM d");
        assertRejected("WITH d AS (UPDATE t SET a=1 RETURNING *) SELECT count(*) FROM d");
    }

    @Test
    void cte_dml_in_dml_rejected() {
        assertRejected("WITH d AS (DELETE FROM t RETURNING *) DELETE FROM t WHERE id=1");
    }

    private void assertType(String sql, SqlGuard.SqlType expected) {
        Statement st = guard.parseAndValidate(sql);
        assertThat(SqlGuard.typeOf(st)).isEqualTo(expected);
    }

    private void assertRejected(String sql) {
        assertThatThrownBy(() -> guard.parseAndValidate(sql))
                .isInstanceOf(SqlGuardException.class);
    }

    private void assertAllowed(String sql) {
        assertThatCode(() -> guard.parseAndValidate(sql)).doesNotThrowAnyException();
    }
}
