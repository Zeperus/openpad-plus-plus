# Editor

openPad++ edits Markdown as *formatted text*: the user never sees `#`, `**` or `- [ ]` in normal editing, and the file on
disk stays plain Markdown. This document covers the **Markdown engine** (Milestone 6) and the **rich editor** (Milestone 7)
built on top of it.

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

# The rich editor (Milestone 7)

```
keyboard / formatting bar
        -> RichEditor (Compose, one text field per row)
        -> NotesViewModel.onRowText / toggleStyle / ...
        -> EditorSession  -> EditorOps (pure) -> EditorDocument (rows)
        -> EditorDocument.toMarkdown()  (MarkdownSerializer.serializeIncremental)
        -> NoteEditor.onTextChanged -> Autosaver -> .md file
```

The formatted view *is* the editor. There is no source view and no preview: `# `, `**`, `- [ ]` exist only in the file.

## Rows (`editor/EditorDocument.kt`)

The document is a flat, immutable list of `EditorRow`s: paragraph, heading (1-6), quote line, list item (with depth and a
`ListInfo` per list, `checked` = null/true/false), code block, rule, and **raw** (Markdown the editor does not format).
Rows have stable ids, so UI state (focus, caret) survives edits. `RichText` is the text of a row with its formatting as
spans (bold, italic, strike, code, link, hard break). Every edit makes a new document that shares the untouched rows.

Mapping to the engine's blocks (`toBlocks`): consecutive list rows of one list form a `ListBlock` (depth = nesting),
consecutive quote rows a `Quote`. A row remembers the parsed block it came from (`Origin`) and whether it was `touched`.
`toMarkdown` writes **untouched blocks byte for byte** from the original text, regenerates only changed blocks and keeps
the gaps between blocks where it can; a document that was never edited is returned as the exact original text. So opening
a note and leaving it never rewrites the file, and changing one paragraph does not reformat the rest.

What the editor cannot represent (tables, HTML blocks, a list item with several paragraphs or code inside, quotes with
lists, link reference definitions, ...) is a **raw row**: shown in monospace in a tinted box ("Markdown kept as written"),
editable as text, never converted. Backspace at the start of a raw row does nothing unless it is empty (then it is removed).

## Operations (`editor/EditorOps.kt`) and behaviour

- Typing: the field reports new text; the operation finds what changed, keeps the formatting of surrounding text and
  continues bold/italic/strike at the caret (code and links are left by simply typing on). Toggling a style with an empty
  selection applies to the next typed characters.
- **Enter** splits the row. In a list it creates the next item (a task item gives an unchecked task); on an empty item it
  leaves the list one level up / out of it; in a heading the new row is a paragraph; in a code block it inserts a line break.
- **Backspace at the start**: list item -> outdent or paragraph; heading/quote/code -> paragraph; paragraph -> joined with
  the previous row (never into code/raw; deletes a rule in front of it).
- Paragraph style (Text, Heading 1-4, Quote, Code block), bullet / numbered / checklist toggles (converting rows in place),
  indent / outdent (lists nest at most one level below the item above), horizontal rule, links (set / change / remove).
- **Pasted text is plain text**: Markdown in pasted text is not interpreted (it is escaped on write so it reads back as the
  same text). A blank line in pasted text starts a new paragraph. Only *opening a file* parses Markdown.
- Checkboxes are real checkboxes; ticking one changes only that item (no reordering). Checked items are drawn dimmed and
  struck through (display only).

## Undo / redo

`EditHistory` keeps at most 100 snapshots per document (rows are shared, so a step costs one list of references). A burst of
typing in one row within one second is one step. Undo/redo cover typing, formatting, paragraph style, list conversion and
checkbox toggles. Every open tab has its own `EditorSession` (document, caret/selection, history), kept while the tab is open.
A tab's session is reused only if its Markdown equals the file just loaded; if the file changed meanwhile, a fresh session is
built from disk (so undo can never write text over a newer file). Closing a tab drops its history. Caret and history are not
persisted across app restarts.

## Compose layer (`ui/editor/`)

- `RichEditor`: a `LazyColumn` with one `BasicTextField` per row. The field holds *plain* text; `SpanTransformation` (a
  `VisualTransformation` with the identity offset mapping) draws bold/italic/strike/code/link on top, so the keyboard's
  composing text, selection, copy/cut/paste and emoji behave exactly as in any text field. Headings differ by typography,
  lists by marker/checkbox, quotes by a bar, code by monospace on a tinted background.
- **Backspace at the start**: soft keyboards report nothing when there is nothing to delete, so every field's text starts
  with an invisible zero-width character. Deleting it *is* "Backspace at the start"; the field never lets the caret in front
  of it. Hardware keys: Ctrl+B / Ctrl+I / Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y, Tab / Shift+Tab (indent/outdent).
- The view model publishes one immutable `EditorUi` (rows, caret, active styles, undo availability); operations that move the
  caret to another row set a `FocusRequest` the row consumes.
- `FormattingBar`: one thin, horizontally scrolling row above the keyboard (paragraph style, **B**, *I*, ~~S~~, code, link,
  bullets, numbers, checklist, indent/outdent, rule, undo, redo). It exists only while the open document can be changed and
  never takes focus from the text. Links: the dialog edits the address; opening is a separate, explicit "Open" button
  (http/https/mailto/tel only), so placing the caret never launches a browser.
- Read-only external documents render formatted with the read-only banner; there is no bar, fields are read-only (selection and
  copy work), checkboxes are disabled.

## Autosave and safety

Only document changes reach the autosave: caret moves and style toggles with an empty selection do not. Each change
serializes the document and hands the Markdown to `NoteEditor`; if the serializer or an operation throws, the previous
Markdown stays in the editor and on disk and the user sees a message. A blank page (one empty paragraph) is not a note: only
text that is not whitespace creates the file. Clear gives an empty document and an empty file; Delete/Rename/Favorite are
unchanged (metadata or Trash).

## Limitations of the Alpha

- Selection works inside one row; selecting across paragraphs, and drag-moving rows, are not supported. Copying yields plain
  text, not Markdown.
- Images, tables, HTML and similar content are kept as raw rows (shown as source), not rendered.
- A list item can hold text and nested lists only; richer items are raw.
- Whitespace between untouched and regenerated blocks can be normalized to one blank line next to an edited block.
- The caret and undo history of a tab live only while the app runs.
- Android's `.md` "Open with" filter for generic MIME types matches paths with up to six dots.

## Editor tests

`editor/EditorOpsTest`, `EditorDocumentTest`, `EditorPropertyTest` (random operation sequences on adversarial documents: after
every operation the model written and read back must mean exactly what the editor holds; undoing everything restores the
original file byte for byte; 1500 sessions per run, 6000 checked during development) and `NotesViewModelEditorTest`; on a
device `RichEditorTest` (formatting shown, typing persists, formatting bar, lists, checkboxes, tab switching with undo,
blank page, external Markdown).
