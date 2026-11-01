package com.enterprise.ai.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.enterprise.ai.config.AppProperties;
import com.enterprise.ai.tools.TavilyTools.TavilySearchRequest;
import com.enterprise.ai.tools.TavilyTools.TavilySearchResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class TavilyClient {

    private static final Logger log = LoggerFactory.getLogger(TavilyClient.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiResult(String title, String url, String content, Double score) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiResponse(String answer, List<ApiResult> results) {
    }

    private final RestClient rest;
    private final AppProperties.Tavily cfg;

    public TavilyClient(RestClient.Builder builder, AppProperties props) {
        this.cfg = props.tavily();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(cfg.connectTimeout());
        factory.setReadTimeout(cfg.readTimeout());
        this.rest = builder
                .baseUrl(cfg.baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.apiKey())
                .build();
    }

    public TavilySearchResponse search(TavilySearchRequest request) {
        if (!StringUtils.hasText(request.query())) {
            return new TavilySearchResponse(null, List.of(), "Empty query");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", request.query());
        body.put("search_depth", cfg.searchDepth());
        body.put("max_results", cfg.maxResults());
        body.put("include_answer", true);
        if (StringUtils.hasText(request.topic())) {
            body.put("topic", request.topic());
        }
        try {
            ApiResponse response = rest.post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ApiResponse.class);

            List<TavilySearchResponse.Result> results = response == null || response.results() == null
                    ? List.of()
                    : response.results().stream()
                    .map(r -> new TavilySearchResponse.Result(r.title(), r.url(), truncate(r.content()), r.score()))
                    .toList();
            return new TavilySearchResponse(response == null ? null : response.answer(), results, null);
        } catch (Exception e) {
            // Return the error to the model instead of failing the whole tool-calling loop.
            log.warn("Tavily search failed: {}", e.toString());
            return new TavilySearchResponse(null, List.of(), "Web search unavailable: " + e.getClass().getSimpleName());
        }
    }

    private String truncate(String s) {
        if (s == null || s.length() <= cfg.maxContentChars()) {
            return s;
        }
        return s.substring(0, cfg.maxContentChars()) + "...";
    }
}
