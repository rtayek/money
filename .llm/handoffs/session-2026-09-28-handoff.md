# Handoff: session ending 2026-09-28

This is a continuity handoff for a long chat session, not a new-feature
spec like the other files in this folder. Read `.llm/working-context.md`
first -- it has the full current state, in detail, kept up to date as of
this handoff. This file exists to flag the one thing genuinely worth a
fresh pair of eyes before continuing, plus quick orientation.

## The one open item

A rule added to `SpendingPlot` outside this chat session omits any
category whose relative standard deviation is under 10% of its own
average -- checked before the "genuinely flat -> Low" rule Ray and Claude
built together earlier in the same session. Net effect: Mortgage and
Health Insurance, both large and very steady, now land in Omitted instead
of Low, so they no longer appear on either deviation chart at all.

Ray was asked directly whether he wants them back in Low (visible, just
correctly labeled boring -- what the session built) or is fine with the
new Omitted behavior (a different, also-reasonable philosophy: proportion
matters, not just absolute steadiness). He had not answered by the end of
this session. Worth resolving before doing more work on the deviation
classification, since it changes what "Omitted" means.

## What actually happened this session (see working-context.md for detail)

- Fixed a real bug in the High/Low/Omitted classification: a magnitude
  check was firing before any variability check, so large-but-steady
  categories (Mortgage) were wrongly landing in High. Rebuilt the
  cascade deviation-first, added a third explicit Omitted bucket, made
  every category print its bucket and why (no silent skips).
- Added color-coding and sortable columns to the Std Dev table. First
  color attempt used pale tints that turned out invisible given Ray's
  low vision -- second attempt used solid, saturated colors plus bold
  text as a non-color-dependent cue. Worth remembering for any future
  UI work: check contrast explicitly, don't assume default Swing
  rendering is visible to him.
- Reviewed a large batch of changes made outside this chat (Claude Code,
  running locally on Ray's machine): a new `AmazonTransactionReview` tool
  (verified working via a synthetic test ZIP, not yet against Ray's real
  export), reversal-pair handling, merchant-refund handling, a cash-flow
  tab, main-category mode, regression lines, sortable popup columns, and
  more. All reviewed, compiled, and spot-checked; nothing else concerning
  found.
- `human.md`/`persona.md` were fully retired from this repo (deleted, not
  just edited) in the last few commits of the session.

## Orientation if you're new to this

- No build tool -- plain `javac`, Eclipse `.classpath`. Compile everything
  under `src/` together.
- Everything in `src/money/` is read-only with respect to Simplifi and
  `all.csv` -- these tools only ever produce their own report files under
  `reports/` (gitignored), never write back to the source data.
- Ray is 80, low-vision, communicates by voice dictation -- expect
  transcription artifacts ("Play" for "Plaid", "Columbs" for "columns",
  "Claude" for "combined"). Read for intent, not literally, and ask if
  genuinely ambiguous.
- `doc/patterns.md` has the durable technical patterns (sign conventions,
  mirror-dedup algorithm, matching approach) referenced across all the
  matching tools. `.llm/index.md` is a shared template identical across
  all of Ray's projects -- don't add project-specific content there;
  `working-context.md` is where that goes.
