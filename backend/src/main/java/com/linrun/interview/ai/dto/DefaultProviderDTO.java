package com.linrun.interview.ai.dto;

public record DefaultProviderDTO(
    String defaultProvider,
    String defaultEmbeddingProvider
) {
    public DefaultProviderDTO(String defaultProvider) {
        this(defaultProvider, null);
    }
}
