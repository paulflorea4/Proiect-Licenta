package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.Assignment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {}
