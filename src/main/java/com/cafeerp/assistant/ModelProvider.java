package com.cafeerp.assistant;

/**
 * Configuration for a single model provider (OpenAI-compatible /chat/completions endpoint).
 *
 * <p>{@code model} is the model that answers chat turns (with ERP tools).
 * {@code titleModel} is the small/fast model used ONLY for sidebar-title
 * summarization — plain completions, no tools — so titles stay cheap and
 * never touch the agentic tool-calling pipeline. When unset it falls back
 * to {@code model} (see {@link #effectiveTitleModel()}).
 */
public record ModelProvider(
    String name,
    String baseUrl,
    String apiKeyEnvVar,
    String model,
    boolean supportsMinTokens,
    String titleModel
) {

    /** Compatibility constructor for callers that don't configure a title model. */
    public ModelProvider(String name, String baseUrl, String apiKeyEnvVar,
                         String model, boolean supportsMinTokens) {
        this(name, baseUrl, apiKeyEnvVar, model, supportsMinTokens, null);
    }

    /**
     * The model used for sidebar-title generation: the configured small/fast
     * title model, or the main chat model when no explicit title model is set.
     */
    public String effectiveTitleModel() {
        return titleModel != null && !titleModel.isBlank() ? titleModel : model;
    }

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