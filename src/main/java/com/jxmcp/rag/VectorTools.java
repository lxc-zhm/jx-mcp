package com.jxmcp.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 向量工具：写入自动分块并 embedding；检索走 pgvector HNSW。
 * 写入自实现 SQL（id 由数据库 GENERATED ALWAYS identity 严格生成，PgVectorStore 的
 * BIGSERIAL 写入路径要求应用自带数字 id，与严格模式冲突）；读取复用 VectorStore。
 * 直接用 CRUD 工具改 vector_store 会绕过 embedding 导致内容与向量不一致。
 */
@Component
public class VectorTools {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final VectorStore store;
    private final EmbeddingModel model;
    private final TextChunker chunker;
    private final NamedParameterJdbcTemplate jdbc;

    public VectorTools(VectorStore store, EmbeddingModel model, TextChunker chunker,
                       NamedParameterJdbcTemplate jdbc) {
        this.store = store;
        this.model = model;
        this.chunker = chunker;
        this.jdbc = jdbc;
    }

    @McpTool(name = "vector_upsert", description =
            "写入/更新一个 RAG 文档：按块切分并自动 embedding 存入 pgvector。同 doc_id 重复调用为替换语义。"
                    + "切勿用 insert/update 直接写 vector_store 表——那会绕过 embedding，造成内容与向量不一致，"
                    + "如已直写，可用 reembed 维护命令修复。")
    @Transactional
    public String upsert(
            @McpToolParam(description = "文档唯一 ID，重复调用替换旧内容") String docId,
            @McpToolParam(description = "文档原文，长文本自动分块") String content,
            @McpToolParam(description = "可选元数据，如 {\"source\":\"file.md\",\"kind\":\"doc\"}", required = false)
            Map<String, Object> metadata) {
        List<String> chunks = chunker.chunk(content);
        Map<String, Object> base = metadata == null ? Map.of() : metadata;
        // 先向量化再入库：DELETE 与写入同事务，embedding 失败整体回滚不丢原文
        List<float[]> vectors = model.embed(chunks);
        List<MapSqlParameterSource> batch = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> meta = new HashMap<>(base);
            meta.put("doc_id", docId);
            batch.add(new MapSqlParameterSource()
                    .addValue("content", chunks.get(i))
                    .addValue("meta", toJson(meta))
                    .addValue("emb", vectorLiteral(vectors.get(i))));
        }
        jdbc.update("DELETE FROM vector_store WHERE metadata->>'doc_id' = :docId", Map.of("docId", docId));
        batch.forEach(p -> jdbc.update(
                "INSERT INTO vector_store (content, metadata, embedding) "
                        + "VALUES (:content, CAST(:meta AS json), CAST(:emb AS vector))", p));
        return "{\"doc_id\":\"%s\",\"chunks\":%d}".formatted(docId, batch.size());
    }

    @McpTool(name = "vector_search", description =
            "RAG 相似度检索：query 文本自动 embedding，按余弦相似度在 pgvector HNSW 索引上取 topK，"
                    + "可按元数据过滤（如 kind=doc）。vector_store 表的 embedding 由 upsert 工具生成，"
                    + "绕过 embedding 直写的数据会显著影响本工具结果。")
    public List<Map<String, Object>> search(
            @McpToolParam(description = "查询文本") String query,
            @McpToolParam(description = "返回条数，默认 5，最大 50") Integer topK,
            @McpToolParam(description = "元数据精确过滤，如 {\"kind\":\"doc\"}", required = false)
            Map<String, Object> metadataFilter) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(topK == null || topK <= 0 ? 5 : Math.min(topK, 50));
        if (metadataFilter != null && !metadataFilter.isEmpty()) {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            FilterExpressionBuilder.Op acc = null;
            for (Map.Entry<String, Object> e : metadataFilter.entrySet()) {
                acc = acc == null ? b.eq(e.getKey(), e.getValue()) : b.and(acc, b.eq(e.getKey(), e.getValue()));
            }
            builder.filterExpression(acc.build());
        }
        return store.similaritySearch(builder.build()).stream()
                .map(doc -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("content", doc.getText());
                    row.put("score", doc.getScore());
                    row.put("doc_id", doc.getMetadata().get("doc_id"));
                    row.put("metadata", new HashMap<>(doc.getMetadata()));
                    return row;
                })
                .toList();
    }

    @McpTool(name = "vector_delete", description =
            "按文档 ID 删除全部向量块（不删业务表数据）。"
                    + "删除后如需恢复检索，用 vector_upsert 重新写入。"
                    + "若用 SQL 直写 vector_store 绕过 embedding 造成内容与向量不一致，可用 reembed 维护命令修复。")
    public int delete(@McpToolParam(description = "文档唯一 ID") String docId) {
        return jdbc.update("DELETE FROM vector_store WHERE metadata->>'doc_id' = :docId",
                Map.of("docId", docId));
    }

    private String toJson(Map<String, Object> meta) {
        try {
            return JSON.writeValueAsString(meta);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("元数据序列化失败", e);
        }
    }

    private String vectorLiteral(float[] vec) {
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
