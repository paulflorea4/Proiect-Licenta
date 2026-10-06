package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Exact match: the database's unique constraint on `email` is case-sensitive too. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
