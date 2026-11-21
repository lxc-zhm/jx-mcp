package com.jxmcp.support;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 离线确定性 embedding：1024 维，内容含 "apple" 首位 1，含 "banana" 次位 1，否则第三位 1。
 * 让相似度检索可断言，不依赖任何模型服务。
 */
public class FixedEmbeddingModel implements EmbeddingModel {

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> inputs = request.getInstructions();
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            embeddings.add(new Embedding(vectorFor(inputs.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return vectorFor(document.getText());
    }

    public static float[] vectorFor(String text) {
        float[] v = new float[1024];
        String t = text == null ? "" : text.toLowerCase();
        if (t.contains("apple")) {
            v[0] = 1.0f;
        } else if (t.contains("banana")) {
            v[1] = 1.0f;
        } else {
            v[2] = 1.0f;
        }
        return v;
    }
}
