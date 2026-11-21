package com.jxmcp.rag;

import com.jxmcp.JxMcpApplication;
import com.jxmcp.support.FixedEmbeddingModel;
import com.jxmcp.support.PgTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"jx.chunk-size=50", "jx.chunk-overlap=10"})
@Import(VectorToolsTest.FixedEmbeddingConfig.class)
class VectorToolsTest extends PgTestBase {

    @Autowired
    private VectorTools vectorTools;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @TestConfiguration
    static class FixedEmbeddingConfig {
        @Bean
        @Primary
        FixedEmbeddingModel fixedEmbeddingModel() {
            return new FixedEmbeddingModel();
        }
    }

    private void clearVectorStore() {
        jdbc.getJdbcTemplate().execute("DELETE FROM vector_store");
    }

    @Test
    void upsert_then_search_top1_relevant() {
        clearVectorStore();
        vectorTools.upsert("doc-apple", "I eat an apple every day", Map.of("kind", "fruit"));
        vectorTools.upsert("doc-banana", "The banana is yellow", Map.of("kind", "fruit"));

        List<Map<String, Object>> hits = vectorTools.search("apple", 2, null);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).get("doc_id")).isEqualTo("doc-apple");
    }

    @Test
    void long_content_chunked_shares_doc_id() {
        clearVectorStore();
        String longText = "y".repeat(120);
        String result = vectorTools.upsert("doc-long", longText, Map.of());
        assertThat(result).contains("\"doc_id\":\"doc-long\"").contains("\"chunks\":3");

        Integer rows = jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM vector_store", Integer.class);
        assertThat(rows).isEqualTo(3);
        Integer distinct = jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(DISTINCT metadata->>'doc_id') FROM vector_store", Integer.class);
        assertThat(distinct).isEqualTo(1);
    }

    @Test
    void search_with_metadata_filter() {
        clearVectorStore();
        vectorTools.upsert("doc-apple", "apple pie recipe", Map.of("kind", "food"));
        vectorTools.upsert("doc-iphone", "iPhone 17 review", Map.of("kind", "tech"));

        List<Map<String, Object>> hits = vectorTools.search("apple", 5, Map.of("kind", "food"));
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).get("doc_id")).isEqualTo("doc-apple");
    }

    @Test
    void delete_by_doc_id_removes_all_chunks() {
        clearVectorStore();
        vectorTools.upsert("doc-doomed", "z".repeat(90), Map.of());
        Integer before = jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM vector_store", Integer.class);
        assertThat(before).isEqualTo(2);

        int removed = vectorTools.delete("doc-doomed");
        assertThat(removed).isEqualTo(2);
        Integer after = jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM vector_store", Integer.class);
        assertThat(after).isZero();
    }

    @Test
    void tool_descriptions_warn_vector_bypass() throws Exception {
        for (String method : List.of("upsert", "search", "delete")) {
            var ann = VectorTools.class.getMethod(method,
                    method.equals("upsert") ? new Class<?>[]{String.class, String.class, Map.class}
                            : method.equals("search") ? new Class<?>[]{String.class, Integer.class, Map.class}
                            : new Class<?>[]{String.class})
                    .getAnnotation(org.springframework.ai.mcp.annotation.McpTool.class);
            assertThat(ann).as("method %s", method).isNotNull();
            assertThat(ann.description()).contains("embedding");
        }
    }
}
