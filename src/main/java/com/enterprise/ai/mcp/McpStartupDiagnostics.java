package com.enterprise.ai.mcp;

import java.util.List;

import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** After startup, logs how many MCP clients exist and what tools each one really exposes (or the error). */
@Component
public class McpStartupDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(McpStartupDiagnostics.class);

    private final ObjectProvider<List<McpSyncClient>> clients;

    public McpStartupDiagnostics(ObjectProvider<List<McpSyncClient>> clients) {
        this.clients = clients;
    }

    @EventListener(ApplicationReadyEvent.class)
    void report() {
        List<McpSyncClient> list = clients.getIfAvailable(List::of);
        log.info("[mcp] {} MCP client(s) configured", list.size());
        for (McpSyncClient c : list) {
            try {
                var tools = c.listTools().tools();
                log.info("[mcp] client exposes {} tool(s): {}", tools.size(),
                        tools.stream().map(t -> t.name()).toList());
            } catch (Exception e) {
                log.error("[mcp] listTools failed: {}", e.toString(), e);
            }
        }
    }
}
