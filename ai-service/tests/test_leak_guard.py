import json

import pytest

from ai_service.feedback import SAMPLE_REQUEST, generate_feedback
from ai_service.leak_guard import (
    LINE_SIMILARITY,
    MAX_CODE_LINES,
    PROGRAM_SHARE,
    SAFE_HINT,
    Leak,
    find_leak,
)
from ai_service.prompts import MAX_SNIPPET_LINES
from ai_service.schemas import FeedbackRequest, FeedbackResponse
from fakes import ScriptedProvider

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


# The same task with the arithmetic in a function: `return a * b`.
FUNCTION = SAMPLE_REQUEST.model_copy(
    update={
        "code": (
            "def solve(a, b):\n"
            "    return a * b\n"
            "\n"
            "a, b = map(int, input().split())\n"
            "print(solve(a, b))\n"
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
        # 7.7l: a statement keyword followed by words, not an expression.
        "- if the result is too large, look at the operator\n- return to the "
        "description and read it again\n- while reading the input, note both values",
        "If you are done, return the result.",
    ],
    ids=[
        "one sentence",
        "two lines",
        "values in prose",
        "an operator in a span",
        "a list",
        "parentheses",
        "the safe hint",
        "keywords starting bullets",
        "a sentence ending in a full stop",
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
        # 7.7l: the full stop is the sentence's, not the line's.
        "Look at this line:\nprint(a * b).",
    ],
    ids=[
        "in a span",
        "in a fence",
        "spaced differently",
        "the whole program",
        "with a full stop",
    ],
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
        # 7.7l: bare statements without the punctuation the other patterns want.
        "Write it like this:\nreturn a + b",
        "Write it like this:\nif total > limit",
        "Try `return a + b`.",
        "Write it like this:\nsum(numbers).",
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
        "a bare return",
        "a bare condition",
        "a bare return in a span",
        "a call with a full stop",
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
        # 7.7l: before, neither was seen as code, so neither was compared.
        ("Write:\nprint(a + b).", PYTHON),
        ("Write it as\nreturn a + b", FUNCTION),
    ],
    ids=[
        "the fixed line",
        "the fixed line in a span",
        "the corrected program",
        "a fixed line of a longer program",
        "a fixed line of a longer program in a span",
        "the fixed line with a full stop",
        "a fixed bare return",
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


# ---- 6.6c: a full solution is caught at every level, in every shape ----

PY = ["x, y = map(int, input().split())", "print(x + y)"]
JAVA_MAIN = [
    "import java.util.Scanner;",
    "public class Main {",
    "  public static void main(String[] x) {",
    "    Scanner s = new Scanner(System.in);",
    "    System.out.println(s.nextInt() + s.nextInt());",
    "  }",
    "}",
]
JAVA_BODY = JAVA_MAIN[3:5]


def lines(*rows: str) -> str:
    return "\n".join(rows)


# Full solutions for the sample task (read two numbers, print the sum), written the
# ways a model may write code: fenced or not, in a list, quoted, split, one line.
FULL_SOLUTIONS: dict[str, tuple[str, FeedbackRequest]] = {
    "fenced": ("Here:\n" + fenced(*PY), PYTHON),
    "tilde fence": ("Here:\n" + lines("~~~", *PY, "~~~"), PYTHON),
    "fence without a language": (lines("```", *PY, "```"), PYTHON),
    "indented block": ("Here:\n" + lines(*("    " + row for row in PY)), PYTHON),
    "bare lines": ("Here:\n" + lines(*PY), PYTHON),
    "inline spans": (f"Write `{PY[0]}` and then `{PY[1]}`.", PYTHON),
    "one line": ("Just use\nprint(sum(map(int, input().split())))", PYTHON),
    "sys.stdin": (
        fenced("import sys", "print(sum(map(int, sys.stdin.read().split())))"),
        PYTHON,
    ),
    "numbered list": (f"Steps:\n1. {PY[0]}\n2. {PY[1]}", PYTHON),
    "numbered with parenthesis": (f"Steps:\n1) {PY[0]}\n2) {PY[1]}", PYTHON),
    "bullets": (f"Steps:\n- {PY[0]}\n- {PY[1]}", PYTHON),
    "star bullets": (f"Steps:\n* {PY[0]}\n* {PY[1]}", PYTHON),
    "blockquote": (f"Here:\n> {PY[0]}\n> {PY[1]}", PYTHON),
    "nested quote and list": (f"Here:\n> 1. {PY[0]}\n> 2. {PY[1]}", PYTHON),
    "fence in a list item": ("Steps:\n1. " + fenced(*PY), PYTHON),
    "split over two fences": (fenced(PY[0]) + "\nthen\n" + fenced(PY[1]), PYTHON),
    "with comments": (fenced("# read", PY[0], "# answer", PY[1]), PYTHON),
    "function and call": (
        fenced("def solve():", "    " + PY[0], "    return x + y", "print(solve())"),
        PYTHON,
    ),
    "windows line ends": ("Here:\r\n" + fenced(*PY).replace("\n", "\r\n"), PYTHON),
    "the student's program corrected": (
        fenced("a, b = map(int, input().split())", "print(a + b)"),
        PYTHON,
    ),
    "java program": (
        "Here:\n" + lines("```java", *JAVA_MAIN, "```"),
        JAVA,
    ),
    "java body only": ("Inside main:\n" + lines("```java", *JAVA_BODY, "```"), JAVA),
    "java numbered": (f"Steps:\n1. {JAVA_BODY[0]}\n2. {JAVA_BODY[1]}", JAVA),
    "java corrected line": ("Change it to `System.out.println(a + b);`", JAVA),
    # Neither reads nor edits a student line: only its entry point gives it away.
    "python main guard": (
        fenced(
            "def solve(p, q):",
            "    return p + q",
            'if __name__ == "__main__":',
            "    print(solve(2, 3))",
        ),
        PYTHON,
    ),
}


# 7.7k: programs whose fix is a whole line added, removed or moved, which no single
# line of the corrected program shows (each line is the student's or plainly new).
def request_for(*code: str) -> FeedbackRequest:
    return SAMPLE_REQUEST.model_copy(update={"code": "\n".join(code) + "\n"})


# Adds the text as strings: the fix is a conversion line before the sum.
STRINGS = request_for("a, b = input().split()", "print(a + b)")
# One line too many: the fix is removing it.
EXTRA_LINE = request_for(
    "a, b = map(int, input().split())", "a = a - 1", "print(a + b)"
)
# Prints before adding up: the fix is moving the print to the end.
EARLY_PRINT = request_for(
    "nums = list(map(int, input().split()))",
    "total = 0",
    "print(total)",
    "for n in nums:",
    "    total += n",
)

REWORKED_PROGRAMS: dict[str, tuple[str, FeedbackRequest]] = {
    "a line added": (
        fenced("a, b = input().split()", "a, b = int(a), int(b)", "print(a + b)"),
        STRINGS,
    ),
    "the faulty line removed": (
        fenced("a, b = map(int, input().split())", "print(a + b)"),
        EXTRA_LINE,
    ),
    "the faulty line removed, numbered": (
        "Steps:\n1. a, b = map(int, input().split())\n2. print(a + b)",
        EXTRA_LINE,
    ),
    "a line moved": (
        fenced(
            "nums = list(map(int, input().split()))",
            "total = 0",
            "for n in nums:",
            "    total += n",
            "print(total)",
        ),
        EARLY_PRINT,
    ),
}
FULL_SOLUTIONS |= REWORKED_PROGRAMS


@pytest.mark.parametrize("level", [1, 2, 3])
@pytest.mark.parametrize("name", sorted(FULL_SOLUTIONS))
def test_a_full_solution_is_refused_at_every_level(name: str, level: int) -> None:
    hint, request = FULL_SOLUTIONS[name]

    found = leak(hint, level, request)

    assert found is not None
    if level == 3:
        # Within the snippet allowance, it is refused as a solution, not for size.
        assert found in {
            Leak.COMPLETE_PROGRAM,
            Leak.EDITED_STUDENT_LINE,
            Leak.REPRODUCED_PROGRAM,
        }


@pytest.mark.parametrize("level", [1, 2, 3])
@pytest.mark.parametrize(
    "name",
    [
        "numbered list",
        "bullets",
        "blockquote",
        "java program",
        "the faulty line removed",
    ],
)
def test_a_full_solution_from_the_model_twice_gives_the_safe_hint(
    name: str, level: int
) -> None:
    hint, request = FULL_SOLUTIONS[name]
    answer = json.dumps({"hint": hint})
    provider = ScriptedProvider(answer, answer)

    response = generate_feedback(
        provider, request.model_copy(update={"hint_level": level})
    )

    assert response == FeedbackResponse(hint=SAFE_HINT, fallback=True)
    assert len(provider.prompts) == 2


# ---- 6.6c: a short illustrative snippet is accepted at level 3 ----

SNIPPETS = {
    "python accumulation": fenced(
        "total = 0", "for x in [1, 2, 3]:", "    total = total + x"
    ),
    "python with a comment": fenced("# multiply two numbers", "result = 4 * 5"),
    "printing only": fenced("x, y = 4, 5", "print(x - y)"),
    "reading only": fenced("line = input()", "words = line.split()"),
    "java on another example": lines(
        "```java", "int total = 0;", "for (int x : xs) {", "    total += x;", "}", "```"
    ),
    "five numbered lines": lines(*(f"{i}. v{i} = {i}" for i in range(1, 6))),
    "in a bulleted list": "- total = 0\n- total = total + x",
}


@pytest.mark.parametrize("name", sorted(SNIPPETS))
def test_a_short_snippet_on_another_example_is_accepted_at_level_3_only(
    name: str,
) -> None:
    hint = "The idea on another example:\n" + SNIPPETS[name]

    assert leak(hint, 3) is None
    assert leak(hint, 1) is Leak.TOO_MUCH_CODE
    assert leak(hint, 2) is Leak.TOO_MUCH_CODE


def test_six_lines_in_a_list_are_one_too_many_at_level_3() -> None:
    hint = lines(*(f"{i}. v{i} = {i}" for i in range(1, MAX_SNIPPET_LINES + 2)))

    assert leak(hint, 3) is Leak.TOO_MUCH_CODE


@pytest.mark.parametrize("level", [1, 2, 3])
@pytest.mark.parametrize(
    "hint",
    [
        "Try this:\n1. Read the description again.\n2. Compare your output with "
        "the expected output.\n3. Look at the operator you used.",
        "Things to check:\n- Which operation does the task ask for?\n- What does "
        "your print statement compute?",
        "Things to check:\n- whether the operation matches the description\n- what "
        "your program prints for 2 and 3",
        "The task says:\n> Read two integers and print their sum.",
        "Look at this line:\n1. print(a * b)",
        "Look at:\n> print(a * b)",
        "Look at:\n> " + fenced("print(a * b)"),
    ],
    ids=[
        "numbered steps",
        "bullets",
        "lowercase bullets",
        "quoted task",
        "own line in a list",
        "own line quoted",
        "own line in a quoted fence",
    ],
)
def test_prose_lists_and_quotes_are_not_code(hint: str, level: int) -> None:
    assert leak(hint, level) is None


# ---- 7.7k: most of the student's program, reworked ----


def test_the_share_is_the_humans_value() -> None:
    assert PROGRAM_SHARE == 0.6


@pytest.mark.parametrize("name", sorted(REWORKED_PROGRAMS))
def test_a_reworked_program_is_refused_for_what_it_is(name: str) -> None:
    # Before 7.7k each passed at level 3, and the removed or moved line at every level:
    # no line in them is new code, or the one new line is not an edit of another.
    hint, request = REWORKED_PROGRAMS[name]

    assert leak(hint, 3, request) is Leak.REPRODUCED_PROGRAM


@pytest.mark.parametrize("level", [1, 2])
@pytest.mark.parametrize(
    "name",
    ["the faulty line removed", "the faulty line removed, numbered", "a line moved"],
)
def test_a_program_reworked_with_no_new_line_is_refused_at_levels_1_and_2_too(
    name: str, level: int
) -> None:
    hint, request = REWORKED_PROGRAMS[name]

    assert leak(hint, level, request) is Leak.REPRODUCED_PROGRAM


@pytest.mark.parametrize("level", [1, 2, 3])
@pytest.mark.parametrize(
    "hint",
    [
        fenced("a, b = map(int, input().split())", "a = a - 1", "print(a + b)"),
        fenced("a, b = map(int, input().split())", "", "a = a - 1", "", "print( a+b )"),
    ],
    ids=["as written", "spaced differently"],
)
def test_the_students_whole_program_quoted_as_written_is_not_a_rework(
    hint: str, level: int
) -> None:
    assert leak("Your program:\n" + hint, level, EXTRA_LINE) is None


def test_a_comment_pointing_at_a_line_does_not_make_a_quote_a_rework() -> None:
    # Level 3 only: at levels 1 and 2 the comment is new code (6.4a), refused as such.
    hint = fenced(
        "a, b = map(int, input().split())",
        "# look at the next line",
        "a = a - 1",
        "print(a + b)",
    )
    assert leak("Your program:\n" + hint, 3, EXTRA_LINE) is None
    assert leak("Your program:\n" + hint, 1, EXTRA_LINE) is Leak.TOO_MUCH_CODE


@pytest.mark.parametrize("level", [1, 2, 3])
def test_quoting_lines_of_the_program_one_at_a_time_is_not_a_block(level: int) -> None:
    # Two of three lines, but each in its own span within a sentence.
    hint = (
        "You read the numbers with `a, b = map(int, input().split())` and print "
        "with `print(a + b)`. What happens to `a` in between?"
    )
    assert leak(hint, level, EXTRA_LINE) is None


def test_a_block_below_the_share_is_not_a_rework_and_one_at_it_is() -> None:
    program = [f"v{i} = {i} * k" for i in range(6)]
    request = request_for(*program)
    # Three of six lines in another order: half, below the share.
    assert leak(fenced(program[2], program[0], program[1]), 3, request) is None
    # Four of six: two thirds, at least the share.
    assert (
        leak(fenced(program[3], program[2], program[0], program[1]), 3, request)
        is Leak.REPRODUCED_PROGRAM
    )


def test_braces_alone_do_not_make_a_snippet_most_of_a_program() -> None:
    # The program's only meaningful line is `run();`. Counting `{` and `}`, this
    # snippet would show two of three lines; they say nothing about the program.
    request = SAMPLE_REQUEST.model_copy(
        update={"language": "JAVA", "code": "{\n  run();\n}\n"}
    )
    hint = "The same shape on another example:\n```java\n{\n  walk();\n}\n```"

    assert leak(hint, 3, request) is None


@pytest.mark.xfail(
    strict=True,
    reason="known limit (7.7k): the share is per block, so a rework split over two "
    "blocks is not seen",
)
def test_a_reworked_program_split_over_two_blocks_is_refused() -> None:
    hint = (
        "First:\n"
        + fenced("a, b = map(int, input().split())")
        + "\nthen:\n"
        + fenced("print(a + b)")
    )
    assert leak(hint, 1, EXTRA_LINE) is not None


@pytest.mark.xfail(
    strict=True,
    reason="known limit (6.4a, 6.6c): code inside a sentence of prose is not seen",
)
def test_a_solution_written_inside_a_sentence_is_refused() -> None:
    hint = (
        "Read both with x, y = map(int, input().split()) and then print(x + y) at "
        "the end."
    )
    assert leak(hint, 3) is not None
