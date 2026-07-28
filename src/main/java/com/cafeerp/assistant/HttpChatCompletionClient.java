package com.cafeerp.assistant;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Component;

/**
 * Default {@link ChatCompletionClient} backed by the JDK HTTP client.
 */
@Component
public class HttpChatCompletionClient implements ChatCompletionClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;

    public HttpChatCompletionClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    @Override
    public Result post(String url, String apiKey, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(), response.body());
    }
}
