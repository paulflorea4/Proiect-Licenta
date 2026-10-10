package com.gradingplatform.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A row of `rubric_criteria` (V5). The `config` column is not mapped: no criterion type has
 * parameters yet, and the phase that adds one maps it.
 */
@Entity
@Table(name = "rubric_criteria")
public class RubricCriterion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private CriterionType type;

    /** Whole points out of 100 for the assignment. */
    @Column(name = "weight", nullable = false)
    private int weight;

    /** For JPA only. */
    protected RubricCriterion() {}

    public RubricCriterion(Long assignmentId, String name, CriterionType type, int weight) {
        this.assignmentId = assignmentId;
        this.name = name;
        this.type = type;
        this.weight = weight;
    }

    /** Replaces what a teacher can edit; the caller has already checked what is locked. */
    public void revise(String name, CriterionType type, int weight) {
        this.name = name;
        this.type = type;
        this.weight = weight;
    }

    public Long getId() {
        return id;
    }

    public Long getAssignmentId() {
        return assignmentId;
    }

    public String getName() {
        return name;
    }

    public CriterionType getType() {
        return type;
    }

    public int getWeight() {
        return weight;
    }
}
