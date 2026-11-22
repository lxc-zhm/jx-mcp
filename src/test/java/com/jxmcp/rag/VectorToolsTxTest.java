package com.jxmcp.rag;

import com.jxmcp.support.FixedEmbeddingTestConfig;
import com.jxmcp.support.PgTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

/**
 * I-2 回归钉：embedding 调用失败时，upsert 不得丢失已有向量数据。
 */
@SpringBootTest(properties = {"jx.chunk-size=50", "jx.chunk-overlap=10"})
@Import(FixedEmbeddingTestConfig.class)
class VectorToolsTxTest extends PgTestBase {

    @Autowired
    private VectorTools vectorTools;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @MockitoBean
    private EmbeddingModel model;

    @Test
    void upsert_failure_keeps_old_rows() {
        jdbc.getJdbcTemplate().execute("DELETE FROM vector_store");
        // 直接 jdbc 播种旧数据
        jdbc.update("INSERT INTO vector_store (content, metadata) VALUES (:c, CAST(:m AS json))",
                java.util.Map.of("c", "old-1", "m", "{\"doc_id\":\"doc-keep\"}"));
        jdbc.update("INSERT INTO vector_store (content, metadata) VALUES (:c, CAST(:m AS json))",
                java.util.Map.of("c", "old-2", "m", "{\"doc_id\":\"doc-keep\"}"));
        Integer before = jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM vector_store", Integer.class);
        assertThat(before).isEqualTo(2);

        doThrow(new RuntimeException("embedding down")).when(model).embed(anyList());
        assertThatThrownBy(() -> vectorTools.upsert("doc-keep", "new content", java.util.Map.of()))
                .isInstanceOf(RuntimeException.class);

        Integer after = jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM vector_store", Integer.class);
        assertThat(after).isEqualTo(2);
    }
}
