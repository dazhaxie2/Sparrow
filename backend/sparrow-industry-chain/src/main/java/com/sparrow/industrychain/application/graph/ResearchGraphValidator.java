package com.sparrow.industrychain.application.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparrow.industrychain.infrastructure.llm.WebSearchClient.SearchSource;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class ResearchGraphValidator {

    public boolean valid(JsonNode graph, List<SearchSource> sources) {
        if (graph == null || !graph.path("nodes").isArray() || !graph.path("edges").isArray()
                || graph.path("nodes").isEmpty()) return false;
        Set<String> validRefs = new HashSet<>(sources.stream().map(SearchSource::sourceRef).toList());
        Set<String> nodeIds = new HashSet<>();
        for (JsonNode node : graph.path("nodes")) {
            if (node.path("id").asText().isBlank() || node.path("name").asText().isBlank()) return false;
            JsonNode refs = node.path("sourceRefs");
            if (!refs.isArray() || refs.isEmpty()) return false;
            for (JsonNode ref : refs) if (!validRefs.contains(ref.asText())) return false;
            nodeIds.add(node.path("id").asText());
        }
        for (JsonNode edge : graph.path("edges")) {
            if (!nodeIds.contains(edge.path("from").asText()) || !nodeIds.contains(edge.path("to").asText())) {
                return false;
            }
            JsonNode refs = edge.path("sourceRefs");
            if (!refs.isArray() || refs.isEmpty()) return false;
            for (JsonNode ref : refs) if (!validRefs.contains(ref.asText())) return false;
        }
        return true;
    }
}
