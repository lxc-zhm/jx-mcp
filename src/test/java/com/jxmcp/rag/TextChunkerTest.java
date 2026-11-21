package com.jxmcp.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTest {

    @Test
    void chunk_respects_size_and_overlap() {
        TextChunker chunker = new TextChunker(50, 10);
        String text = "x".repeat(120);
        List<String> chunks = chunker.chunk(text);
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).hasSize(50);
        assertThat(chunks.get(1)).hasSize(50);
        assertThat(chunks.get(2)).hasSize(40);
        // 相邻块重叠 10 字符
        assertThat(chunks.get(0).substring(40)).isEqualTo(chunks.get(1).substring(0, 10));
        assertThat(chunks.get(1).substring(40)).isEqualTo(chunks.get(2).substring(0, 10));
    }

    @Test
    void empty_text_single_chunk() {
        assertThat(new TextChunker(50, 10).chunk("")).hasSize(1);
    }
}
