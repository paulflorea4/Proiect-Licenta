import copy
import re
from pathlib import Path
from typing import Any

import pytest
from pydantic import BaseModel, ValidationError

from ai_service.schemas import (
    FeedbackRequest,
    FeedbackResponse,
    HiddenTestResult,
    PublicTestResult,
    TestOutcome,
)

BACKEND_OUTCOME = (
    Path(__file__).resolve().parents[2]
    / "backend/src/main/java/com/gradingplatform/backend/sandbox/TestOutcome.java"
)


def request(**changes: Any) -> dict[str, Any]:
    """A valid request as the backend sends it (camelCase), with some keys changed."""
    body: dict[str, Any] = {
        "assignmentDescription": "Print the sum of two integers.",
        "language": "PYTHON",
        "code": "a, b = map(int, input().split())\nprint(a * b)\n",
        "compilerOutput": None,
        "publicTests": [
            {
                "name": "small numbers",
                "input": "1 2\n",
                "expectedOutput": "3",
                "status": "FAILED",
                "actualOutput": "2\n",
                "stderr": "",
            }
        ],
        "hiddenTests": [{"passed": False}, {"passed": True}],
        "hintLevel": 1,
    }
    body.update(changes)
    return body


def refused(body: dict[str, Any]) -> ValidationError:
    with pytest.raises(ValidationError) as raised:
        FeedbackRequest.model_validate(body)
    return raised.value


# ---- a valid request ----


def test_a_request_from_the_backend_is_read_with_camel_case_names() -> None:
    parsed = FeedbackRequest.model_validate(request())

    assert parsed.assignment_description == "Print the sum of two integers."
    assert parsed.language == "PYTHON"
    assert parsed.code.startswith("a, b = map")
    assert parsed.compiler_output is None
    assert parsed.public_tests == [
        PublicTestResult.model_validate(request()["publicTests"][0])
    ]
    public = parsed.public_tests[0]
    assert (public.name, public.input, public.expected_output) == (
        "small numbers",
        "1 2\n",
        "3",
    )
    assert (public.status, public.actual_output, public.stderr) == (
        TestOutcome.FAILED,
        "2\n",
        "",
    )
    assert [h.passed for h in parsed.hidden_tests] == [False, True]
    assert parsed.hint_level == 1


def test_it_reads_from_json_text_too() -> None:
    import json

    parsed = FeedbackRequest.model_validate_json(json.dumps(request()))

    assert parsed.public_tests[0].status is TestOutcome.FAILED


def test_text_is_kept_exactly_as_sent() -> None:
    code = "  # leading spaces\n\tprint('é 🙂')  \n\n"
    parsed = FeedbackRequest.model_validate(request(code=code))

    assert parsed.code == code


def test_a_compile_error_has_no_actual_output_and_the_compiler_message() -> None:
    body = request(
        compilerOutput="Main.java:3: error: ';' expected",
        publicTests=[
            {
                "name": "t",
                "input": "",
                "expectedOutput": "1",
                "status": "COMPILE_ERROR",
                "actualOutput": None,
                "stderr": "Main.java:3: error: ';' expected",
            }
        ],
    )

    parsed = FeedbackRequest.model_validate(body)

    assert parsed.compiler_output == "Main.java:3: error: ';' expected"
    assert parsed.public_tests[0].actual_output is None


def test_actual_output_and_stderr_may_be_left_out() -> None:
    body = request(
        publicTests=[
            {"name": "t", "input": "", "expectedOutput": "1", "status": "TIMEOUT"}
        ]
    )

    public = FeedbackRequest.model_validate(body).public_tests[0]

    assert public.actual_output is None
    assert public.stderr is None


def test_no_tests_of_either_kind_is_allowed() -> None:
    parsed = FeedbackRequest.model_validate(request(publicTests=[], hiddenTests=[]))

    assert parsed.public_tests == []
    assert parsed.hidden_tests == []


@pytest.mark.parametrize("level", [1, 2, 3])
def test_each_hint_level_is_accepted(level: int) -> None:
    assert FeedbackRequest.model_validate(request(hintLevel=level)).hint_level == level


def test_every_outcome_of_the_backend_is_accepted() -> None:
    for outcome in TestOutcome:
        body = request()
        body["publicTests"][0]["status"] = outcome.value
        assert FeedbackRequest.model_validate(body).public_tests[0].status is outcome


def test_the_outcomes_are_exactly_the_backends() -> None:
    # The mirror of backend/.../sandbox/TestOutcome.java; it must not drift.
    java = BACKEND_OUTCOME.read_text(encoding="utf-8")
    java = re.sub(r"/\*.*?\*/|//[^\n]*", "", java, flags=re.S)  # drop comments
    declared = re.search(r"enum TestOutcome\s*\{([^;}]*)", java)
    assert declared is not None
    names = [n.strip() for n in declared.group(1).split(",") if n.strip()]

    assert names == [o.value for o in TestOutcome]


# ---- hidden tests are hidden by the shape ----


def test_a_hidden_test_is_only_whether_it_passed() -> None:
    assert set(HiddenTestResult.model_fields) == {"passed"}


@pytest.mark.parametrize(
    "leak",
    [
        "input",
        "expectedOutput",
        "actualOutput",
        "stderr",
        "name",
        "status",
        "expected_output",
    ],
)
def test_a_hidden_test_carrying_anything_more_is_refused(leak: str) -> None:
    body = request(hiddenTests=[{"passed": False, leak: "SECRET"}])

    error = refused(body)

    assert "extra_forbidden" in str(error) or "Extra inputs" in str(error)


def test_no_field_anywhere_can_carry_hidden_test_data() -> None:
    # Fields that could hold a test's content exist on the public test only.
    content = {"input", "expected_output", "actual_output", "stderr"}

    def fields(model: type[BaseModel]) -> set[str]:
        return set(model.model_fields)

    assert content <= fields(PublicTestResult)
    assert not content & fields(HiddenTestResult)
    assert not content & fields(FeedbackRequest)
    assert not content & fields(FeedbackResponse)


@pytest.mark.parametrize(
    "extra",
    ["hiddenInputs", "hiddenExpectedOutputs", "tests", "studentId", "instructions"],
)
def test_an_unknown_top_level_field_is_refused(extra: str) -> None:
    refused(request(**{extra: "SECRET"}))


def test_an_unknown_field_on_a_public_test_is_refused() -> None:
    body = request()
    body["publicTests"][0]["weight"] = 3

    refused(body)


# ---- what is refused ----


@pytest.mark.parametrize(
    "missing",
    [
        "assignmentDescription",
        "language",
        "code",
        "publicTests",
        "hiddenTests",
        "hintLevel",
    ],
)
def test_every_required_field_is_required(missing: str) -> None:
    body = request()
    del body[missing]

    refused(body)


@pytest.mark.parametrize("field", ["assignmentDescription", "language", "code"])
def test_required_text_may_not_be_empty(field: str) -> None:
    refused(request(**{field: ""}))


@pytest.mark.parametrize("level", [0, 4, -1, 1.5, "1", None, True])
def test_a_hint_level_other_than_1_2_or_3_is_refused(level: object) -> None:
    refused(request(hintLevel=level))


@pytest.mark.parametrize("passed", ["true", "yes", 1, 0, None])
def test_passed_must_be_a_real_boolean(passed: object) -> None:
    refused(request(hiddenTests=[{"passed": passed}]))


def test_an_unknown_outcome_is_refused() -> None:
    body = request()
    body["publicTests"][0]["status"] = "ACCEPTED"

    refused(body)


def test_snake_case_names_are_not_the_contract() -> None:
    body = request()
    body["hint_level"] = body.pop("hintLevel")

    refused(body)


def test_a_request_cannot_be_changed_after_it_is_read() -> None:
    parsed = FeedbackRequest.model_validate(request())

    with pytest.raises(ValidationError):
        parsed.hint_level = 3  # type: ignore[misc]


def test_the_example_request_is_not_shared_between_tests() -> None:
    # Guard for the helper itself: changing one copy leaves the next intact.
    first = request()
    second = copy.deepcopy(first)
    first["hiddenTests"].append({"passed": True})

    assert len(request()["hiddenTests"]) == len(second["hiddenTests"]) == 2


# ---- the response ----


def test_the_response_is_the_hint_text() -> None:
    response = FeedbackResponse(hint="Look at the operator.")

    assert response.model_dump() == {"hint": "Look at the operator."}
    assert response.model_dump_json() == '{"hint":"Look at the operator."}'


def test_an_empty_hint_is_refused() -> None:
    with pytest.raises(ValidationError):
        FeedbackResponse(hint="")


def test_the_request_is_written_back_in_camel_case() -> None:
    # What the backend's DTO (7.1a) must match, key for key.
    dumped = FeedbackRequest.model_validate(request()).model_dump(mode="json")

    assert set(dumped) == {
        "assignmentDescription",
        "language",
        "code",
        "compilerOutput",
        "publicTests",
        "hiddenTests",
        "hintLevel",
    }
    assert set(dumped["publicTests"][0]) == {
        "name",
        "input",
        "expectedOutput",
        "status",
        "actualOutput",
        "stderr",
    }
    assert dumped["hiddenTests"] == [{"passed": False}, {"passed": True}]
