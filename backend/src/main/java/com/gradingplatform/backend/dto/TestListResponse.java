package com.gradingplatform.backend.dto;

/**
 * The answer to `GET /assignments/{id}/tests`: the teacher's full list ({@link TestCasesResponse})
 * or the student's masked one ({@link StudentTestCasesResponse}). Which one a caller gets follows
 * from the token's role in the service and controller, never from a parameter.
 */
public sealed interface TestListResponse permits TestCasesResponse, StudentTestCasesResponse {}
