package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Exact match: the database's unique constraint on `email` is case-sensitive too. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Every user with this role, locked against other writers until the transaction ends (`FOR UPDATE`).
     * Ordered by id so two callers lock in the same order. Must run inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.role = :role order by u.id")
    List<User> lockAllByRole(@Param("role") Role role);

    /**
     * The user's stored role, with the row locked {@code FOR SHARE} until the transaction ends
     * (9.0d). A role change (an {@code UPDATE}) waits for it and it waits for a role change in
     * flight, so a join and a promotion are decided one after the other. Empty if the user is gone.
     */
    @Query(value = "select role from users where id = :id for share", nativeQuery = true)
    Optional<String> lockRoleForShare(@Param("id") Long id);

    /** The students enrolled in a course. */
    @Query(
            "select u from User u where u.id in (select e.id.studentId from Enrollment e where e.id.courseId = :courseId)")
    Page<User> findEnrolledIn(@Param("courseId") Long courseId, Pageable pageable);
}
