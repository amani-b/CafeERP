package com.cafeerp.assistant;

/**
 * Configuration for a single model provider (OpenAI-compatible /chat/completions endpoint).
 */
public record ModelProvider(
    String name,
    String baseUrl,
    String apiKeyEnvVar,
    String model,
    boolean supportsMinTokens
) {

    public String url() {
        return baseUrl + "/chat/completions";
    }

    public String apiKey() {
        return System.getenv(apiKeyEnvVar);
    }

    public boolean hasApiKey() {
        String key = apiKey();
        return key != null && !key.isBlank();
    }
}