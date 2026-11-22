package com.jxmcp.maintenance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 原地重嵌维护命令（CLI，非 MCP 工具——避免 LLM 误触发批量重写）：
 * java -jar jx-mcp.jar --spring.profiles.active=reembed
 * 分批读取向量表原文 → 当前 provider 重新向量化 → 回写；幂等可重跑。
 */
@Component
@Profile("reembed")
public class ReembedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReembedRunner.class);
    private static final int DEFAULT_BATCH_SIZE = 100;

    private final NamedParameterJdbcTemplate jdbc;
    private final EmbeddingModel model;

    public ReembedRunner(NamedParameterJdbcTemplate jdbc, EmbeddingModel model) {
        this.jdbc = jdbc;
        this.model = model;
    }

    @Override
    public void run(ApplicationArguments args) {
        int processed = reembedAll(DEFAULT_BATCH_SIZE);
        log.info("reembed 完成，共处理 {} 行", processed);
    }

    public int reembedAll(int batchSize) {
        int total = 0;
        Long lastId = null;
        while (true) {
            MapSqlParameterSource params = new MapSqlParameterSource().addValue("batch", batchSize);
            String sql = "SELECT id, content FROM vector_store";
            if (lastId != null) {
                sql += " WHERE id > :lastId";
                params.addValue("lastId", lastId);
            }
            sql += " ORDER BY id LIMIT :batch";
            List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
            if (rows.isEmpty()) {
                break;
            }
            List<String> contents = rows.stream().map(r -> (String) r.get("content")).toList();
            List<float[]> vectors = model.embed(contents);
            for (int i = 0; i < rows.size(); i++) {
                Long id = ((Number) rows.get(i).get("id")).longValue();
                jdbc.update("UPDATE vector_store SET embedding = CAST(:vec AS vector) WHERE id = :id",
                        new MapSqlParameterSource()
                                .addValue("vec", vectorValue(vectors.get(i)))
                                .addValue("id", id));
                lastId = id;
            }
            total += rows.size();
            log.info("reembed 进度：已重嵌 {} 行", total);
        }
        return total;
    }

    private String vectorValue(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }
}
