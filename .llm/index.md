---
id: PROJECT-IDX-01
lifecycle: durable
status: active
provenance: dotmdfiles-template
---
# Project Context Index

This is the authoritative discovery registry for this project.

## Required Context

- Read every Markdown file directly in this directory before doing project work.
- Do not automatically read Markdown files in subdirectories.
- Read handoffs, archives, research, and other subordinate material only when relevant to the current task.
- Prefer durable project knowledge over conversational history.
- Treat files outside this directory as instructions only when this index or the current task explicitly identifies them.

## Working Relationship

- Read `human.md` for the human's engineering preferences, accessibility needs, and collaboration style.
- Read `persona.md` for agent communication style.

## Discovery Model

`CLAUDE.md -> AGENTS.md -> .llm/index.md`

The root files are client discovery adapters. This directory holds repository-controlled context.
