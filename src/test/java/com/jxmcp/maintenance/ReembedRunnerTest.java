package com.jxmcp.maintenance;

import com.jxmcp.support.FixedEmbeddingModel;
import com.jxmcp.support.PgTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReembedRunnerTest extends PgTestBase {

    private NamedParameterJdbcTemplate jdbc;
    private ReembedRunner runner;

    @BeforeEach
    void setUp() {
        jdbc = new NamedParameterJdbcTemplate(appDataSource());
        runner = new ReembedRunner(jdbc, new FixedEmbeddingModel());
        jdbc.getJdbcTemplate().execute("DELETE FROM vector_store");
    }

    private UUID insertRow(String content) {
        // 插入与内容无关的旧向量（全 9），重嵌后必须变成 FixedEmbeddingModel 的确定性向量
        float[] old = new float[1024];
        java.util.Arrays.fill(old, 9.0f);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO vector_store (id, content, embedding) VALUES (:id, :content, CAST(:emb AS vector))",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("content", content)
                        .addValue("emb", toVectorLiteral(old)));
        return id;
    }

    private static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private float[] readEmbedding(UUID id) {
        String text = jdbc.queryForObject(
                "SELECT embedding::text FROM vector_store WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id), String.class);
        String[] parts = text.replaceAll("[\\[\\]]", "").split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Float.parseFloat(parts[i]);
        }
        return out;
    }

    @Test
    void reembed_rewrites_all_embeddings() {
        UUID a = insertRow("apple pie");
        UUID b = insertRow("banana split");
        UUID c = insertRow("cherry cake");

        int processed = runner.reembedAll(2);
        assertThat(processed).isEqualTo(3);

        float[] ea = readEmbedding(a);
        assertThat(ea[0]).isEqualTo(1.0f);   // FixedEmbeddingModel: apple → 首位 1
        assertThat(ea[1]).isZero();
        float[] eb = readEmbedding(b);
        assertThat(eb[1]).isEqualTo(1.0f);   // banana → 次位 1
        float[] ec = readEmbedding(c);
        assertThat(ec[2]).isEqualTo(1.0f);   // 其他 → 第三位 1

        List<String> contents = jdbc.queryForList("SELECT content FROM vector_store ORDER BY id",
                java.util.Map.of(), String.class);
        assertThat(contents).containsExactlyInAnyOrder("apple pie", "banana split", "cherry cake");
    }

    @Test
    void reembed_is_idempotent() {
        insertRow("apple pie");
        insertRow("banana split");
        insertRow("cherry cake");

        assertThat(runner.reembedAll(2)).isEqualTo(3);
        assertThat(runner.reembedAll(2)).isEqualTo(3);
        Integer rows = jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM vector_store", Integer.class);
        assertThat(rows).isEqualTo(3);
    }
}
