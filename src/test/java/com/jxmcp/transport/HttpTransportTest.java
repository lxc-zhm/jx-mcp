package com.jxmcp.transport;

import com.jxmcp.support.FixedEmbeddingTestConfig;
import com.jxmcp.support.PgTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 无 token 的 http profile：STREAMABLE 端点存在且可访问。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("http")
@Import(FixedEmbeddingTestConfig.class)
class HttpTransportTest extends PgTestBase {

    @LocalServerPort
    private int port;

    @Test
    void mcp_endpoint_exists_without_token() {
        int status = restClient().post().uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .body(initializeBody())
                .exchange((client, response) -> response.getStatusCode().value());
        assertThat(status).isNotEqualTo(404);
    }

    static String initializeBody() {
        return """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26",
                 "capabilities":{},"clientInfo":{"name":"t","version":"0"}}}
                """.stripIndent();
    }

    private RestClient restClient() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }
}
