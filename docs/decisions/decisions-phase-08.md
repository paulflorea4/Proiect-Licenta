# Decisions / Assumptions — Phase 8 (`docs/tasks/tasks-phase-08.md`)

Historical, append-only. One entry per commit where a non-obvious call was made — a gap in the task spec, a gate resolution, a formatter/tool choice, a bug found and fixed. Not read every session — see the note in root `CLAUDE.md`. Load this only when auditing a specific Phase 8 decision.

## 8.0a — Numeric values for enum fields are a 400 (review, low)

- **The finding was real (the review had not run it):** before the fix, `{"role":2}` was accepted as `ADMIN` (a numeric value a 200, not the 400 that 2.5b documents), `"type":0` stored a `TESTS` criterion, and `"visibility":0`/`1`/`"1"` were `PUBLIC`/`HIDDEN`; the new test rows failed 10 times against the unchanged code.
- **The switch moved in Jackson 3:** `FAIL_ON_NUMBERS_FOR_ENUMS` is an `EnumFeature` in `jackson-databind` 3.1 (checked with `javap`), no longer a `DeserializationFeature`, so the property from the 3.4a suggestion (`spring.jackson.deserialization.fail-on-numbers-for-enums`) would not bind to anything. Spring Boot 4.1 exposes `EnumFeature` as `spring.jackson.datatype.enum.*` (its configuration metadata), so the setting is `spring.jackson.datatype.enum.fail-on-numbers-for-enums: true`, in the common part of `application.yml`.
- **For every enum of every body at once:** one mapper setting, not a check per field. The three enum fields in request bodies today are `RoleChangeRequest.role`, `RubricCriterionRequest.type` and `TestCaseRequest.visibility`; a number or a numeric string in any is 400 `MALFORMED_REQUEST` (Jackson's read error, as any unreadable body), and nothing is stored.
- **Not done here:** the twin finding in the same 3.4a suggestion (a fractional number accepted for an integer field and truncated, `accept-float-as-int`) is not this row.
- **Tests (+11, backend 2551 -> 2562; ai-service 985 and frontend 3 unchanged and passing):** `RealHttpAdminRoleTests` four more bodies (`0`, `1`, `2`, `"2"`), each 400 with the role unchanged; `RealHttpRubricTests` `"0"` among the unknown types and a new test for `0` and `1` (400 `MALFORMED_REQUEST`, nothing stored); `RealHttpTestCaseTests` `0`, `1`, `"1"` among the refused visibilities; `ApplicationConfigTests.numbersAreNotAcceptedForEnumFields` pins the YAML value.
- **Mutation-checked:** the same tests without the setting fail 10 times (the run before the fix).
