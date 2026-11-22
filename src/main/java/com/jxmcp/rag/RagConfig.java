package com.jxmcp.rag;

import com.jxmcp.config.JxProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfig {

    @Bean
    public TextChunker textChunker(JxProperties props) {
        return new TextChunker(props.chunkSize(), props.chunkOverlap());
    }
}
