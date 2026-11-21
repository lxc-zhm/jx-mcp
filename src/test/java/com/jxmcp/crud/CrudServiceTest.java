package com.jxmcp.crud;

import com.jxmcp.config.JxProperties;
import com.jxmcp.security.SqlGuardException;
import com.jxmcp.support.PgTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrudServiceTest extends PgTestBase {

    private CrudService service;

    @BeforeEach
    void setUp() {
        JxProperties props = new JxProperties();
        service = new CrudService(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(appDataSource()), props);
    }

    @Test
    void restricted_role_cannot_ddl() throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl(), APP_USER, APP_PASSWORD);
             Statement st = conn.createStatement()) {
            assertThatThrownBy(() -> st.execute("CREATE TABLE hack(id int)"))
                    .isInstanceOf(PSQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void list_tables_returns_demo_tables_only() {
        List<String> tables = service.listTables();
        assertThat(tables).contains("demo_customers", "demo_orders");
        assertThat(tables).noneMatch(t -> t.startsWith("pg_"));
    }

    @Test
    void describe_table_returns_columns() {
        List<Map<String, Object>> cols = service.describeTable("demo_customers");
        List<String> names = cols.stream().map(c -> (String) c.get("column_name")).toList();
        assertThat(names).containsExactly("id", "name", "city", "created_at");
    }

    @Test
    void query_forces_limit_when_missing() {
        JxProperties props = new JxProperties();
        props.setForcedLimit(3);
        CrudService limited = new CrudService(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(appDataSource()), props);
        assertThat(limited.query("SELECT * FROM demo_customers")).hasSize(3);
    }

    @Test
    void query_respects_smaller_explicit_limit() {
        assertThat(service.query("SELECT * FROM demo_customers LIMIT 2")).hasSize(2);
    }

    @Test
    void query_parenthesized_forces_limit() {
        assertThat(limitedService().query("(SELECT * FROM demo_customers)")).hasSize(3);
    }

    @Test
    void query_union_forces_limit() {
        assertThat(limitedService().query(
                "SELECT name FROM demo_customers UNION ALL SELECT product FROM demo_orders")).hasSize(3);
    }

    @Test
    void query_caps_larger_explicit_limit() {
        assertThat(limitedService().query("SELECT * FROM demo_customers LIMIT 99999999")).hasSize(3);
    }

    private CrudService limitedService() {
        JxProperties props = new JxProperties();
        props.setForcedLimit(3);
        return new CrudService(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(appDataSource()), props);
    }

    @Test
    void insert_roundtrip_special_chars() {
        String evil = "O'Brien\"; DROP TABLE demo_customers; --";
        int inserted = service.insert("demo_customers", Map.of("name", evil, "city", "注入测试"));
        assertThat(inserted).isEqualTo(1);

        List<Map<String, Object>> rows = service.query(
                "SELECT name FROM demo_customers WHERE id = (SELECT max(id) FROM demo_customers)");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("name")).isEqualTo(evil);
        assertThat(service.query("SELECT count(*) AS n FROM demo_customers").get(0).get("n")).isEqualTo(6L);
    }

    @Test
    void update_with_where_params() {
        int n = service.update("demo_customers", Map.of("city", "海淀"),
                "name = :name", Map.of("name", "张伟"));
        assertThat(n).isEqualTo(1);
        List<Map<String, Object>> rows = service.query("SELECT city FROM demo_customers WHERE name = '张伟'");
        assertThat(rows.get(0).get("city")).isEqualTo("海淀");
    }

    @Test
    void delete_with_where() {
        int n = service.delete("demo_orders", "status = :status", Map.of("status", "pending"));
        assertThat(n).isEqualTo(2);
        assertThat(service.query("SELECT count(*) AS n FROM demo_orders").get(0).get("n")).isEqualTo(6L);
    }

    @Test
    void update_without_where_rejected() {
        assertThatThrownBy(() -> service.update("demo_customers", Map.of("city", "x"), null, null))
                .isInstanceOf(SqlGuardException.class);
    }

    @Test
    void execute_dml_named_params() {
        int n = service.executeDml("UPDATE demo_orders SET status = :s WHERE product = :p",
                Map.of("s", "paid", "p", "台灯"));
        assertThat(n).isEqualTo(1);
        assertThat(service.query("SELECT status FROM demo_orders WHERE product = '台灯'")
                .get(0).get("status")).isEqualTo("paid");
    }

    @Test
    void where_with_statement_injection_rejected() {
        Long before = (Long) service.query("SELECT count(*) AS n FROM demo_customers").get(0).get("n");
        assertThatThrownBy(() -> service.delete("demo_customers",
                "id = 0; INSERT INTO demo_customers (name, city) VALUES ('injected', 'via-where')", null))
                .isInstanceOf(SqlGuardException.class);
        Long after = (Long) service.query("SELECT count(*) AS n FROM demo_customers").get(0).get("n");
        assertThat(after).isEqualTo(before);
    }

    @Test
    void identifier_injection_rejected() {
        assertThatThrownBy(() -> service.insert("demo_orders; DROP TABLE x", Map.of("product", "y")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update("demo_orders", Map.of("bad col!", "v"), "1=1", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
