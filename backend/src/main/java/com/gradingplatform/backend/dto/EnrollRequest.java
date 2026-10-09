package com.gradingplatform.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of `POST /courses/enroll`. The longest code the column holds is 20 characters, so anything
 * longer cannot match and is refused as malformed. Case and surrounding spaces are normalised in
 * the service, not here.
 */
public record EnrollRequest(
        @NotBlank @Size(max = EnrollRequest.CODE_MAX) String code) {

    public static final int CODE_MAX = 20;

    /** The code is a join secret: keep it out of any log line that prints the request. */
    @Override
    public String toString() {
        return "EnrollRequest[code=<redacted>]";
    }
}
