-- 向量表的 DDL 由本特权脚本一次性负责；应用以受限角色 + initialize-schema=false 运行
CREATE TABLE IF NOT EXISTS vector_store (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(1024)
);

-- HNSW 索引 + 余弦距离；换 embedding 模型时的维度变更见 README
CREATE INDEX IF NOT EXISTS vector_store_embedding_hnsw_idx
    ON vector_store USING hnsw (embedding vector_cosine_ops);
