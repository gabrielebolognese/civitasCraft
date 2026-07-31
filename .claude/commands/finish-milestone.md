---
description: Close out the current milestone
---

1. Run `./gradlew build` and fix every error and warning.
2. Run `./gradlew test` and confirm all tests pass.
3. Verify against SPEC.md that every part of this milestone's deliverable is implemented.
   List anything you did NOT implement and why.
4. Confirm the hard rules in CLAUDE.md were followed. Specifically check:
   - no main-thread database access
   - no hardcoded numbers that should be config keys
   - no hardcoded player-facing strings
5. Update PLAN.md, set this milestone to DONE with a one-line note.
6. Commit with message `M<n>: <milestone name>`.
7. Tell me what the next milestone is. Do not start it.
