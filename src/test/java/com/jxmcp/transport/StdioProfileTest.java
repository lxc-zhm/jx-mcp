package com.jxmcp.transport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class StdioProfileTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void stdio_profile_is_non_web_with_file_logging() {
        runner.withPropertyValues("spring.profiles.active=stdio").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("spring.main.web-application-type")).isEqualTo("none");
            assertThat(context.getEnvironment().getProperty("spring.ai.mcp.server.stdio")).isEqualTo("true");
            assertThat(context.getEnvironment().getProperty("logging.file.name")).isEqualTo("logs/jx-mcp.log");
        });
    }
}
