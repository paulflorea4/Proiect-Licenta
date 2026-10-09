package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.EnrollmentId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnrollmentRepository extends JpaRepository<Enrollment, EnrollmentId> {}
