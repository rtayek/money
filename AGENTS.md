> Purpose: General AI agent behavior rules for any codebase.
> Scope: What agents may do freely, must ask before, and must never do.

# Agents

Guidelines for AI agents working in any project. Before doing anything,
agents MUST read `.llm/index.md` and follow its instructions. The index is the
authoritative route to human, persona, and project-specific context.

## Status

This document is normative. Where it uses **MUST / MUST NOT / SHOULD / MAY**, those words are used in the RFC sense.

If this file's rules conflict with a project's `.llm/index.md`, this file
takes precedence for agent behavior; `.llm/index.md` takes precedence for
project-specific context.

## What agents may do freely

Agents may take any action not restricted by the sections below,
except deleting the source code control repository (usually `.git`).

Agents may:

- Read access to the project folders and files.
- Commit to a local Git repository.

## What agents must ask before doing

- Delete files permanently if they are not tracked by the source code control system (usually this is git).
- Renaming or moving files that are not tracked by the source code control system.
- Adding new dependencies.
- Making architectural changes (changes to module boundaries, public APIs, dependency structure, or overall design) -- including introducing new abstractions, frameworks, or design patterns not present in the existing code, even if they seem like an improvement.
- Doing more than the task asked for. If a fix reveals other things worth changing, name them and stop; do not fix them in the same pass without asking.
- Pushing the Git repository.

## What agents must never do

- Modify configuration files (`.env`, secrets, CI pipelines) without explicit instruction.
- Push to a Git repository without explicit instruction.
- Leave background processes running after the task ends.
- Commit scratch, cache, or tool-generated files (e.g. `.aider*`, build output, lock files not part of the project) -- add them to `.gitignore` instead.
- Expand scope beyond what was asked, even when the additional work seems clearly good.

## What agents should do

- Ask when anything is unclear.
- Explain non-obvious changes.

## Commit messages

- First line: what changed, factual, present tense.
- Message MUST NOT claim anything the change doesn't actually do.
- If a decision was made, add: `Decision: <what, and why, briefly>`.
- If something is still unresolved, add: `Open: <what>`.

## Artifact Delivery Method

Deliver each qualifying artifact using the first available method:

- If writing to the project and downloads are both unsupported by the interface, provide the artifact in a fenced block.
- Any handoff artifact MUST include '[Hh]andoff' in the filename.