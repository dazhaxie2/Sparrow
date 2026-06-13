package com.sparrow.graph.infrastructure.neo4j;

import com.sparrow.graph.domain.model.NeoTechNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.List;
import java.util.Optional;

public interface NeoTechNodeRepository extends Neo4jRepository<NeoTechNode, Long> {

    @Query("MATCH (n:TechNode) RETURN n ORDER BY n.era_rank, n.id")
    List<NeoTechNode> findAllOrdered();

    @Query("MATCH (n:TechNode) WHERE n.id = $id RETURN n")
    Optional<NeoTechNode> findByNodeId(Long id);

    @Query("MATCH (a:TechNode)-[:REQUIRES]->(b:TechNode) RETURN a.id AS fromId, b.id AS toId")
    List<NeoEdgeRecord> findAllEdges();

    @Query("""
            MATCH (n:TechNode {id: $id})-[:REQUIRES*]->(pre)
            RETURN DISTINCT pre
            ORDER BY pre.era_rank, pre.id
            """)
    List<NeoTechNode> findAllPrerequisites(Long id);

    @Query("""
            MATCH (n:TechNode {id: $id})<-[:REQUIRES]-(unlock)
            RETURN unlock
            ORDER BY unlock.era_rank, unlock.id
            """)
    List<NeoTechNode> findDirectUnlocks(Long id);

    @Query("""
            MATCH (n:TechNode {id: $id})-[:REQUIRES]->(direct)
            RETURN direct
            ORDER BY direct.era_rank, direct.id
            """)
    List<NeoTechNode> findDirectPrerequisites(Long id);

    @Query("MATCH (n:TechNode) WHERE n.id = $id RETURN COUNT(n) > 0")
    boolean existsByNodeId(Long id);

    @Query("MATCH (n:TechNode) RETURN COUNT(n)")
    long countAll();

    @Query("MATCH ()-[r:REQUIRES]->() RETURN COUNT(r)")
    long countEdges();

    @Query("MATCH (n:TechNode) DETACH DELETE n")
    void deleteAllNodes();
}
