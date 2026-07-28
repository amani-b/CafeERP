package com.cafeerp.assistant;

/**
 * Thin seam over the HTTP call to an OpenAI-compatible /chat/completions endpoint.
 * Extracted so the failover and tool-call-validation logic in {@link AssistantService}
 * can be exercised in tests without real network access.
 */
public interface ChatCompletionClient {

    /**
     * POST a chat-completions request body.
     *
     * @return the raw HTTP status and response body
     * @throws Exception on transport failure (timeout, DNS, connection reset, ...)
     */
    Result post(String url, String apiKey, String jsonBody) throws Exception;

    record Result(int status, String body) {}
}
