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
 * A row of `test_cases` (V6): the stdin fed to the program and the stdout it must produce. The
 * column `criterion_id` is nullable in the database, but the service always sets it to a `TESTS`
 * criterion of the same assignment.
 */
@Entity
@Table(name = "test_cases")
public class TestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "criterion_id")
    private Long criterionId;

    @Column(name = "name", nullable = false)
    private String name;

    /** The stdin; may be empty. */
    @Column(name = "input", nullable = false)
    private String input;

    /** The expected stdout, compared with trailing whitespace normalised; may be empty. */
    @Column(name = "expected_output", nullable = false)
    private String expectedOutput;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false)
    private TestVisibility visibility;

    /** Relative weight inside its criterion. */
    @Column(name = "weight", nullable = false)
    private int weight;

    /** Display and run order within the assignment. */
    @Column(name = "position", nullable = false)
    private int position;

    /** For JPA only. */
    protected TestCase() {}

    public TestCase(
            Long assignmentId,
            Long criterionId,
            String name,
            String input,
            String expectedOutput,
            TestVisibility visibility,
            int weight,
            int position) {
        this.assignmentId = assignmentId;
        this.criterionId = criterionId;
        this.name = name;
        this.input = input;
        this.expectedOutput = expectedOutput;
        this.visibility = visibility;
        this.weight = weight;
        this.position = position;
    }

    /** Replaces everything a teacher can edit; the caller has already checked what is locked. */
    public void revise(
            Long criterionId,
            String name,
            String input,
            String expectedOutput,
            TestVisibility visibility,
            int weight,
            int position) {
        this.criterionId = criterionId;
        this.name = name;
        this.input = input;
        this.expectedOutput = expectedOutput;
        this.visibility = visibility;
        this.weight = weight;
        this.position = position;
    }

    public Long getId() {
        return id;
    }

    public Long getAssignmentId() {
        return assignmentId;
    }

    public Long getCriterionId() {
        return criterionId;
    }

    public String getName() {
        return name;
    }

    public String getInput() {
        return input;
    }

    public String getExpectedOutput() {
        return expectedOutput;
    }

    public TestVisibility getVisibility() {
        return visibility;
    }

    public int getWeight() {
        return weight;
    }

    public int getPosition() {
        return position;
    }
}
