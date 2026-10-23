import pytest

from ai_service.feedback import SAMPLE_REQUEST
from ai_service.leak_guard import (
    LINE_SIMILARITY,
    MAX_CODE_LINES,
    SAFE_HINT,
    Leak,
    find_leak,
)
from ai_service.prompts import MAX_SNIPPET_LINES
from ai_service.schemas import FeedbackRequest

# The sample request's code: `a, b = map(int, input().split())` / `print(a * b)`.
PYTHON = SAMPLE_REQUEST

JAVA = SAMPLE_REQUEST.model_copy(
    update={
        "language": "JAVA",
        "code": (
            "import java.util.Scanner;\n"
            "\n"
            "public class Main {\n"
            "    public static void main(String[] args) {\n"
            "        Scanner in = new Scanner(System.in);\n"
            "        int a = in.nextInt();\n"
            "        int b = in.nextInt();\n"
            "        System.out.println(a * b);\n"
            "    }\n"
            "}\n"
        ),
    }
)


def leak(hint: str, level: int, request: FeedbackRequest = PYTHON) -> Leak | None:
    return find_leak(hint, request.model_copy(update={"hint_level": level}))


def fenced(*lines: str, fence: str = "```") -> str:
    return "\n".join([f"{fence}python", *lines, fence])


# ---- prose passes at every level ----


@pytest.mark.parametrize("level", [1, 2, 3])
@pytest.mark.parametrize(
    "hint",
    [
        "Look again at which arithmetic operation your print statement performs.",
        "If the result is too large, ask yourself whether the operation matches the "
        "description.\nWhat does the task ask you to compute?",
        "In your program, a = 2 and b = 3 give 6, but the test expects 5.",
        "The last line uses the `*` operator; think about what the task asks for.",
        "- First, read the description again.\n- Then compare with your output.",
        "Note (see the test above): your output is 6.",
        SAFE_HINT,
    ],
    ids=[
        "one sentence",
        "two lines",
        "values in prose",
        "an operator in a span",
        "a list",
        "parentheses",
        "the safe hint",
    ],
)
def test_prose_is_not_code(hint: str, level: int) -> None:
    assert leak(hint, level) is None


@pytest.mark.parametrize("level", [1, 2, 3])
@pytest.mark.parametrize(
    "hint",
    [
        "The problem is in the line `print(a * b)`: the operator is the wrong kind.",
        "Look at this line:\n" + fenced("print(a * b)") + "\nWhat does it compute?",
        "Look at this line:\n" + fenced("print( a*b )"),
        "Your whole program:\n"
        + fenced("a, b = map(int, input().split())", "print(a * b)"),
    ],
    ids=["in a span", "in a fence", "spaced differently", "the whole program"],
)
def test_quoting_the_students_own_code_unchanged_is_not_a_leak(
    hint: str, level: int
) -> None:
    assert leak(hint, level) is None


# ---- the allowance per level ----


def test_the_allowance_is_none_at_levels_1_and_2_and_the_snippet_at_level_3() -> None:
    assert MAX_CODE_LINES == {1: 0, 2: 0, 3: MAX_SNIPPET_LINES}


@pytest.mark.parametrize("level", [1, 2])
@pytest.mark.parametrize(
    "hint",
    [
        "For example:\n" + fenced("total = 0"),
        "For example:\n" + fenced("x", fence="~~~"),
        "Try `total = total + x` in a loop.",
        "Try `sum(numbers)`.",
        "Write it like this:\ntotal = 0",
        "Write it like this:\n    for x in numbers:",
        "Write it like this:\nwhile (i < n) {",
        "Write it like this:\nresult.add(x);",
        "Write it like this:\n}",
        "Write it like this:\nsum(numbers)",
        "Open a fence and never close it:\n```\ntotal = 0",
    ],
    ids=[
        "a fenced line",
        "a tilde fence",
        "an assignment in a span",
        "a call in a span",
        "an assignment",
        "a loop header",
        "a brace",
        "a statement",
        "a closing brace",
        "a call",
        "an unclosed fence",
    ],
)
def test_any_new_code_is_too_much_at_levels_1_and_2(hint: str, level: int) -> None:
    assert leak(hint, level) is Leak.TOO_MUCH_CODE


def snippet(lines: int) -> str:
    return "On a different example:\n" + fenced(*(f"v{i} = {i}" for i in range(lines)))


def test_a_snippet_of_the_allowed_size_passes_at_level_3() -> None:
    assert leak(snippet(MAX_SNIPPET_LINES), 3) is None
    assert leak(snippet(MAX_SNIPPET_LINES + 1), 3) is Leak.TOO_MUCH_CODE


def test_an_illustrative_snippet_on_another_example_passes_at_level_3() -> None:
    hint = (
        "Your program combines the two numbers with the wrong operation. Here is the "
        "idea of accumulating on another example:\n"
        + fenced("total = 0", "for x in [1, 2, 3]:", "    total = total + x")
        + "\nThink about which operation your task needs."
    )
    assert leak(hint, 3) is None


def test_lines_of_code_are_counted_across_fences_spans_and_prose() -> None:
    # Two fenced lines, one span, two lines of prose code: six in all with the
    # third fenced block's line, one more than the level-3 allowance.
    hint = "\n".join(
        [
            fenced("v1 = 1", "v2 = 2"),
            "Then `v3 = 3` and:",
            "v4 = 4",
            "v5 = 5",
            fenced("v6 = 6", fence="~~~"),
        ]
    )
    assert leak(hint, 3) is Leak.TOO_MUCH_CODE
    assert leak(hint.replace("v6 = 6", ""), 3) is None


def test_blank_lines_in_a_fence_are_not_code() -> None:
    hint = fenced("v1 = 1", "", "   ", "v2 = 2", "", "v3 = 3", "v4 = 4", "v5 = 5")
    assert leak(hint, 3) is None


def test_a_longer_fence_is_not_closed_by_a_shorter_one() -> None:
    lines = "\n".join(f"v{i} = {i}" for i in range(MAX_SNIPPET_LINES - 1))
    # Inside the four-backtick block: the snippet's lines, the inner three backticks
    # and the sentence, one more than the allowance. Were the inner fence to close
    # the block, the sentence would be prose and the snippet within the allowance.
    hint = f"````\n{lines}\n```\nThis sentence is still in the block.\n````\n"
    assert leak(hint, 3) is Leak.TOO_MUCH_CODE
    assert leak(hint.replace("````", "```"), 3) is None


# ---- a complete program, at any level ----


@pytest.mark.parametrize(
    "code",
    [
        ("n = int(input())", "print(n * 2)"),
        ("import sys", "data = sys.stdin.read().split()", "print(len(data))"),
        ("public class Main {", "  public static void main(String[] a) {", "  }", "}"),
        ('if __name__ == "__main__":', "    main()"),
        ("int main() {", "  return 0;", "}"),
    ],
    ids=["python input/print", "python stdin", "java main", "python entry", "c main"],
)
def test_a_complete_program_is_a_leak_even_within_the_allowance(
    code: tuple[str, ...],
) -> None:
    assert len(code) <= MAX_SNIPPET_LINES
    assert leak("Here:\n" + fenced(*code), 3) is Leak.COMPLETE_PROGRAM


def test_a_snippet_that_only_prints_or_only_reads_is_not_a_program() -> None:
    assert leak(fenced("x, y = 4, 5", "print(x + y)"), 3) is None
    assert leak(fenced("line = input()", "words = line.split()"), 3) is None


def test_reading_and_printing_quoted_from_the_students_code_is_not_new() -> None:
    hint = (
        "Your program:\n"
        + fenced("a, b = map(int, input().split())")
        + ("\nand later:\n" + fenced("print(a * b)"))
    )
    assert leak(hint, 3) is None


# ---- the student's own line, edited ----


@pytest.mark.parametrize("level", [1, 2, 3])
@pytest.mark.parametrize(
    ("hint", "request_"),
    [
        ("Write:\n" + fenced("print(a + b)"), PYTHON),
        ("Write `print(a + b)` instead.", PYTHON),
        (
            "Fixed:\n" + fenced("a, b = map(int, input().split())", "print(a + b)"),
            PYTHON,
        ),
        ("Change it to:\n```java\nSystem.out.println(a + b);\n```", JAVA),
        ("Use `System.out.println(a + b);` there.", JAVA),
    ],
    ids=[
        "the fixed line",
        "the fixed line in a span",
        "the corrected program",
        "a fixed line of a longer program",
        "a fixed line of a longer program in a span",
    ],
)
def test_an_edited_line_of_the_students_code_is_a_leak(
    hint: str, request_: FeedbackRequest, level: int
) -> None:
    expected = Leak.TOO_MUCH_CODE if level < 3 else Leak.EDITED_STUDENT_LINE
    assert leak(hint, level, request_) is expected


def test_the_same_idea_on_other_names_is_not_an_edit() -> None:
    assert LINE_SIMILARITY == 0.8
    assert leak("For example:\n" + fenced("print(x + y)"), 3) is None
    assert leak("For example:\n" + fenced("print(a + b)"), 3) is (
        Leak.EDITED_STUDENT_LINE
    )


def test_the_check_is_pure() -> None:
    request = PYTHON.model_copy(update={"hint_level": 3})
    hint = "Write:\n" + fenced("print(a + b)")
    assert find_leak(hint, request) is find_leak(hint, request)
    assert request == PYTHON.model_copy(update={"hint_level": 3})
