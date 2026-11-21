package com.jxmcp.support;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.stream.Stream;

/**
 * 测试基建：起 pgvector 容器 → admin 按序执行 db-init/*.sql → 测试用受限角色 jx_mcp_app 连接。
 */
@Testcontainers
public abstract class PgTestBase {

    public static final String APP_USER = "jx_mcp_app";
    public static final String APP_PASSWORD = "jx_mcp_app";

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("jx")
                    .withUsername("admin")
                    .withPassword("admin");

    @BeforeAll
    static void runDbInit() throws Exception {
        DataSource admin = adminDataSource();
        try (Connection conn = admin.getConnection();
             Stream<Path> files = Files.list(Path.of("db-init"))) {
            List<Path> scripts = files.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
            for (Path script : scripts) {
                ScriptUtils.executeSqlScript(conn, new FileSystemResource(script.toFile()));
            }
        }
    }

    public static DataSource adminDataSource() {
        return DataSourceBuilder.create()
                .url(POSTGRES.getJdbcUrl())
                .username("admin")
                .password("admin")
                .build();
    }

    public static DataSource appDataSource() {
        return DataSourceBuilder.create()
                .url(POSTGRES.getJdbcUrl())
                .username(APP_USER)
                .password(APP_PASSWORD)
                .build();
    }

    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    @org.springframework.test.context.DynamicPropertySource
    static void pgProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
    }
}
