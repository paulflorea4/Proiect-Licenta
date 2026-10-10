package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.Assignment;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    /** Every assignment of a course, published or not (for the people who run it). */
    Page<Assignment> findByCourseId(Long courseId, Pageable pageable);

    /** Only the published assignments of a course (for students). */
    Page<Assignment> findByCourseIdAndPublishedTrue(Long courseId, Pageable pageable);

    /** Only the course of an assignment, for the access check before anything is locked or loaded. */
    @Query("select a.courseId from Assignment a where a.id = :id")
    Optional<Long> findCourseIdById(@Param("id") Long id);

    /**
     * The assignment, locked `FOR UPDATE` until the transaction ends. A submission being inserted
     * holds a key-share lock on its assignment row, so taking this lock waits for it: after it,
     * {@link #hasSubmissions} is exact and no submission can slip in before the transaction ends.
     *
     * <p>Native on purpose: Hibernate's {@code PESSIMISTIC_WRITE} on PostgreSQL is `FOR NO KEY
     * UPDATE`, which does <em>not</em> conflict with that key-share lock (a test pins this).
     */
    @Query(value = "select * from assignments where id = :id for update", nativeQuery = true)
    Optional<Assignment> findByIdForUpdate(@Param("id") Long id);

    /** True if anyone has submitted to this assignment (the table has no entity until Phase 5). */
    @Query(value = "select exists (select 1 from submissions where assignment_id = :id)", nativeQuery = true)
    boolean hasSubmissions(@Param("id") Long id);

    /** The assignment's test cases, which never outlive it (one bulk statement, so no entities are loaded). */
    @Modifying
    @Query(value = "delete from test_cases where assignment_id = :id", nativeQuery = true)
    int deleteTestCasesOf(@Param("id") Long id);

    /** The assignment's rubric criteria; delete the test cases first, they reference these. */
    @Modifying
    @Query(value = "delete from rubric_criteria where assignment_id = :id", nativeQuery = true)
    int deleteRubricCriteriaOf(@Param("id") Long id);
}
