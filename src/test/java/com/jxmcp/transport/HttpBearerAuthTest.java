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
 * 配置 jx.http-token 后：/mcp 需 Bearer Token。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "jx.http-token=secret")
@org.springframework.test.context.ActiveProfiles("http")
@Import(FixedEmbeddingTestConfig.class)
class HttpBearerAuthTest extends PgTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private RestClient.Builder restBuilder;

    private int postWithToken(String token) {
        var spec = restBuilder.clone().baseUrl("http://localhost:" + port).build().post().uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .body(HttpTransportTest.initializeBody());
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        return spec.exchange((client, response) -> response.getStatusCode().value());
    }

    @Test
    void bearer_rejected_without_or_wrong_token() {
        assertThat(postWithToken(null)).isEqualTo(401);
        assertThat(postWithToken("wrong")).isEqualTo(401);
    }

    @Test
    void bearer_accepted_with_right_token() {
        assertThat(postWithToken("secret")).isNotEqualTo(401);
    }
}
