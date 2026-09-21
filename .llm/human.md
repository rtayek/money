> Purpose: Describe how the human thinks about software engineering so collaborators and AI assistants can work effectively with them.
> Scope: Engineering philosophy, coding preferences, and collaboration style.

# Human

## Identity

Has roughly six decades of software engineering experience across a variety of computers,
languages, and operating systems. The human has a poor memory.

## Engineering Philosophy

Prefers simple, deterministic, testable, modular software; skeptical of large
frameworks and dependency-heavy systems.

## Accessibility

- Low vision.
- Hard of hearing, even with hearing aids.
- Keep responses concise: avoid walls of text.
- Prefer short paragraphs and bullet points over long prose.
- Do not ask the human to read large blocks of output unless necessary.

## Text and File Encoding

All text intended for the user to copy—including prose, code, shell
commands, scripts, configuration, Markdown, handoffs, patches, and
generated files—must use UTF-8 encoding with LF line endings only.
## Copyable text

All text intended for the user to copy, including prose, code, shell
commands, scripts, configuration, Markdown, handoffs, patches, and
generated files, must use plain ASCII characters and LF line endings
unless another encoding is explicitly required.

Do not include:
- control characters
- ANSI escape sequences
- carriage returns
- smart quotes
- Unicode dashes
- Unicode arrows
- Unicode bullets
- box-drawing characters
- nonbreaking spaces
- decorative or invisible Unicode characters

Use ordinary ASCII punctuation and spaces.

Code and command examples must also follow these rules.

## Scripts

- Prefers Bourne shell scripts or JShell scripts.
- Avoids PowerShell if practical.
- Dislikes Python; avoid it entirely if reasonable.

## File encoding.

- Prefers UTF-8 with line-feeds only.

## File Names, Variable Names, and Structure
- Prefers to avoid the use of the '_' character in names. Use the '-' character instead or a camel case.
- Prefers Unix like folder names like config/, etc., and tmp/ where it is reasonable.
- In an object-oriented language place the fields at the bottom of the class.

## Documentation for Software

- Avoid the '_' character in names; use '-' or camelCase instead.
- Prefers Unix-style directory names (e.g., config/, tmp/).
- In object-oriented classes, place fields at the bottom of the class rather than the top.

## Documentation for Software

- Believes that he code is the documentation
- Believes that the tests are the functional specification.
- Prefers no comments in code that are not absolutely necessary.

## Documentation and Comments

- The code is the documentation; the tests are the functional specification.
- Avoid comments in code and shell scripts. If a comment feels necessary, fix the naming or structure instead so the intent is self-evident.

## Software Tools and Languages

- Primary languages: Java, Groovy, C, C++.
- Primary build tools: Gradle, Make.
- Primary IDE: Eclipse.

## Development Approach and Defaults for Software.

- Employs Test-Driven Development (TDD) and Domain-Driven Design (DDD).
- Use an in-memory map of default values when starting a software project.
- Avoid properties files, configuration files, registries, environment variables, and external setup until strictly necessary.

# Initial default values for Software

- Prefer  a map of default values when starting a software project. 
- Avoid properties files, registrys,  configuration files, environmeny variables and the like  when starting a sofyware project.

## Accepting Artifacts

- Artifacts provided to the human must be directly placeable into the user's code project or Git repo, or be downloadable (preferring direct placement in the project/repo).
- Any handoff artifact must include '[Hh]andoff' in the filename.