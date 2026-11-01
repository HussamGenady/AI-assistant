package com.enterprise.ai.tools;

import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

/**
 * Local web-search tool. The bean name ("tavilySearch") is what AIConfig registers
 * via defaultToolNames(...). DTOs are named TavilySearch* to avoid clashing with
 * Spring AI's vector-store SearchRequest.
 */
@Configuration
public class TavilyTools {

    public record TavilySearchRequest(
            @JsonProperty(required = true)
            @JsonPropertyDescription("The web search query, phrased as a concise search string")
            String query,
            @JsonPropertyDescription("Optional search topic: 'general' (default) or 'news'")
            String topic) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TavilySearchResponse(String answer, List<Result> results, String error) {
        public record Result(String title, String url, String content, Double score) {
        }
    }

    @Bean
    @Description("Search the live web for current or recent information (news, prices, releases, recent facts). "
            + "Returns a short synthesized answer plus source snippets with URLs.")
    public Function<TavilySearchRequest, TavilySearchResponse> tavilySearch(TavilyClient client) {
        return client::search;
    }
}
