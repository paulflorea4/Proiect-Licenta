package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.SubmissionRequest;
import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.Submission;
import com.gradingplatform.backend.repository.EnrollmentRepository;
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
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Students' submissions: accepting them (5.1a, 5.1b) and the rules a submission must meet (5.1c).
 * Grading them is the worker's (5.2): each accepted submission is handed to {@link SubmissionDispatcher}.
 */
@Service
public class SubmissionService {

    /**
     * The largest source code, in bytes of UTF-8, as text or as an uploaded file (5.1b, 5.1c).
     * Placeholder pending the human's decision. The server's own multipart limit
     * ({@code spring.servlet.multipart} in {@code application.yml}) must stay above it, so that this
     * is the limit a student meets.
     */
    public static final int SOURCE_MAX_BYTES = 128 * 1024;

    private static final char BYTE_ORDER_MARK = '﻿';

    private final AssignmentService assignmentService;
    private final SubmissionRepository submissions;
    private final EnrollmentRepository enrollments;
    private final SandboxProperties sandboxProperties;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public SubmissionService(
            AssignmentService assignmentService,
            SubmissionRepository submissions,
            EnrollmentRepository enrollments,
            SandboxProperties sandboxProperties,
            Clock clock,
            ApplicationEventPublisher events) {
        this.assignmentService = assignmentService;
        this.submissions = submissions;
        this.enrollments = enrollments;
        this.sandboxProperties = sandboxProperties;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Stores the code as the student's next attempt, {@code QUEUED}, and returns at once: nothing here
     * waits for grading. The code must be at most {@link #SOURCE_MAX_BYTES} and storable text (no NUL,
     * no unpaired surrogate); then the rules of {@link #accept} apply.
     *
     * @throws AssignmentNotFoundException if there is no such assignment or the user may not see it
     */
    @Transactional
    public Submission submit(long userId, Role role, long assignmentId, SubmissionRequest request) {
        Instant arrived = now();
        String sourceCode = request.sourceCode();
        if (sourceCode.getBytes(StandardCharsets.UTF_8).length > SOURCE_MAX_BYTES) {
            throw new SourceTooLargeException();
        }
        requireStorable(sourceCode);
        return accept(userId, role, assignmentId, sourceCode, null, arrived);
    }

    /**
     * The same as {@link #submit} with the code read from one uploaded file (5.1b). The file's text
     * becomes the source code (nothing is written to disk); its name is only used for the extension,
     * which must be that of the assignment's language. The file is checked first (one file, size,
     * UTF-8 text, not blank), then the rules of {@link #accept}.
     *
     * @param files the parts named {@code file}; null when there are none
     * @throws AssignmentNotFoundException if there is no such assignment or the user may not see it
     */
    @Transactional
    public Submission submitFile(long userId, Role role, long assignmentId, List<MultipartFile> files) {
        Instant arrived = now();
        if (files == null || files.size() != 1) {
            throw new OneSourceFileExpectedException();
        }
        MultipartFile file = files.get(0);
        String sourceCode = textOf(file);
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        return accept(userId, role, assignmentId, sourceCode, fileName, arrived);
    }

    /**
     * The rules every submission meets, in this order, all under locks so none can change halfway:
     * the assignment is one the student may see (else the 404 of a missing id, before anything about
     * it is told), read under {@link AssignmentService#lockVisibleForSubmission}; the student's
     * enrollment is locked ({@link EnrollmentRepository#lockByCourseIdAndStudentId}), so their
     * submissions are made one at a time; the request arrived no later than the deadline; an attempt
     * is left (one that ended in {@code ERROR} does not count, 5.1d); the language is still configured; an uploaded file has its extension.
     *
     * @param fileName the uploaded file's name, or null for code sent as text
     * @param arrived when the request arrived: the instant judged against the deadline and stored
     */
    private Submission accept(
            long userId, Role role, long assignmentId, String sourceCode, String fileName, Instant arrived) {
        Assignment assignment = assignmentService.lockVisibleForSubmission(userId, role, assignmentId);
        if (enrollments
                .lockByCourseIdAndStudentId(assignment.getCourseId(), userId)
                .isEmpty()) {
            throw new AssignmentNotFoundException();
        }
        if (arrived.isAfter(assignment.getDeadline())) {
            throw new SubmissionDeadlinePassedException();
        }
        int attemptNo = submissions.nextAttemptNo(assignment.getId(), userId);
        Integer maxAttempts = assignment.getMaxAttempts();
        if (maxAttempts != null && submissions.countAttemptsUsed(assignment.getId(), userId) >= maxAttempts) {
            throw new AttemptLimitReachedException();
        }
        LanguageSpec language =
                sandboxProperties.find(assignment.getLanguage()).orElseThrow(UnsupportedLanguageException::new);
        if (fileName != null && !SourceFile.extensionOf(fileName).equals(language.sourceFileExtension())) {
            throw new SourceFileExtensionMismatchException();
        }
        Submission saved = submissions.saveAndFlush(
                new Submission(assignment.getId(), userId, assignment.getLanguage(), sourceCode, attemptNo, arrived));
        // Handed to the worker pool once this transaction commits (5.2a).
        events.publishEvent(new SubmissionQueuedEvent(saved.getId()));
        return saved;
    }

    /**
     * The file's content as text: strict UTF-8 (a byte that is not is refused, never replaced), a
     * leading byte order mark dropped (editors on Windows add one and {@code javac} rejects it),
     * otherwise exactly as uploaded.
     */
    private static String textOf(MultipartFile file) {
        if (file.getSize() > SOURCE_MAX_BYTES) {
            throw new SourceTooLargeException();
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (bytes.length > SOURCE_MAX_BYTES) {
            throw new SourceTooLargeException();
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
            throw new SourceNotTextException();
        }
        requireStorable(text);
        if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            text = text.substring(1);
        }
        if (text.isBlank()) {
            throw new SourceFileEmptyException();
        }
        return text;
    }

    /**
     * Refuses text a Postgres {@code TEXT} column cannot store as sent: U+0000 is rejected by the
     * database (a 500 otherwise), and an unpaired surrogate (possible in JSON through a
     * {@code \}{@code uD800} escape) would be stored as a replacement character, silently changing the
     * code.
     */
    private static void requireStorable(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\0') {
                throw new SourceNotTextException();
            }
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new SourceNotTextException();
            }
        }
    }

    /** The current time at the precision the database keeps (microseconds). */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
