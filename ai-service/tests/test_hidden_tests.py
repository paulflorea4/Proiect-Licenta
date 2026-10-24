"""Hidden test details never reach a prompt (6.6b).

The backend holds each hidden test in full (name, input, expected output, the
program's output), but the contract (6.3a) lets it send only whether each passed.
These tests start from such a graded submission, every hidden detail a marker
string, map it the only way the contract allows, and look for the markers in every
prompt the service can build or send.
"""

import itertools
import json
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from ai_service import api, config
from ai_service.feedback import SAMPLE_REQUEST, generate_feedback
from ai_service.llm import GeminiProvider
from ai_service.main import app
from ai_service.prompts import build_prompt
from ai_service.schemas import FeedbackRequest, HiddenTestResult
from fakes import gemini_playing, prompt_sent

# What the backend knows about each hidden test (5.5b's teacher view); every
# value is a marker that must never be seen outside the backend.
HIDDEN = [
    {
        "name": "HIDDEN-NAME-overflow",
        "input": "HIDDEN-INPUT-2147483647 1",
        "expectedOutput": "HIDDEN-EXPECTED-2147483648",
        "actualOutput": "HIDDEN-ACTUAL--2147483648",
        "stderr": "HIDDEN-STDERR-trace",
        "passed": False,
    },
    {
        "name": "HIDDEN-NAME-negatives",
        "input": "HIDDEN-INPUT--4 -6",
        "expectedOutput": "HIDDEN-EXPECTED--10",
        "actualOutput": "HIDDEN-ACTUAL-24",
        "stderr": "",
        "passed": False,
    },
    {
        "name": "HIDDEN-NAME-zeros",
        "input": "HIDDEN-INPUT-0 0",
        "expectedOutput": "HIDDEN-EXPECTED-0",
        "actualOutput": "HIDDEN-ACTUAL-0",
        "stderr": "",
        "passed": True,
    },
]
MARKER = "HIDDEN-"


def as_request(hidden: list[dict[str, Any]], level: int = 1) -> FeedbackRequest:
    """The request the backend can build: each hidden test reduced to ``passed``."""
    body = SAMPLE_REQUEST.model_dump(mode="json")
    body["hiddenTests"] = [{"passed": test["passed"]} for test in hidden]
    body["hintLevel"] = level
    return FeedbackRequest.model_validate(body)


# ---- the contract has nowhere to put them ----


def test_the_published_contract_gives_a_hidden_test_one_boolean_and_nothing_else() -> (
    None
):
    # The JSON schema is what the backend's DTO (7.1a) mirrors.
    schema = FeedbackRequest.model_json_schema()
    items = schema["properties"]["hiddenTests"]["items"]
    hidden = schema["$defs"][items["$ref"].rsplit("/", 1)[-1]]

    assert hidden["properties"] == {"passed": {"title": "Passed", "type": "boolean"}}
    assert hidden["required"] == ["passed"]
    assert hidden["additionalProperties"] is False


def test_no_model_but_the_public_test_has_a_text_field_a_test_could_fill() -> None:
    # Anything that can hold a test's content is a string; the only strings outside
    # the public test are the teacher's description, the language, the student's
    # code and the compiler's message.
    schema = FeedbackRequest.model_json_schema()
    top_strings = {
        name
        for name, field in schema["properties"].items()
        if "string" in json.dumps(field) and "$ref" not in json.dumps(field)
    }

    assert top_strings == {
        "assignmentDescription",
        "language",
        "code",
        "compilerOutput",
    }
    assert set(HiddenTestResult.model_fields) == {"passed"}


@pytest.mark.parametrize("field", sorted(set(HIDDEN[0]) - {"passed"}))
def test_each_hidden_detail_sent_as_it_is_kept_is_refused(field: str) -> None:
    body = SAMPLE_REQUEST.model_dump(mode="json")
    body["hiddenTests"] = [{"passed": False, field: HIDDEN[0][field]}]

    with pytest.raises(ValidationError) as refused:
        FeedbackRequest.model_validate(body)

    assert MARKER not in str(refused.value.errors(include_input=False))


def test_a_whole_hidden_test_as_the_backend_holds_it_is_refused() -> None:
    body = SAMPLE_REQUEST.model_dump(mode="json")
    body["hiddenTests"] = HIDDEN

    with pytest.raises(ValidationError):
        FeedbackRequest.model_validate(body)


# ---- no prompt holds them ----


@pytest.mark.parametrize("after_leak", [False, True], ids=["first", "after leak"])
@pytest.mark.parametrize("level", [1, 2, 3])
def test_no_built_prompt_holds_a_hidden_detail(level: int, after_leak: bool) -> None:
    prompt = build_prompt(as_request(HIDDEN, level), after_leak=after_leak)

    assert MARKER not in prompt
    assert "Hidden tests: 1 of 3 passed." in prompt


@pytest.mark.parametrize("level", [1, 2, 3])
def test_the_prompt_depends_on_the_hidden_results_only_through_the_count(
    level: int,
) -> None:
    # Which hidden test failed, or in which order they ran, never shows: every
    # arrangement of one pass and two failures gives the very same prompt.
    prompts = {
        build_prompt(as_request(list(order), level))
        for order in itertools.permutations(HIDDEN)
    }

    assert len(prompts) == 1


def test_different_counts_give_different_prompts_and_nothing_else_changes() -> None:
    # Positive control for the test above: the count does reach the prompt.
    all_failed = build_prompt(as_request([{**t, "passed": False} for t in HIDDEN]))
    one_passed = build_prompt(as_request(HIDDEN))

    assert all_failed != one_passed
    assert all_failed.replace("0 of 3 passed", "1 of 3 passed") == one_passed


def test_the_hidden_tests_are_mentioned_on_their_line_and_in_the_rules_only() -> None:
    prompt = build_prompt(as_request(HIDDEN))
    lines = [line for line in prompt.splitlines() if "idden test" in line]

    assert lines == [
        "- Hidden tests are reported only as passed or failed; do not guess what they "
        "contain.",
        "Hidden tests: 1 of 3 passed. Their inputs and expected outputs are not "
        "available.",
    ]


# ---- nor does anything sent to Gemini ----


@pytest.mark.parametrize("level", [1, 2, 3])
def test_every_prompt_sent_to_gemini_is_free_of_hidden_details(level: int) -> None:
    # A leak, then a hint: the first prompt and the after-leak retry both go out.
    provider, seen = gemini_playing("leak_edited_line", "hint")

    generate_feedback(provider, as_request(HIDDEN, level))

    assert len(seen) == 2
    for request in seen:
        assert MARKER not in request.content.decode()
        assert "Hidden tests: 1 of 3 passed." in prompt_sent(request)


def test_through_the_endpoint_hidden_details_cannot_get_in_or_out(
    monkeypatch: pytest.MonkeyPatch, test_token: str
) -> None:
    monkeypatch.setenv("GEMINI_API_KEY", "fake-key")
    monkeypatch.setenv("GEMINI_MODEL", "fixture-model")
    config.get_settings.cache_clear()

    # A fresh provider per request, as the real dependency builds (FastAPI builds it
    # even for a request whose body is then refused; that one sends nothing).
    def build(settings: Any) -> GeminiProvider:
        provider, sent = gemini_playing("hint")
        seen_lists.append(sent)
        return provider

    seen_lists: list[list[httpx.Request]] = []
    monkeypatch.setattr(api, "gemini_from_settings", build)
    headers = {"Authorization": f"Bearer {test_token}"}
    full = SAMPLE_REQUEST.model_dump(mode="json") | {"hiddenTests": HIDDEN}
    reduced = as_request(HIDDEN).model_dump(mode="json")

    with TestClient(app) as client:
        refused = client.post("/feedback", json=full, headers=headers)
        answered = client.post("/feedback", json=reduced, headers=headers)
    seen = [request for sent in seen_lists for request in sent]

    # The full hidden tests are refused without being echoed or sent on; the
    # reduced ones are answered, and Gemini saw only the count.
    assert refused.status_code == 422
    assert MARKER not in refused.text
    assert answered.status_code == 200
    assert MARKER not in answered.text
    assert len(seen) == 1
    assert MARKER not in seen[0].content.decode()
