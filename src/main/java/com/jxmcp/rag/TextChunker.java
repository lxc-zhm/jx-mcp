package com.jxmcp.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 定长切分带重叠：步长 = size - overlap；不足一块的尾巴作为最后一块。
 */
public class TextChunker {

    private final int size;
    private final int overlap;

    public TextChunker(int size, int overlap) {
        if (size <= 0 || overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException("要求 size > overlap >= 0，实际 size=%d overlap=%d".formatted(size, overlap));
        }
        this.size = size;
        this.overlap = overlap;
    }

    public List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            chunks.add("");
            return chunks;
        }
        int step = size - overlap;
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + size, text.length());
            chunks.add(text.substring(start, end));
            if (end == text.length()) {
                break;
            }
        }
        return chunks;
    }
}
