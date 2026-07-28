package com.cafeerp.assistant;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "assistant")
public class AssistantConfigProperties {

    /**
     * Ordered list of model providers to try. Each entry defines name, baseUrl,
     * apiKeyEnvVar, model, and supportsMinTokens.
     */
    private List<ProviderConfig> providers = List.of();

    public List<ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(List<ProviderConfig> providers) {
        this.providers = providers;
    }

    public static class ProviderConfig {
        private String name;
        private String baseUrl;
        private String apiKeyEnvVar;
        private String model;
        private boolean supportsMinTokens = true;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKeyEnvVar() { return apiKeyEnvVar; }
        public void setApiKeyEnvVar(String apiKeyEnvVar) { this.apiKeyEnvVar = apiKeyEnvVar; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public boolean isSupportsMinTokens() { return supportsMinTokens; }
        public void setSupportsMinTokens(boolean supportsMinTokens) { this.supportsMinTokens = supportsMinTokens; }
    }
}