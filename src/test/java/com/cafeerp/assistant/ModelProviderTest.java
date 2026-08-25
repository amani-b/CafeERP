package com.cafeerp.assistant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests to ensure each configured provider's url() method
 * doesn't produce a doubled /chat/completions path.
 */
class ModelProviderTest {

    @Test
    void groqBaseUrl_shouldNotContainChatCompletionsSuffix() {
        // Groq baseUrl should end before /chat/completions
        String baseUrl = "https://api.groq.com/openai/v1";
        assertFalse(baseUrl.endsWith("/chat/completions"), 
            "Groq baseUrl should not end with /chat/completions");
        
        ModelProvider provider = new ModelProvider("groq", baseUrl, "GROQ_API_KEY", "openai/gpt-oss-120b", false);
        String url = provider.url();
        
        assertEquals("https://api.groq.com/openai/v1/chat/completions", url);
        // Ensure no doubled path
        assertFalse(url.contains("/chat/completions/chat/completions"), 
            "url() should not contain doubled /chat/completions");
    }

    @Test
    void geminiBaseUrl_shouldNotContainChatCompletionsSuffix() {
        // Gemini baseUrl should end before /chat/completions
        String baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";
        assertFalse(baseUrl.endsWith("/chat/completions"), 
            "Gemini baseUrl should not end with /chat/completions");
        
        ModelProvider provider = new ModelProvider("gemini", baseUrl, "GEMINI_API_KEY", "gemini-2.0-flash", false);
        String url = provider.url();
        
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", url);
        // Ensure no doubled path
        assertFalse(url.contains("/chat/completions/chat/completions"), 
            "url() should not contain doubled /chat/completions");
    }

    @Test
    void openrouterBaseUrl_shouldNotContainChatCompletionsSuffix() {
        // OpenRouter baseUrl should end before /chat/completions
        String baseUrl = "https://openrouter.ai/api/v1";
        assertFalse(baseUrl.endsWith("/chat/completions"), 
            "OpenRouter baseUrl should not end with /chat/completions");
        
        ModelProvider provider = new ModelProvider("openrouter", baseUrl, "OPENROUTER_API_KEY", "inclusionai/ling-3.0-flash:free", false);
        String url = provider.url();
        
        assertEquals("https://openrouter.ai/api/v1/chat/completions", url);
        // Ensure no doubled path
        assertFalse(url.contains("/chat/completions/chat/completions"), 
            "url() should not contain doubled /chat/completions");
    }

    @Test
    void url_shouldAlwaysAppendChatCompletionsOnce() {
        // Test that url() always appends exactly one /chat/completions
        String[] baseUrls = {
            "https://api.groq.com/openai/v1",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "https://openrouter.ai/api/v1",
            "https://example.com/api"
        };
        
        for (String baseUrl : baseUrls) {
            ModelProvider provider = new ModelProvider("test", baseUrl, "TEST_KEY", "test-model", false);
            String url = provider.url();
            
            assertTrue(url.endsWith("/chat/completions"), 
                "url() should end with /chat/completions for baseUrl: " + baseUrl);
            
            // Count occurrences of /chat/completions
            int count = 0;
            int index = 0;
            while ((index = url.indexOf("/chat/completions", index)) != -1) {
                count++;
                index += "/chat/completions".length();
            }
            assertEquals(1, count, 
                "url() should contain exactly one /chat/completions for baseUrl: " + baseUrl);
        }
    }
}
