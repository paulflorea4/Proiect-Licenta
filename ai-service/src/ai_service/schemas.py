"""The ``POST /feedback`` contract (6.3a): what the backend sends, what it gets back.

The backend's DTO (7.1a) mirrors these models. On the wire every field is camelCase,
like the rest of the platform's JSON (the human's choice); in Python the attributes
are snake_case. Only the camelCase names are accepted.

**Hidden tests are hidden by the shape of the contract.** A hidden test's result is
only ``{"passed": true|false}``: there is no field in which its name, input, expected
output or actual output could travel, and every model refuses fields it does not
declare, so a request that tries to send one is rejected (422) instead of quietly
reaching a prompt. Only a public test carries its input and outputs.
"""

from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictInt
from pydantic.alias_generators import to_camel


class _Contract(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        validate_by_alias=True,
        validate_by_name=False,
        serialize_by_alias=True,
        extra="forbid",
        frozen=True,
    )


class TestOutcome(StrEnum):
    """A test's outcome, exactly the backend's ``TestOutcome`` (4.6a)."""

    __test__ = False  # not a pytest test class, despite the name

    PASSED = "PASSED"
    FAILED = "FAILED"
    TIMEOUT = "TIMEOUT"
    MEMORY_LIMIT = "MEMORY_LIMIT"
    RUNTIME_ERROR = "RUNTIME_ERROR"
    COMPILE_ERROR = "COMPILE_ERROR"


class PublicTestResult(_Contract):
    """A public test in full: the student may see all of it, so the model may too."""

    name: str
    input: str
    expected_output: str
    status: TestOutcome
    # None when no program ran (a compile error, 5.3b).
    actual_output: str | None = None
    stderr: str | None = None


class HiddenTestResult(_Contract):
    """A hidden test: whether it passed, and nothing else."""

    passed: StrictBool


class FeedbackRequest(_Contract):
    assignment_description: str = Field(min_length=1)
    language: str = Field(min_length=1)
    # The student's code: untrusted data, never instructions (6.3b quotes it).
    code: str = Field(min_length=1)
    # The compiler's message when the build failed (the same on every test), else None.
    compiler_output: str | None = None
    public_tests: list[PublicTestResult]
    hidden_tests: list[HiddenTestResult]
    # 1 nudge, 2 pointer, 3 partial explanation. Strict: true, 1.5 and "1" are refused.
    hint_level: StrictInt = Field(ge=1, le=3)


class FeedbackResponse(_Contract):
    hint: str = Field(min_length=1)
