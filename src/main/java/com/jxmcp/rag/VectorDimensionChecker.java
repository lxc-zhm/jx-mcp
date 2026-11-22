package com.jxmcp.rag;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动时校验 vector_store.embedding 列的维度与所配 embedding 模型维度一致。
 * 不一致直接拒启——防止换模型后静默写入错维度或检索质量劣化。
 */
@Component
public class VectorDimensionChecker {

    private static final Pattern VECTOR_TYPE = Pattern.compile("vector\\s*\\((\\d+)\\)");

    private final JdbcTemplate jdbc;
    private final int configuredDim;

    public VectorDimensionChecker(JdbcTemplate jdbc,
                                  @Value("${spring.ai.vectorstore.pgvector.dimensions:1024}") int configuredDim) {
        this.jdbc = jdbc;
        this.configuredDim = configuredDim;
    }

    @PostConstruct
    void check() {
        List<String> types = jdbc.queryForList("""
                SELECT format_type(a.atttypid, a.atttypmod)
                FROM pg_attribute a
                JOIN pg_class c ON a.attrelid = c.oid
                JOIN pg_namespace n ON c.relnamespace = n.oid
                WHERE c.relname = 'vector_store' AND a.attname = 'embedding'
                  AND n.nspname = 'public' AND a.attnum > 0 AND NOT a.attisdropped
                """, String.class);
        if (types.isEmpty()) {
            throw new IllegalStateException(
                    "vector_store 表不存在或缺少 embedding 列——请先由管理员执行 db-init/01、02 脚本（应用以受限角色运行，不做 initialize-schema）");
        }
        Matcher m = VECTOR_TYPE.matcher(types.getFirst());
        if (!m.matches()) {
            throw new IllegalStateException("embedding 列类型异常: " + types.getFirst() + "，应为 vector(N)");
        }
        int actualDim = Integer.parseInt(m.group(1));
        if (actualDim != configuredDim) {
            throw new IllegalStateException(
                    "向量维度不一致：vector_store.embedding 为 " + actualDim
                            + " 维，配置的 embedding 模型维度为 " + configuredDim
                            + " 维。请调整 EMBEDDING_DIM 或按 README 的换模型流程迁移向量列");
        }
    }
}
