package com.sparrow.graph.domain.model;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.ArrayList;
import java.util.List;

@Node("TechNode")
public class NeoTechNode {

    @Id
    private Long id;

    @Property
    private String code;

    @Property
    private String name;

    @Property
    private String era;

    @Property("era_rank")
    private Integer eraRank;

    @Property("year_label")
    private String yearLabel;

    @Property
    private String summary;

    @Property
    private String detail;

    @Property
    private Boolean premium;

    @Property
    private String category;

    @Property
    private Integer importance;

    @Relationship(type = "REQUIRES", direction = Relationship.Direction.OUTGOING)
    private List<NeoTechNode> requires = new ArrayList<>();

    public NeoTechNode() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEra() { return era; }
    public void setEra(String era) { this.era = era; }
    public Integer getEraRank() { return eraRank; }
    public void setEraRank(Integer eraRank) { this.eraRank = eraRank; }
    public String getYearLabel() { return yearLabel; }
    public void setYearLabel(String yearLabel) { this.yearLabel = yearLabel; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Boolean getPremium() { return premium; }
    public void setPremium(Boolean premium) { this.premium = premium; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Integer getImportance() { return importance; }
    public void setImportance(Integer importance) { this.importance = importance; }
    public List<NeoTechNode> getRequires() { return requires; }
    public void setRequires(List<NeoTechNode> requires) { this.requires = requires; }
}
