package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.Course;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseRepository extends JpaRepository<Course, Long> {}
