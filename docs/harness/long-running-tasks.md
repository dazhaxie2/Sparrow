# Long-running task protocol

Chat context is temporary. A task JSON file is the resumable execution state for
work that can span sessions, agents or context windows.

## Create and resume

```text
node tools/harness.mjs task:init <lowercase-slug> <title>
node tools/harness.mjs task:status
```

After initialization, fill in objective, in/out scope, acceptance criteria and
steps before changing code. Change `status` from `draft` to `planned` or
`in_progress` only when acceptance criteria are testable.

At session start:

1. Read the task file, `git status`, recent log and relevant `AGENTS.md` files.
2. Reconcile task claims with code/tests; repository evidence wins.
3. Run the cheapest liveness check for the touched area.
4. Continue from `nextAction`, one acceptance criterion or coherent slice at a time.

At every checkpoint:

- update step and acceptance-criterion status;
- record decisions that would otherwise be rediscovered;
- record exact verification commands and short results;
- list blockers without hiding partial progress;
- set one concrete `nextAction` and refresh `updatedAt` in ISO-8601 UTC.

Before `completed`, every acceptance criterion must be `passed` with evidence, no
required step may remain pending, and relevant verification must have run. Do not
delete the file immediately: it is a compact decision and evidence record. Move old
records to `.harness/tasks/archive/` during periodic cleanup.

## Small-slice rule

Implement one observable behaviour at a time. Run a baseline check before starting
the next slice. This prevents a later session from inheriting an attractive but
non-working pile of changes.

## Blocked work

Set `status` to `blocked`, explain the external condition, preserve runnable partial
work, and make `nextAction` the smallest action that unblocks progress. A timeout or
failed command is evidence, not a reason to erase task history.

