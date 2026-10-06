# Editor

openPad++ edits Markdown as *formatted text*: the user never sees `#`, `**` or `- [ ]` in normal editing, and the file on
disk stays plain Markdown. This document covers the **Markdown engine** (Milestone 6); the rich editor built on top of it
is described further down once it exists.

```
Markdown file -> MarkdownParser -> ParsedDocument (OpenPadDocument + original layout)
                                        |
                                  editor operations
                                        v
Markdown file <- MarkdownSerializer <- OpenPadDocument
```

## Library choice: commonmark-java 0.30

Evaluated (data from Maven Central / GitHub on 2026-10-06):

| Library | Status | Verdict |
|---------|--------|---------|
| **commonmark-java 0.30.0** | released 2026-08-06, BSD-2, 2.7k stars, reference-style CommonMark implementation, ~220 KB core + 12-24 KB per extension | **chosen** |
| JetBrains `markdown` 0.7.16 | released 2026-09-29, Apache-2.0, Kotlin, IDE-oriented AST | runner-up; its tree is shaped for IntelliJ, commonmark-java's source spans fit our needs better |
| Flexmark | last push 2025-04, heavy | rejected: inactive, large |
| Markwon | last release 2021 | rejected: abandoned (and a renderer, not an engine) |

Why commonmark-java: standards-compliant CommonMark, the GFM extensions we need (`~~strikethrough~~`, task lists,
tables), **source spans** (we can map every top-level block back to its exact lines), built-in nesting limits against
hostile input, no Android-specific dependencies. It is compiled for Java 11; the only post-API-28 calls
(`List.of`, `Set.of`, `Map.of`) are rewritten by D8 into synthetic backports (verified in the dex, see below), so it runs on
Android 9 (minSdk 28). Our own model and serializer are what the app depends on; the library is only used inside
`MarkdownParser`.

Verification of the Android compatibility claim: all `java.*` references in the four commonmark jars were checked against
the SDK's `api-versions.xml` (12 references need API 30: the collection factories) and the built APK's dex was inspected:
no direct call remains, D8 generated `ExternalSyntheticBackport` helper classes instead.

## Document model (`markdown/Model.kt`)

Pure immutable Kotlin values, independent of Compose, Android and the parser library; structural equality is semantic
equality.

- `Block`: `Paragraph`, `Heading(1..6)`, `ListBlock` (bullet / ordered; items with optional `checked` = task item; `tight`),
  `Quote`, `CodeBlock` (fenced / indented, info string), `Rule`, `Raw`.
- `Inline`: `Text`, `SoftBreak`, `HardBreak`, `Emphasis` (italic), `Strong` (bold), `Strikethrough`, `Code`, `Link`, `Raw`.
- A **task list** is a `ListBlock` whose items all have `checked != null`.
- Stable per-block IDs for the UI are assigned by the editor layer (they are UI state, not document content).

## Lossless strategy

1. **Unknown constructs are kept, not dropped.** HTML blocks, tables, link reference definitions, footnote syntax... become
   `Block.Raw` (their exact text); images and inline HTML become `Inline.Raw`. They are written back verbatim.
2. **Per-block layout.** `ParsedDocument` remembers, for every top-level block, its exact original text and the whitespace
   after it; `leading + sum(source + gapAfter)` is always exactly the input. `serializeIncremental` writes a block that
   is still equal to what was parsed *byte for byte* and regenerates only what changed. So editing one paragraph never
   reformats the rest of the file (odd bullet markers, `__bold__`, setext headings, blank-line habits, CRLF endings all stay).
   The result is checked in tests: an unchanged document is returned identical for every corpus document and 40,000 random ones.
3. **Verified regeneration.** Every block that has to be regenerated is parsed again and compared with the model. If a
   CommonMark delimiter rule makes the first spelling read back differently, other spellings are tried (`_` emphasis,
   then HTML tags). Spans that CommonMark cannot express in a position (`a**!**` is not bold) are written as
   `<strong>`/`<em>`/`<del>`/`<a href>` for exactly that span; the parser reads those tags back as the styles they mean.
4. **Never crashes.** `MarkdownParser.parse` catches library failures and stack overflows; the whole file then becomes one
   raw block. Nesting is limited to 24 levels (deeper structure degrades to text, the content is kept).

## Normalization rules (what regeneration may change)

Only blocks that were *edited* are regenerated. For those: bullets keep their marker (adjacent lists get different markers
so they stay separate), numbered lists are numbered consecutively from their start number, `*italic*`/`**bold**`/`~~strike~~`,
ATX headings, fenced code (indented code stays indented where legal), `---` for rules, one blank line between blocks, a
backslash hard break, links as `[text](url "title")`, `<...>` around destinations with spaces. Whitespace at the edges of
bold/italic/strike moves outside the markers; spaces at line starts/ends are dropped; empty paragraphs and empty
formatting disappear; text is escaped so it can never turn into markup (also at the start of a line: `- x`, `1. x`, `# x`,
`> x`, `---`, fences, `[ ]` ...). Tight/loose is preserved when written, but is *not* part of the compared meaning: CommonMark
reports it inconsistently for deeply nested lists with empty items, and it only changes blank lines.
Text that begins like a task marker (`[ ] `/`[x] `) at the start of a list item *is* a task item (that is how the GFM
extension reads the decoded text).

## Tests

130 tests in `app/src/test/.../markdown`: parser, serializer, normalizer, a 100+ document round-trip corpus (German,
emoji, CRLF, lone CR, tabs, tables, HTML, unterminated fences, ...), **property tests** (4000 random documents built from
every construct with an adversarial alphabet; plus 2000 stability, 6000 text-survival, 3000 formatting, 500 incremental
checks), malformed-input/fuzz tests (random bytes, markup soup, 5000-deep nesting, lone surrogates), and performance
tests (a 6000-word note parses in tens of milliseconds; parsing/serializing scale linearly). The property tests were
developed with a shrinker that reduces failing random documents to minimal cases; it found, among others, the emoji
flanking rule (flanking is defined on code points, not UTF-16 units), the task-lookalike rule and list-interruption rules.
