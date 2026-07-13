# cloud-itonami-isco-2514

**Community Applications Programming** — the ISCO-08 2514
(Applications Programmers) actor, an ISCO **Wave 0** occupation per
ADR-2607121000: pure-cognitive work, the LLM-first wave, no robotics
gate.

**Maturity: `:implemented`** — ApplicationsProgrammingAdvisor ⊣
ApplicationsProgrammingGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt), modeled on cloud-itonami-isco-4311's bookkeeping actor.
14 tests / 29 assertions green.

The programming-specific HARD invariants:

1. **Requirement basis** — every change must cite a REGISTERED ticket
   (no invented requirements).
2. **Commit-scoped test evidence** — a change submission must cite a
   registered test run that is BOTH `:green` AND for the exact commit
   being submitted. A red run, a missing run, or a green run for a
   different commit are all held at any confidence — "the tests were
   green yesterday" (on another commit) proves nothing. **Evidence
   freshness is identity, not recency**; rerun on THIS commit.

Escalations (always human sign-off): `:deploy-release` (real
deployment), low confidence (< 0.6).

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
