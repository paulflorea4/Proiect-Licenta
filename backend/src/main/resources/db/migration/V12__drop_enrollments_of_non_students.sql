-- 9.0d: enrollments only ever hold students. From now on a role change away from STUDENT removes
-- the user's enrollments (UserAdminService.changeRole) and only a stored STUDENT may join a course
-- (EnrollmentService.enroll). This removes the enrollments that users promoted before this
-- migration still have. Their submissions and grades are untouched, as when a student leaves.
DELETE FROM enrollments e
USING users u
WHERE u.id = e.student_id
  AND u.role <> 'STUDENT';
