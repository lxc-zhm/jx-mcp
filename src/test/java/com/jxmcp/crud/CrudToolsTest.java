package com.jxmcp.crud;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrudToolsTest {

    private final CrudService service = mock(CrudService.class);
    private final CrudTools tools = new CrudTools(service);

    private static final List<String> TOOL_METHODS =
            List.of("list_tables", "describe_table", "query", "insert", "update", "delete", "execute_dml");

    @Test
    void delegates_to_service() {
        List<Map<String, Object>> rows = List.of(Map.of("id", 1));
        when(service.listTables()).thenReturn(List.of("demo_customers"));
        when(service.describeTable("demo_customers")).thenReturn(rows);
        when(service.query("SELECT 1")).thenReturn(rows);
        when(service.insert("t", Map.of("a", 1))).thenReturn(1);
        when(service.update("t", Map.of("a", 2), "id = :id", Map.of("id", 1))).thenReturn(1);
        when(service.delete("t", "id = :id", Map.of("id", 1))).thenReturn(1);
        when(service.executeDml("UPDATE t SET a=3 WHERE id = :id", Map.of("id", 1))).thenReturn(1);

        assertThat(tools.list_tables()).containsExactly("demo_customers");
        assertThat(tools.describe_table("demo_customers")).isEqualTo(rows);
        assertThat(tools.query("SELECT 1")).isEqualTo(rows);
        assertThat(tools.insert("t", Map.of("a", 1))).isEqualTo(1);
        assertThat(tools.update("t", Map.of("a", 2), "id = :id", Map.of("id", 1))).isEqualTo(1);
        assertThat(tools.delete("t", "id = :id", Map.of("id", 1))).isEqualTo(1);
        assertThat(tools.execute_dml("UPDATE t SET a=3 WHERE id = :id", Map.of("id", 1))).isEqualTo(1);

        verify(service).listTables();
        verify(service).describeTable("demo_customers");
        verify(service).query("SELECT 1");
        verify(service).insert("t", Map.of("a", 1));
        verify(service).update("t", Map.of("a", 2), "id = :id", Map.of("id", 1));
        verify(service).delete("t", "id = :id", Map.of("id", 1));
        verify(service).executeDml("UPDATE t SET a=3 WHERE id = :id", Map.of("id", 1));
    }

    @Test
    void annotations_present_and_named() {
        for (String name : TOOL_METHODS) {
            Method m = methodByName(name);
            McpTool ann = m.getAnnotation(McpTool.class);
            assertThat(ann).as("method %s", name).isNotNull();
            assertThat(ann.name()).isEqualTo(name);
            assertThat(ann.description()).isNotBlank();
        }
    }

    @Test
    void dml_descriptions_warn_vector_bypass() {
        for (String name : List.of("insert", "update", "delete", "execute_dml")) {
            assertThat(methodByName(name).getAnnotation(McpTool.class).description())
                    .as("method %s", name).contains("vector_store");
        }
        assertThat(methodByName("query").getAnnotation(McpTool.class).description()).contains("LIMIT");
    }

    private Method methodByName(String name) {
        for (Method m : CrudTools.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError("method not found: " + name);
    }
}
