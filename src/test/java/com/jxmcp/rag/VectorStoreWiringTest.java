package com.jxmcp.rag;

import com.jxmcp.support.PgTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class VectorStoreWiringTest extends PgTestBase {

    private ApplicationContextRunner baseRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class,
                        PgVectorStoreAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=" + jdbcUrl(),
                        "spring.datasource.username=" + APP_USER,
                        "spring.datasource.password=" + APP_PASSWORD,
                        "spring.ai.vectorstore.pgvector.initialize-schema=false",
                        "spring.ai.vectorstore.pgvector.index-type=HNSW",
                        "spring.ai.vectorstore.pgvector.distance-type=COSINE_DISTANCE")
                .withBean(VectorDimensionChecker.class);
    }

    private ApplicationContextRunner withEmbedding(String provider, String... extra) {
        ApplicationContextRunner runner = baseRunner()
                .withPropertyValues(
                        "spring.ai.model.embedding=" + provider,
                        "spring.ai.vectorstore.pgvector.dimensions=1024");
        if (extra.length > 0) {
            runner = runner.withPropertyValues(extra);
        }
        return runner.withConfiguration(AutoConfigurations.of(
                org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration.class,
                OllamaEmbeddingAutoConfiguration.class,
                OpenAiEmbeddingAutoConfiguration.class));
    }

    @Test
    void context_boots_with_matching_dimension() {
        withEmbedding("ollama", "spring.ai.ollama.embedding.options.model=bge-m3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(VectorStore.class);
                    assertThat(context).hasSingleBean(VectorDimensionChecker.class);
                });
    }

    @Test
    void context_fails_on_dimension_mismatch() {
        withEmbedding("ollama", "spring.ai.vectorstore.pgvector.dimensions=1536")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("1024")
                            .hasMessageContaining("1536");
                });
    }

    @Test
    void embedding_provider_selection() {
        withEmbedding("ollama")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EmbeddingModel.class).getClass().getSimpleName())
                            .isEqualTo("OllamaEmbeddingModel");
                });
        // 智谱无官方 starter（Task 1 裁定）：走 OpenAI 兼容端点预设，不再发起真实 API 调用
        withEmbedding("openai",
                        "spring.ai.openai.api-key=test-dummy",
                        "spring.ai.openai.base-url=https://open.bigmodel.cn/api/paas/v4")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EmbeddingModel.class).getClass().getSimpleName())
                            .isEqualTo("OpenAiEmbeddingModel");
                });
    }
}
