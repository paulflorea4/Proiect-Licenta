"""The solution-leak guard (6.4a): does a hint hold more code than its level allows,
or something that looks like a complete solution?

The prompt (:mod:`ai_service.prompts`) already asks for no code at levels 1 and 2 and
at most one short snippet at level 3; this module checks the answer in code, because a
model does not always follow its instructions (and a student's code may try to talk it
out of them). :func:`find_leak` is a pure function of the hint and the request.

Code in a hint is found three ways: every non-blank line of a fenced block, a line of
prose that reads as a statement, and an inline code span that does. A line copied
unchanged from the student's own code is not the model writing code (a hint may quote
the line it is about), so it is not counted against the level's allowance.
"""

import re
from difflib import SequenceMatcher
from enum import Enum

from ai_service.prompts import MAX_SNIPPET_LINES
from ai_service.schemas import FeedbackRequest

# The most lines of new code a hint may hold, per level: none for a nudge or a pointer,
# the illustrative snippet the prompt allows for a partial explanation.
MAX_CODE_LINES: dict[int, int] = {1: 0, 2: 0, 3: MAX_SNIPPET_LINES}

# A new line of code this similar to a line of the student's (difflib's ratio,
# whitespace ignored) is that line edited: the fix. A placeholder: 0.8 holds
# `print(a + b)` against `print(a * b)` and leaves `print(x + y)`, the same idea on
# other names, apart.
LINE_SIMILARITY = 0.8

# Returned instead of a hint when the regenerated answer leaks too (6.4a). It gives no
# information about the program, so it is safe at every level.
SAFE_HINT = (
    "Pick a public test your program fails and work through your code by hand with "
    "that test's input, writing down the value of each variable as you go. Compare "
    "what you get with the expected output and look for the first step where they "
    "part ways."
)


class Leak(Enum):
    """Why a hint was refused."""

    TOO_MUCH_CODE = "more code than the hint level allows"
    COMPLETE_PROGRAM = "a program that reads the input and prints the answer"
    EDITED_STUDENT_LINE = "a line of the student's code with a change in it"


_FENCE_OPEN = re.compile(r"^\s*(?P<fence>`{3,}|~{3,})")
_INLINE_CODE = re.compile(r"(?P<ticks>`+)(?P<code>.+?)(?P=ticks)")

# A Markdown container in front of a line: a blockquote, a bullet or a numbered item.
# It is not part of the line's text (6.6c: a whole program written as a numbered list
# passed every level while the marker hid it).
_CONTAINER = re.compile(r"^(?:>\s*)*(?:(?:[-*+]|\d{1,3}[.)])\s+)?")

# A line of code, outside a fence, once its container is removed. Prose lines start
# with a capital letter and end with a full stop; these do not.
_KEYWORD_START = re.compile(
    r"^(def|class|import|from|for|while|if|elif|else|try|except|finally|return|with"
    r"|switch|case|do|public|private|protected|static|final|void|int|long|double"
    r"|float|boolean|char|String|var|let|const|function|package|using|#include)\b"
)
_STATEMENT_END = re.compile(r"[;{}]$|^[{}]")
_ASSIGNMENT = re.compile(
    r"^[A-Za-z_][\w.]*(\[[^\]]*\])*(\s*,\s*[A-Za-z_][\w.]*)*\s*"
    r"(\+|-|\*\*|\*|//|/|%)?=(?!=)\s*\S"
)
_CALL = re.compile(r"^[A-Za-z_][\w.]*\(.*\)\s*;?$")

# What a whole program for a stdin-to-stdout assignment does: read the input, print the
# answer, or declare an entry point. One list for every language (no branch per
# language); a new language may add its own words here.
_READS_INPUT = re.compile(
    r"\binput\s*\(|\bsys\.stdin\b|\bScanner\s*\(|\bSystem\.in\b|\bBufferedReader\b"
    r"|\breadline\s*\(|\bscanf\s*\(|\bcin\b"
)
_PRINTS = re.compile(
    r"\bprint\s*\(|\bSystem\.out\b|\bsys\.stdout\b|\bprintf\s*\(|\bcout\b"
)
_ENTRY_POINT = re.compile(r"\bstatic\s+void\s+main\s*\(|__name__|\bint\s+main\s*\(")


def find_leak(hint: str, request: FeedbackRequest) -> Leak | None:
    """Why this hint must not reach the student, or ``None`` if it may.

    The checks, in order: new code beyond ``MAX_CODE_LINES`` for the request's level;
    new code that reads the input and prints, or declares an entry point (a whole
    program, at any level); a new line that is one of the student's lines edited
    (``LINE_SIMILARITY``): the fix itself, or their program corrected, which level 3
    must stop short of as much as level 1.
    """
    student = _student_lines(request.code)
    code = [line for line in _code_lines(hint) if _normalized(line)]
    new = [line for line in code if _normalized(line) not in student]
    if len(new) > MAX_CODE_LINES[request.hint_level]:
        return Leak.TOO_MUCH_CODE
    new_text = "\n".join(new)
    if _ENTRY_POINT.search(new_text) or (
        _READS_INPUT.search(new_text) and _PRINTS.search(new_text)
    ):
        return Leak.COMPLETE_PROGRAM
    if any(_similar(_normalized(line), s) for line in new for s in student):
        return Leak.EDITED_STUDENT_LINE
    return None


def _code_lines(hint: str) -> list[str]:
    """Every line of code in the hint: fenced lines, statement-like lines of prose and
    statement-like inline spans. A fence left open runs to the end of the hint."""
    lines: list[str] = []
    fence: str | None = None
    for raw in hint.splitlines():
        # Lines are kept without their container, so a quoted line of the student's
        # still matches their code.
        line = _without_container(raw)
        if fence is not None:
            closing = _FENCE_OPEN.match(line)
            if closing and _closes(closing.group("fence"), fence):
                fence = None
            elif line.strip():
                lines.append(line)
            continue
        opening = _FENCE_OPEN.match(line)
        if opening:
            fence = opening.group("fence")
        elif _looks_like_code(line):
            lines.append(line)
        else:
            lines += [
                span.group("code")
                for span in _INLINE_CODE.finditer(line)
                if _looks_like_code(span.group("code"))
            ]
    return lines


def _closes(candidate: str, opening: str) -> bool:
    return candidate[0] == opening[0] and len(candidate) >= len(opening)


def _without_container(line: str) -> str:
    return _CONTAINER.sub("", line.strip(), count=1)


def _looks_like_code(text: str) -> bool:
    line = _without_container(text)
    return bool(
        _STATEMENT_END.search(line)
        or (_KEYWORD_START.match(line) and re.search(r"[:;{)]$|[(=\[]", line))
        or _ASSIGNMENT.match(line)
        or _CALL.match(line)
    )


def _normalized(line: str) -> str:
    return re.sub(r"\s+", "", line)


def _student_lines(code: str) -> set[str]:
    return {n for n in map(_normalized, code.splitlines()) if n}


def _similar(a: str, b: str) -> bool:
    matcher = SequenceMatcher(None, a, b, autojunk=False)
    return (
        matcher.real_quick_ratio() >= LINE_SIMILARITY
        and matcher.quick_ratio() >= LINE_SIMILARITY
        and matcher.ratio() >= LINE_SIMILARITY
    )
