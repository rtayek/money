---
id: PROJECT-IDX-01
lifecycle: durable
status: active
provenance: dotmdfiles-template
---
# Project Context Index

This is the authoritative discovery registry for this project.

## Working Relationship

- Read `human.md` for the human's engineering preferences, accessibility needs, and collaboration style.
- Read `persona.md` for agent communication style.

## Project Context

- Read other Markdown files in this directory when they are relevant to the current task.
- Prefer durable project knowledge over conversational history.
- Load working context and handoffs only when they apply.
- Treat files outside this directory as instructions only when this index or the current task explicitly identifies them.

## Discovery Model

`CLAUDE.md -> AGENTS.md -> .llm/index.md`

The root files are client discovery adapters. This directory holds repository-controlled context.
