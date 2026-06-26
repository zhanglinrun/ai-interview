package com.linrun.interview.modules.llmprovider.dto;

public record DefaultProviderDTO(
    String defaultProvider,
    String defaultEmbeddingProvider
) {
    public DefaultProviderDTO(String defaultProvider) {
        this(defaultProvider, null);
    }
}
