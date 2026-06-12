package com.sparrow.graph.api;

import com.sparrow.graph.api.dto.GraphDtos.NodeBrief;
import com.sparrow.graph.api.dto.GraphDtos.NodeDetail;
import com.sparrow.graph.api.dto.GraphDtos.Tree;

import java.util.List;

/**
 * Public query boundary for graph consumers inside the modular monolith.
 */
public interface GraphQueryService {

    Tree tree();

    NodeDetail nodeDetail(Long id, Long userId);

    List<NodeBrief> prerequisiteChain(Long id);
}
