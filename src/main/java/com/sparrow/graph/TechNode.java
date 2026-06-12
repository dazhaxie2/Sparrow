package com.sparrow.graph;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "tech_node")
public class TechNode {

    @Id
    private Long id;

    @Column(name = "code")
    private String code;

    @Column(name = "name")
    private String name;

    @Column(name = "era")
    private String era;

    @Column(name = "era_rank")
    private Integer eraRank;

    @Column(name = "year_label")
    private String yearLabel;

    @Column(name = "summary")
    private String summary;

    @Column(name = "detail")
    private String detail;

    @Column(name = "premium")
    private Boolean premium;

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getEra() {
        return era;
    }

    public Integer getEraRank() {
        return eraRank;
    }

    public String getYearLabel() {
        return yearLabel;
    }

    public String getSummary() {
        return summary;
    }

    public String getDetail() {
        return detail;
    }

    public Boolean getPremium() {
        return premium;
    }
}
