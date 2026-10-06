/** Business logic and the transaction boundaries. Controllers call services; services call repositories (and, from later phases, the `sandbox` and `ai` packages). Calls to the AI service are never made inside an open database transaction. */
package com.gradingplatform.backend.service;
