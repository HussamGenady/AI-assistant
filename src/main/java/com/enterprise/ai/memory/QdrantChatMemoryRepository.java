package com.enterprise.ai.config.memory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import com.enterprise.ai.config.AppProperties;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Persistent conversation memory in Qdrant, keyed by conversationId.
 *
 * Payload-only collection: each point carries a 1-dim dummy vector, so no embedding calls are made
 * for memory. Reads use a payload-filtered scroll, ordered by "seq".
 *
 * saveAll() has replace semantics (that is what MessageWindowChatMemory expects).
 * For large deployments, add a keyword payload index on "conversationId".
 */
@Component
public class QdrantChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(QdrantChatMemoryRepository.class);

    private static final String F_CONV = "conversationId";
    private static final String F_SEQ = "seq";
    private static final String F_ROLE = "role";
    private static final String F_CONTENT = "content";
    private static final String F_TS = "timestamp";

    private final QdrantClient client;
    private final String collection;

    public QdrantChatMemoryRepository(QdrantClient client, AppProperties props) {
        this.client = client;
        this.collection = props.memory().collection();
    }

    @PostConstruct
    void ensureCollection() {
        boolean exists = await(client.collectionExistsAsync(collection));
        if (!exists) {
            await(client.createCollectionAsync(collection,
                    VectorParams.newBuilder().setSize(1).setDistance(Distance.Cosine).build()));
            log.info("Created Qdrant chat-memory collection '{}'", collection);
        }
    }

    @Override
    public List<String> findConversationIds() {
        Set<String> ids = new LinkedHashSet<>();
        scroll(null, List.of(F_CONV), p -> ids.add(p.getPayloadMap().get(F_CONV).getStringValue()));
        return List.copyOf(ids);
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        Assert.hasText(conversationId, "conversationId must not be empty");
        List<RetrievedPoint> points = new ArrayList<>();
        scroll(conversationFilter(conversationId), null, points::add);
        return points.stream()
                .sorted(Comparator.comparingLong(p -> p.getPayloadMap().get(F_SEQ).getIntegerValue()))
                .map(this::toMessage)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "conversationId must not be empty");
        deleteByConversationId(conversationId);

        List<PointStruct> points = new ArrayList<>();
        long now = System.currentTimeMillis();
        int seq = 0;
        for (Message m : messages) {
            String role = roleOf(m);
            if (role == null || !StringUtils.hasText(m.getText())) {
                continue; // tool / empty messages are not persisted
            }
            UUID id = UUID.nameUUIDFromBytes((conversationId + ":" + seq).getBytes(StandardCharsets.UTF_8));
            points.add(PointStruct.newBuilder()
                    .setId(PointIdFactory.id(id))
                    .setVectors(VectorsFactory.vectors(1.0f))
                    .putPayload(F_CONV, ValueFactory.value(conversationId))
                    .putPayload(F_SEQ, ValueFactory.value(seq))
                    .putPayload(F_ROLE, ValueFactory.value(role))
                    .putPayload(F_CONTENT, ValueFactory.value(m.getText()))
                    .putPayload(F_TS, ValueFactory.value(now))
                    .build());
            seq++;
        }
        if (!points.isEmpty()) {
            await(client.upsertAsync(collection, points));
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        Assert.hasText(conversationId, "conversationId must not be empty");
        await(client.deleteAsync(collection, conversationFilter(conversationId)));
    }

    // ------------------------------------------------------------------ helpers

    private void scroll(Points.Filter filter, List<String> payloadFields, Consumer<RetrievedPoint> sink) {
        ScrollPoints.Builder request = ScrollPoints.newBuilder()
                .setCollectionName(collection)
                .setLimit(256)
                .setWithPayload(payloadFields == null
                        ? WithPayloadSelectorFactory.enable(true)
                        : WithPayloadSelectorFactory.include(payloadFields));
        if (filter != null) {
            request.setFilter(filter);
        }
        while (true) {
            ScrollResponse page = await(client.scrollAsync(request.build()));
            page.getResultList().forEach(sink);
            if (!page.hasNextPageOffset()) {
                break;
            }
            request.setOffset(page.getNextPageOffset());
        }
    }

    private static Points.Filter conversationFilter(String conversationId) {
        return Points.Filter.newBuilder()
                .addMust(ConditionFactory.matchKeyword(F_CONV, conversationId))
                .build();
    }

    private static String roleOf(Message m) {
        if (m instanceof UserMessage) return "USER";
        if (m instanceof AssistantMessage) return "ASSISTANT";
        if (m instanceof SystemMessage) return "SYSTEM";
        return null;
    }

    private Message toMessage(RetrievedPoint p) {
        String role = p.getPayloadMap().get(F_ROLE).getStringValue();
        String content = p.getPayloadMap().get(F_CONTENT).getStringValue();
        return switch (role) {
            case "USER" -> new UserMessage(content);
            case "ASSISTANT" -> new AssistantMessage(content);
            case "SYSTEM" -> new SystemMessage(content);
            default -> null;
        };
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Qdrant", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Qdrant call failed", e);
        }
    }
}
