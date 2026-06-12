package com.sparrow.graph.api;

import java.util.List;

/**
 * Public read boundary used by AI indexing without exposing graph persistence.
 */
public interface TechNodeCatalog {

    record IndexableNode(Long id, String name, String era, String yearLabel,
                         String summary, String detail) {
    }

    List<IndexableNode> listIndexableNodes();
}
