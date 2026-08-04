package com.feisheng.bot.admin.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Vector math utilities: cosine similarity and JSON serialization.
 */
public class VectorUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Cosine similarity between two float arrays. Range: [-1, 1], higher = more similar. */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** Convert float[] to JSON string for DB storage. */
    public static String toJson(float[] vec) {
        try { return MAPPER.writeValueAsString(vec); }
        catch (JsonProcessingException e) { return "[]"; }
    }

    /** Convert JSON string from DB to float[]. */
    public static float[] fromJson(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) return new float[0];
        try {
            List<Double> list = MAPPER.readValue(json, new TypeReference<List<Double>>() {});
            float[] arr = new float[list.size()];
            for (int i = 0; i < list.size(); i++) arr[i] = list.get(i).floatValue();
            return arr;
        } catch (Exception e) { return new float[0]; }
    }

    /** Batch convert: JSON string list to float[][] */
    public static List<float[]> batchFromJson(List<String> jsonList) {
        List<float[]> result = new ArrayList<>();
        for (String json : jsonList) result.add(fromJson(json));
        return result;
    }
}
