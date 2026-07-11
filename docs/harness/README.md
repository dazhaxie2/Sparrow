# Sparrow Harness

This harness is the repository control layer for coding agents. It makes project
knowledge discoverable, narrows the solution space, records long-running work and
turns important boundaries into executable feedback.

## Six layers

| Layer | Sparrow implementation |
| --- | --- |
| Information boundary | Root and nested `AGENTS.md`, focused documents under `docs/harness/` |
| Tools | `tools/harness.mjs`, existing smoke/load/migration scripts |
| Orchestration | Small-slice work loop and change-aware verification |
| Memory/state | Versioned task JSON under `.harness/tasks/` |
| Evaluation/observation | Unit tests, frontend build, architecture guards, smoke tests and task evidence |
| Constraints/recovery | Machine-readable manifest, deterministic guards and failure playbook |

## Start here

```text
node tools/harness.mjs doctor
node tools/harness.mjs task:init research-recovery "Make research resume after restart"
node tools/harness.mjs changed
```

Use a task file when work may cross sessions or context windows. For a small change,
the root work loop and `changed` verification are enough.

## Design principles

- `AGENTS.md` is a map, not an encyclopedia.
- Durable knowledge is versioned beside the code it describes.
- Mechanical rules enforce stable boundaries; prose explains intent and exceptions.
- Validation includes observable behaviour, not only agent-authored unit tests.
- A repeated failure becomes a repository improvement: test, guard, diagnostic or
  recovery note.
- The harness evolves incrementally with the codebase; it is not a one-time prompt.

## Maintainers' index

- `architecture.md`: topology, ownership and dependency rules.
- `verification.md`: validation levels and change-to-check matrix.
- `long-running-tasks.md`: task state and continuation protocol.
- `failure-playbook.md`: diagnosis and recovery for recurring failures.
- `runtime-ai-chat.md`: Harness lifecycle used by every product AI chat surface.
- `admin-agent-config.md`: service-owned Agent prompts, runtime limits, identity and admin bootstrap.
- `.harness/manifest.json`: facts consumed by deterministic tooling.
