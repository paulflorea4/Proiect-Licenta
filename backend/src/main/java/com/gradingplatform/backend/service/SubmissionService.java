package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.SubmissionRequest;
import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.Submission;
import com.gradingplatform.backend.repository.SubmissionRepository;
import com.gradingplatform.backend.sandbox.LanguageSpec;
import com.gradingplatform.backend.sandbox.SandboxProperties;
import com.gradingplatform.backend.sandbox.SourceFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Students' submissions: accepting them (5.1a, 5.1b). Grading them is the worker's (5.2). */
@Service
public class SubmissionService {

    /**
     * The largest uploaded source file, in bytes (5.1b). Placeholder pending the human's decision.
     * The server's own multipart limit ({@code spring.servlet.multipart} in {@code application.yml})
     * must stay above it, so that this is the limit a student meets.
     */
    public static final int SOURCE_FILE_MAX_BYTES = 128 * 1024;

    private static final char BYTE_ORDER_MARK = '﻿';

    private final AssignmentService assignmentService;
    private final SubmissionRepository submissions;
    private final SandboxProperties sandboxProperties;

    public SubmissionService(
            AssignmentService assignmentService,
            SubmissionRepository submissions,
            SandboxProperties sandboxProperties) {
        this.assignmentService = assignmentService;
        this.submissions = submissions;
        this.sandboxProperties = sandboxProperties;
    }

    /**
     * Stores the code as the student's next attempt, {@code QUEUED}, and returns at once: nothing here
     * waits for grading. The assignment must be one the student may see (published, in a course they
     * are enrolled in), else it is the 404 of an id that does not exist. The language is the
     * assignment's, read under {@link AssignmentService#lockVisibleForSubmission}, so a teacher's
     * change of language either happens before (and is the one stored) or waits and is then refused.
     *
     * @throws AssignmentNotFoundException if there is no such assignment or the user may not see it
     */
    @Transactional
    public Submission submit(long userId, Role role, long assignmentId, SubmissionRequest request) {
        Assignment assignment = assignmentService.lockVisibleForSubmission(userId, role, assignmentId);
        return store(assignment, userId, request.sourceCode());
    }

    /**
     * The same as {@link #submit} with the code read from one uploaded file (5.1b). The file's text
     * becomes the source code (nothing is written to disk); its name is only used for the extension,
     * which must be that of the assignment's language, checked against the language read under the
     * lock. The file is checked first (one file, size, UTF-8 text, not blank), then the assignment,
     * so an assignment the student may not see is the same 404 whatever the file's name.
     *
     * @param files the parts named {@code file}; null when there are none
     * @throws AssignmentNotFoundException if there is no such assignment or the user may not see it
     */
    @Transactional
    public Submission submitFile(long userId, Role role, long assignmentId, List<MultipartFile> files) {
        if (files == null || files.size() != 1) {
            throw new OneSourceFileExpectedException();
        }
        MultipartFile file = files.get(0);
        String sourceCode = textOf(file);
        Assignment assignment = assignmentService.lockVisibleForSubmission(userId, role, assignmentId);
        LanguageSpec language =
                sandboxProperties.find(assignment.getLanguage()).orElseThrow(UnsupportedLanguageException::new);
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!SourceFile.extensionOf(fileName).equals(language.sourceFileExtension())) {
            throw new SourceFileExtensionMismatchException();
        }
        return store(assignment, userId, sourceCode);
    }

    private Submission store(Assignment assignment, long userId, String sourceCode) {
        int attemptNo = submissions.nextAttemptNo(assignment.getId(), userId);
        return submissions.saveAndFlush(
                new Submission(assignment.getId(), userId, assignment.getLanguage(), sourceCode, attemptNo));
    }

    /**
     * The file's content as text: strict UTF-8 (a byte that is not is refused, never replaced), a
     * leading byte order mark dropped (editors on Windows add one and {@code javac} rejects it),
     * otherwise exactly as uploaded.
     */
    private static String textOf(MultipartFile file) {
        if (file.getSize() > SOURCE_FILE_MAX_BYTES) {
            throw new SourceFileTooLargeException();
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (bytes.length > SOURCE_FILE_MAX_BYTES) {
            throw new SourceFileTooLargeException();
        }
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new SourceFileNotTextException();
        }
        // Postgres TEXT cannot store U+0000, and no source file needs it.
        if (text.indexOf('\0') >= 0) {
            throw new SourceFileNotTextException();
        }
        if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            text = text.substring(1);
        }
        if (text.isBlank()) {
            throw new SourceFileEmptyException();
        }
        return text;
    }
}
