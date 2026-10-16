package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * An uploaded file whose extension is not that of the assignment's language (the extension of its {@code source-file-name} in the languages config): 400. Status, code and message come from {@link ErrorCode#SOURCE_FILE_EXTENSION_MISMATCH}.
 */
public class SourceFileExtensionMismatchException extends ApiException {

    public SourceFileExtensionMismatchException() {
        super(ErrorCode.SOURCE_FILE_EXTENSION_MISMATCH);
    }
}
