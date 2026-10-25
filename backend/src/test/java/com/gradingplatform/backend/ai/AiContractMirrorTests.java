package com.gradingplatform.backend.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 7.1a: the request and response records mirror the AI service's pydantic models field for field
 * and in order (`ai-service/src/ai_service/schemas.py`, 6.3a). It reads that file, as the AI service's
 * own test reads the backend's `TestOutcome.java`, so the two sides cannot drift: a field added,
 * renamed or removed on either side fails here. The CI checks out the whole repository.
 */
class AiContractMirrorTests {

    private static final Path SCHEMAS = Path.of("..", "ai-service", "src", "ai_service", "schemas.py");

    // A pydantic model's fields: four spaces, a snake_case name, a colon (class constants such as
    // `__test__` or `model_config` are not fields of the contract).
    private static final Pattern CLASS = Pattern.compile("^class (\\w+)\\(");
    private static final Pattern FIELD = Pattern.compile("^ {4}([a-z][a-z0-9_]*)\\s*:");

    static List<Object[]> models() {
        return List.of(
                new Object[] {"FeedbackRequest", FeedbackRequest.class},
                new Object[] {"PublicTestResult", FeedbackRequest.PublicTestResult.class},
                new Object[] {"HiddenTestResult", FeedbackRequest.HiddenTestResult.class},
                new Object[] {"FeedbackResponse", FeedbackResponse.class});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("models")
    void theRecordHasExactlyTheFieldsOfThePydanticModelInOrder(String model, Class<?> record) throws IOException {
        Map<String, List<String>> python = pythonModels();

        assertThat(python).containsKey(model);
        List<String> expected =
                python.get(model).stream().map(AiContractMirrorTests::camelCase).toList();
        List<String> actual = Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(actual).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("models")
    void theParserFindsFieldsInEveryModel(String model, Class<?> record) throws IOException {
        // Positive control: a parser that silently found nothing would make the test above pass for an
        // empty record.
        assertThat(pythonModels().get(model)).isNotEmpty();
    }

    private static Map<String, List<String>> pythonModels() throws IOException {
        Map<String, List<String>> models = new LinkedHashMap<>();
        List<String> current = null;
        for (String line : Files.readAllLines(SCHEMAS, StandardCharsets.UTF_8)) {
            Matcher declared = CLASS.matcher(line);
            if (declared.find()) {
                current = new ArrayList<>();
                models.put(declared.group(1), current);
                continue;
            }
            Matcher field = FIELD.matcher(line);
            if (current != null && field.find() && !field.group(1).equals("model_config")) {
                current.add(field.group(1));
            }
        }
        return models;
    }

    private static String camelCase(String snake) {
        var out = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }
}
