package com.gradingplatform.backend;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.Submission;
import com.gradingplatform.backend.entity.SubmissionStatus;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.service.SubmissionService;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.MultipartConfigElement;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/** 5.1b: `POST /assignments/{id}/submissions` with the code uploaded as one file, over real HTTP. */
class RealHttpSubmitFileTests extends RealHttpTestBase {

    private static final int MAX = SubmissionService.SOURCE_MAX_BYTES;

    @Autowired
    DataSource dataSource;

    @Autowired
    MultipartConfigElement multipartConfig;

    private User owner;
    private User admin;
    private User student;
    private User outsider;
    private Course course;
    private Assignment python;
    private Assignment java;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        admin = saved("root@example.com", Role.ADMIN);
        student = saved("student@example.com", Role.STUDENT);
        outsider = saved("outsider@example.com", Role.STUDENT);
        course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        python = publishedAssignment("PYTHON");
        java = publishedAssignment("JAVA");
    }

    private Assignment publishedAssignment(String language) {
        Assignment saved = assignments.save(new Assignment(
                course.getId(),
                "Sorting",
                "d",
                language,
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                null,
                2000,
                256,
                null));
        jdbc.update("update assignments set published = true where id = ?", saved.getId());
        return saved;
    }

    /** One part of a multipart body; {@code fileName} null makes it a plain form field. */
    private record Part(String name, String fileName, byte[] content) {

        static Part file(String fileName, String content) {
            return new Part("file", fileName, content.getBytes(UTF_8));
        }

        static Part file(String fileName, byte[] content) {
            return new Part("file", fileName, content);
        }
    }

    private HttpResponse<String> upload(User as, long assignmentId, List<Part> parts) throws Exception {
        String boundary = "test-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Part part : parts) {
            StringBuilder head = new StringBuilder("--" + boundary + "\r\n")
                    .append("Content-Disposition: form-data; name=\"")
                    .append(part.name())
                    .append('"');
            if (part.fileName() != null) {
                head.append("; filename=\"")
                        .append(part.fileName())
                        .append("\"\r\nContent-Type: application/octet-stream");
            }
            body.write(head.append("\r\n\r\n").toString().getBytes(UTF_8));
            body.write(part.content());
            body.write("\r\n".getBytes(UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(UTF_8));
        return sendRaw(as, assignmentId, "multipart/form-data; boundary=" + boundary, body.toByteArray());
    }

    private HttpResponse<String> sendRaw(User as, long assignmentId, String contentType, byte[] body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/assignments/" + assignmentId + "/submissions"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (as != null) {
            request.header("Authorization", "Bearer " + tokenFor(as));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(User as, Assignment to, String fileName, String content) throws Exception {
        return upload(as, to.getId(), List.of(Part.file(fileName, content)));
    }

    private HttpResponse<String> upload(User as, Assignment to, String fileName, byte[] content) throws Exception {
        return upload(as, to.getId(), List.of(Part.file(fileName, content)));
    }

    private static String code(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.code");
    }

    private static void assertRefused(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(code(response)).isEqualTo(code);
    }

    private Submission stored(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        long id = ((Number) JsonPath.read(response.body(), "$.id")).longValue();
        return submissions.findById(id).orElseThrow();
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    // --- accepted ----------------------------------------------------------------------------

    @Test
    void aFileIsReadIntoTheSourceCodeAndAcceptedLikeText() throws Exception {
        String code = "n = int(input())\r\n\tprint(n * 2)  \n# éè 中 \"quoted\" \\ end\n";

        HttpResponse<String> response = upload(student, python, "main.py", code);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        Map<String, Object> body = JsonPath.read(response.body(), "$");
        assertThat(body).containsOnlyKeys("id", "attemptNo", "status", "submittedAt");
        assertThat(body).containsEntry("attemptNo", 1).containsEntry("status", "QUEUED");
        Submission submission = stored(response);
        assertThat(submission.getSourceCode()).as("byte for byte").isEqualTo(code);
        assertThat(submission.getLanguage()).isEqualTo("PYTHON");
        assertThat(submission.getStudentId()).isEqualTo(student.getId());
        // The worker may already have taken it (5.2a), or graded it: no tests to run (5.3a).
        assertThat(submission.getStatus())
                .isIn(SubmissionStatus.QUEUED, SubmissionStatus.RUNNING, SubmissionStatus.GRADED);
    }

    @Test
    void textAndFileSubmissionsAreAttemptsOfTheSameSequence() throws Exception {
        HttpResponse<String> text = post(
                "/assignments/" + python.getId() + "/submissions", "{\"sourceCode\":\"print(1)\"}", tokenFor(student));
        assertThat(stored(text).getAttemptNo()).isEqualTo(1);

        assertThat(stored(upload(student, python, "main.py", "print(2)")).getAttemptNo())
                .isEqualTo(2);
        assertThat(stored(upload(student, java, "Main.java", "class Main {}")).getAttemptNo())
                .as("counted per assignment")
                .isEqualTo(1);
    }

    @Test
    void theExtensionIsComparedIgnoringCaseAndOnlyTheExtensionCounts() throws Exception {
        // Only the extension is checked: a Java file not named Main.java is accepted here and is
        // still built as Main.java (the README's convention), so a class not called Main fails there.
        for (String name : List.of("Main.java", "MAIN.JAVA", "Solution.java", "dir/Main.java", "my.main.Java")) {
            assertThat(stored(upload(student, java, name, "class Main {}")).getLanguage())
                    .as(name)
                    .isEqualTo("JAVA");
        }
        assertThat(stored(upload(student, python, "C:\\fakepath\\main.PY", "print(1)"))
                        .getLanguage())
                .isEqualTo("PYTHON");
    }

    @Test
    void aFileOfExactlyTheMaximumSizeIsAccepted() throws Exception {
        String code = "print(1)\n" + "#".repeat(MAX - 9);
        assertThat(code.getBytes(UTF_8)).hasSize(MAX);

        assertThat(stored(upload(student, python, "main.py", code)).getSourceCode())
                .isEqualTo(code);
    }

    @Test
    void aLeadingByteOrderMarkIsDropped() throws Exception {
        byte[] withBom = concat(bytes(0xEF, 0xBB, 0xBF), "class Main {}\n".getBytes(UTF_8));

        assertThat(stored(upload(student, java, "Main.java", withBom)).getSourceCode())
                .isEqualTo("class Main {}\n");
    }

    @Test
    void otherPartsAreIgnored() throws Exception {
        HttpResponse<String> response = upload(
                student,
                python.getId(),
                List.of(
                        new Part("language", null, "JAVA".getBytes(UTF_8)),
                        Part.file("main.py", "print(1)"),
                        new Part("sourceCode", null, "print(2)".getBytes(UTF_8))));

        Submission submission = stored(response);
        assertThat(submission.getLanguage()).isEqualTo("PYTHON");
        assertThat(submission.getSourceCode()).isEqualTo("print(1)");
    }

    // --- refused: the file -------------------------------------------------------------------

    @Test
    void aFileWhoseExtensionIsNotTheAssignmentsLanguageIsRefused() throws Exception {
        for (String name : List.of("main.java", "main", "main.", "main.py.txt", "main.pyc", "py", "")) {
            assertRefused(upload(student, python, name, "print(1)"), 400, "SOURCE_FILE_EXTENSION_MISMATCH");
        }
        assertRefused(upload(student, java, "Main.py", "class Main {}"), 400, "SOURCE_FILE_EXTENSION_MISMATCH");
        assertRefused(upload(student, java, "Main.class", "class Main {}"), 400, "SOURCE_FILE_EXTENSION_MISMATCH");
        assertThat(submissions.count()).isZero();
    }

    @Test
    void aFileOneByteOverTheMaximumIsRefusedAndSizeIsCountedInBytes() throws Exception {
        assertRefused(upload(student, python, "main.py", "print(1)\n" + "#".repeat(MAX - 8)), 413, "SOURCE_TOO_LARGE");

        // Fewer characters than the maximum, but two bytes each in UTF-8.
        String accents = "#" + "é".repeat(MAX / 2);
        assertThat(accents.length()).isLessThan(MAX);
        assertRefused(upload(student, python, "main.py", accents), 413, "SOURCE_TOO_LARGE");
        assertThat(submissions.count()).isZero();
    }

    @Test
    void aFileOverTheServersOwnUploadLimitGetsTheSameAnswer() throws Exception {
        byte[] huge = "#".repeat(1536 * 1024).getBytes(UTF_8);

        assertRefused(upload(student, python, "main.py", huge), 413, "SOURCE_TOO_LARGE");
        assertThat(submissions.count()).isZero();
    }

    @Test
    void anOversizedUploadToTheSubmissionByAWrongRoleIsStillTheSourceAnswer() throws Exception {
        // Parsed before the role rule (5.1b): the size is judged first, by the endpoint addressed.
        byte[] huge = "#".repeat(1536 * 1024).getBytes(UTF_8);

        assertRefused(upload(owner, python, "main.py", huge), 413, "SOURCE_TOO_LARGE");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "POST, /courses, teacher",
        "POST, /auth/signup, nobody",
        "PUT, /assignments/1, teacher",
        "POST, /no/such/path, student",
        "GET, /assignments/1/submissions, teacher"
    })
    void aMultipartBodyOverTheLimitSentElsewhereIsTheGenericTooLarge(String method, String path, String caller)
            throws Exception {
        // 8.0c: these endpoints take no file, so the answer is not about source code.
        User as = switch (caller) {
            case "teacher" -> owner;
            case "student" -> student;
            default -> null;
        };
        String boundary = "test-" + UUID.randomUUID();
        byte[] body = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n\r\n"
                        + "#".repeat(1536 * 1024)
                        + "\r\n--" + boundary + "--\r\n")
                .getBytes(UTF_8);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        if (as != null) {
            request.header("Authorization", "Bearer " + tokenFor(as));
        }

        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());

        assertRefused(response, 413, "REQUEST_TOO_LARGE");
        assertThat(response.body()).doesNotContain("source");
    }

    @Test
    void theServersUploadLimitsKeepEveryUploadInMemoryAndAboveTheSubmissionCap() {
        assertThat(multipartConfig.getMaxFileSize()).isGreaterThan(MAX);
        assertThat(multipartConfig.getMaxRequestSize()).isGreaterThan(MAX);
        assertThat((long) multipartConfig.getFileSizeThreshold())
                .as("a part only goes to disk above the threshold, and no request can be that large")
                .isGreaterThan(multipartConfig.getMaxRequestSize());
    }

    @Test
    void aFileThatIsNotUtf8TextIsRefused() throws Exception {
        List<byte[]> notText = List.of(
                bytes('p', 'r', 0xE9, '\n'), // Latin-1 é
                bytes(0xC3, 0x28), // a broken two-byte sequence
                bytes(0xFF, 0xFE, 'p', 0, 'r', 0), // UTF-16
                bytes('p', 'r', 'i', 'n', 't', 0, '\n')); // a NUL, which Postgres TEXT cannot hold
        for (byte[] content : notText) {
            assertRefused(upload(student, python, "main.py", content), 400, "SOURCE_NOT_TEXT");
        }
        assertThat(submissions.count()).isZero();
    }

    @Test
    void anEmptyOrBlankFileIsRefusedAndCostsNoAttempt() throws Exception {
        List<byte[]> blank = List.of(
                new byte[0], " \r\n\t ".getBytes(UTF_8), bytes(0xEF, 0xBB, 0xBF), bytes(0xEF, 0xBB, 0xBF, ' ', '\n'));
        for (byte[] content : blank) {
            assertRefused(upload(student, python, "main.py", content), 400, "SOURCE_FILE_EMPTY");
        }
        assertThat(submissions.count()).isZero();
    }

    @Test
    void exactlyOneFileInThePartNamedFileIsExpected() throws Exception {
        List<List<Part>> wrong = List.of(
                List.of(),
                List.of(new Part("upload", "main.py", "print(1)".getBytes(UTF_8))),
                List.of(new Part("file", null, "print(1)".getBytes(UTF_8))),
                List.of(Part.file("main.py", "print(1)"), Part.file("other.py", "print(2)")));
        for (List<Part> parts : wrong) {
            assertRefused(upload(student, python.getId(), parts), 400, "ONE_SOURCE_FILE_EXPECTED");
        }
        assertThat(submissions.count()).isZero();
    }

    @Test
    void aBodyThatIsNeitherJsonNorMultipartIs415() throws Exception {
        assertRefused(
                sendRaw(student, python.getId(), "text/plain", "print(1)".getBytes(UTF_8)),
                415,
                "UNSUPPORTED_MEDIA_TYPE");
        assertRefused(
                sendRaw(student, python.getId(), null, "print(1)".getBytes(UTF_8)), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertThat(submissions.count()).isZero();
    }

    @Test
    void anAssignmentWhoseLanguageIsNoLongerConfiguredRefusesUploads() throws Exception {
        jdbc.update("update assignments set language = 'NOT_CONFIGURED' where id = ?", python.getId());

        assertRefused(upload(student, python, "main.py", "print(1)"), 400, "UNSUPPORTED_LANGUAGE");
        assertThat(submissions.count()).isZero();
    }

    // --- refused: who and where --------------------------------------------------------------

    @Test
    void anAssignmentTheStudentMayNotSeeIsTheSame404WhateverTheFileName() throws Exception {
        HttpResponse<String> missing = upload(student, 987654321L, List.of(Part.file("main.py", "print(1)")));
        assertRefused(missing, 404, "ASSIGNMENT_NOT_FOUND");
        Assignment draft = assignments.save(
                new Assignment(course.getId(), "Draft", "d", "PYTHON", python.getDeadline(), null, 2000, 256, null));

        // A wrong extension must not tell which language an unseen assignment has.
        List<HttpResponse<String>> refused = List.of(
                upload(outsider, python, "main.py", "print(1)"),
                upload(outsider, python, "Main.java", "print(1)"),
                upload(student, draft, "main.py", "print(1)"),
                upload(student, draft, "Main.java", "print(1)"),
                upload(student, 987654321L, List.of(Part.file("Main.java", "print(1)"))));
        for (HttpResponse<String> response : refused) {
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).isEqualTo(missing.body());
        }
        assertThat(submissions.count()).isZero();
    }

    @Test
    void teachersAndAdminsAreRefusedByRoleBeforeTheFileIsLookedAtAndAnonymousIs401() throws Exception {
        for (User user : List.of(owner, admin)) {
            assertRefused(upload(user, python, "main.py", "print(1)"), 403, "ACCESS_DENIED");
            assertRefused(upload(user, python, "main.py", ""), 403, "ACCESS_DENIED");
        }
        assertRefused(upload(null, python, "main.py", "print(1)"), 401, "UNAUTHENTICATED");
        assertThat(submissions.count()).isZero();
    }

    // --- the extension is checked against the locked language --------------------------------

    @Test
    void anUploadWaitsForALanguageChangeInFlightAndIsCheckedAgainstTheNewLanguage() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("select id from assignments where id = " + python.getId() + " for update");
                statement.executeUpdate("update assignments set language = 'JAVA' where id = " + python.getId());

                CompletableFuture<HttpResponse<String>> uploading = CompletableFuture.supplyAsync(() -> {
                    try {
                        return upload(student, python, "Main.java", "class Main {}");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                Thread.sleep(500);
                assertThat(uploading).as("the upload must wait for the change").isNotDone();

                connection.commit();
                HttpResponse<String> response = uploading.get(10, TimeUnit.SECONDS);

                assertThat(stored(response).getLanguage()).isEqualTo("JAVA");
            }
        }
    }
}
