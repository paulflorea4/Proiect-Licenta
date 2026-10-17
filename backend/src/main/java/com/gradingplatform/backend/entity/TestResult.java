package com.gradingplatform.backend.entity;

import com.gradingplatform.backend.sandbox.TestOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A row of `test_results` (V8): how one submission did on one test case (5.3a). The status is the
 * runner's {@link TestOutcome}, stored by name (its values are exactly the column's). **This is the
 * teacher's view: a hidden test's output is here in full**, so a student's view is built from the
 * masked types (5.5a), never from this entity.
 */
@Entity
@Table(name = "test_results")
public class TestResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "submission_id", nullable = false, updatable = false)
    private Long submissionId;

    @Column(name = "test_case_id", nullable = false, updatable = false)
    private Long testCaseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TestOutcome status;

    /** The program's stdout, already cut at the sandbox's output cap (4.3c). */
    @Column(name = "actual_output")
    private String actualOutput;

    @Column(name = "stderr")
    private String stderr;

    @Column(name = "runtime_ms")
    private Integer runtimeMs;

    /** For JPA only. */
    protected TestResult() {}

    public TestResult(
            Long submissionId,
            Long testCaseId,
            TestOutcome status,
            String actualOutput,
            String stderr,
            Integer runtimeMs) {
        this.submissionId = submissionId;
        this.testCaseId = testCaseId;
        this.status = status;
        this.actualOutput = actualOutput;
        this.stderr = stderr;
        this.runtimeMs = runtimeMs;
    }

    public Long getId() {
        return id;
    }

    public Long getSubmissionId() {
        return submissionId;
    }

    public Long getTestCaseId() {
        return testCaseId;
    }

    public TestOutcome getStatus() {
        return status;
    }

    public String getActualOutput() {
        return actualOutput;
    }

    public String getStderr() {
        return stderr;
    }

    public Integer getRuntimeMs() {
        return runtimeMs;
    }

    /** Shows lengths only: the output of a hidden test must not reach a log. */
    @Override
    public String toString() {
        return "TestResult[id=" + id + ", submissionId=" + submissionId + ", testCaseId=" + testCaseId + ", status="
                + status + ", actualOutput=<" + (actualOutput == null ? "null" : actualOutput.length() + " chars")
                + ">, stderr=<" + (stderr == null ? "null" : stderr.length() + " chars") + ">, runtimeMs="
                + runtimeMs + "]";
    }
}
