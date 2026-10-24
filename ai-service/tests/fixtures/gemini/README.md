# Gemini response fixtures (6.6a)

Hand-written bodies of `POST .../models/{model}:generateContent`, in the shape of
Google's Gemini API documentation: `candidates[].content.parts[].text`,
`finishReason`, `usageMetadata`, `promptFeedback.blockReason`, and the
`{"error": {"code", "message", "status"}}` body of an error status. They hold no
student's data, and `fixture-model` is not a real model name.

`tests/fakes.py` plays them through the real `GeminiProvider`: an `error` body is
answered with its own `code` as the HTTP status, anything else with 200.

| Fixture | What Gemini did |
| --- | --- |
| `hint` | answered `{"hint": ...}` |
| `hint_fenced` | the same, wrapped in a ```` ```json ```` fence |
| `hint_split_in_parts` | the same JSON split across two text parts |
| `hint_level3_snippet` | a level-3 hint with an allowed snippet on another example |
| `leak_edited_line` | the sample's fix itself (`print(a + b)`) |
| `leak_complete_program` | a whole program that reads and prints |
| `cut_off_max_tokens` | stopped at the token limit in the middle of the JSON |
| `safety_no_content` | a candidate stopped for safety, without content |
| `prompt_blocked` | refused the prompt (`promptFeedback.blockReason`), no candidate |
| `error_400_invalid_argument` | HTTP 400 `INVALID_ARGUMENT` |
| `error_429_resource_exhausted` | HTTP 429 `RESOURCE_EXHAUSTED`, with a `RetryInfo` detail |
| `error_503_unavailable` | HTTP 503 `UNAVAILABLE` (model overloaded) |

The leak fixtures are written for `SAMPLE_REQUEST` (`a, b = map(int, input().split())`
/ `print(a * b)`).
