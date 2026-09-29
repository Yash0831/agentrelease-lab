package com.agentreleaselab.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/** Embedding provider abstraction.
 *
 *  <p><b>fixture</b> mode: deterministic SHA-256-derived pseudo-embeddings
 *  (384 dims, L2-normalized). Labeled as fixture everywhere; measures pipeline
 *  behavior, not semantic retrieval quality (ADR-0003/0004).
 *
 *  <p>A live deployment would implement {@code embedLive} against a real
 *  embedding model and set {@code arl.embedding-mode=live}. */
@Service
public class EmbeddingService {

    public static final int DIMS = 384;

    /** Deterministic pseudo-embedding: SHA-256(text || ':' || dim) bytes mapped to [-1,1], normalized. */
    public float[] embedFixture(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            float[] vec = new float[DIMS];
            for (int i = 0; i < DIMS; i++) {
                byte[] h = md.digest((text + ":" + i).getBytes(StandardCharsets.UTF_8));
                int v = ((h[0] & 0xFF) << 8) | (h[1] & 0xFF);
                vec[i] = (v / 32767.5f) - 1.0f;
            }
            float norm = 0f;
            for (float v : vec) norm += v * v;
            norm = (float) Math.sqrt(norm);
            for (int i = 0; i < DIMS; i++) vec[i] /= norm;
            return vec;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** pgvector text literal: '[0.1,0.2,...]'. */
    public String toVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }

    /** Cosine similarity for in-Java scoring (used by tests/debugging). */
    public static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot; // inputs are normalized
    }

    public static void main(String[] args) {
        // quick determinism smoke check
        EmbeddingService s = new EmbeddingService();
        System.out.println(Arrays.equals(s.embedFixture("hello"), s.embedFixture("hello")));
    }
}
