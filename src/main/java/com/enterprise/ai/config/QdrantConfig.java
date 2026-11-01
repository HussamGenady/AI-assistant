package com.enterprise.ai.config;

import io.qdrant.client.QdrantClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dedicated Qdrant collection for the semantic cache. The {@link QdrantClient}
 * bean itself is auto-configured from {@code spring.ai.vectorstore.qdrant.*}.
 * Chat memory uses its own payload-only collection (see QdrantChatMemoryRepository).
 */
@Configuration
public class QdrantConfig {

    @Bean(name = "semanticCacheVectorStore")
    public QdrantVectorStore semanticCacheVectorStore(QdrantClient qdrantClient,
                                                      EmbeddingModel embeddingModel,
                                                      AppProperties props) {
        return QdrantVectorStore.builder(qdrantClient, embeddingModel)
                .collectionName(props.semanticCache().collection())
                .initializeSchema(true)
                .build();
    }
}
