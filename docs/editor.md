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
        -> RichEditor (Compose, one text field per run of text rows; SegmentField)
        -> NotesViewModel.applyFieldOp / toggleStyle / ...
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
- **Enter** splits the row *without replacing the row that has the focus*: the focused row keeps its id and becomes the second
  half, a new row for the first half appears above it, so the text field being typed in stays the same field. In a list it creates the next item (a task item gives an unchecked task); on an empty item it
  leaves the list one level up / out of it; in a heading the new row is a paragraph; in a code block it inserts a line break.
- **Backspace at the start**: list item -> outdent or paragraph; heading/quote/code -> paragraph; paragraph -> joined with
  the previous row (never into code/raw; deletes a rule in front of it). When rows are joined, the *focused* row survives: it
  takes the place and kind of the row above, and that row disappears.
- Paragraph style (Text, Heading 1-4, Quote, Code block), bullet / numbered / checklist toggles (converting rows in place),
  indent / outdent (lists nest at most one level below the item above), horizontal rule, links (set / change / remove).
- **Pasted text is plain text**: Markdown in pasted text is not interpreted (it is escaped on write so it reads back as the
  same text). A blank line in pasted text starts a new paragraph. Only *opening a file* parses Markdown.
- Checkboxes are real checkboxes; ticking one changes only that item (no reordering). Checked items are drawn dimmed and
  struck through (display only).

## Identity and focus (why the keyboard used to flicker)

**Root cause (Alpha 1).** Row ids were already stable across paragraph <-> list conversions, but the Compose layer drew each kind of
row in its own `when` branch (a bare `Box` for a paragraph, a `Row` with a marker slot for a list item, ...). Compose identifies
composables by their position in the code, so when the kind of a row changed, the paragraph's text field was *disposed* and a
list item's text field *created*: the focused field disappeared (keyboard closes, the IME connection and composition state are
lost, the caret and key repeat stop) and the new one grabbed the focus a moment later (keyboard reopens). Joining rows with
Backspace had the same effect, because the focused row was the one that was deleted.

**Fix.** (1) Every row kind is drawn by one composable structure - a `Row` with a leading slot (bullet / number / checkbox / empty)
and the text field - so only modifiers, the slot content and the text style depend on the kind; a conversion changes the row
instead of replacing it. (2) The document operations keep the focused row's id: Enter makes the focused row the second half,
Backspace-join makes the focused row the survivor, leaving a list changes the kind in place. (3) `LazyColumn` keys are the row ids
(never indexes or text), so a row that moves - a ticked task in a smart checklist - is *moved* with its field, focus and caret.
(4) No `requestFocus()` is used to paper over it; the only focus requests are for a caret that really moves to *another* row
(e.g. the new empty item after Enter in a list). `EditorDiagnostics` counts created/disposed fields; the instrumented
`StructuralEditingTest` asserts that none is disposed by a conversion, that the focused field keeps its focus, and that a
simulated key repeat of Backspace over a list boundary stays in one field.

**Alpha 4.** The row-per-field structure above kept the focus stable but could not select across rows (see below), so consecutive
text rows now share one field. The rule is unchanged and stricter: *no field is created or disposed by an edit* - rows become lines
of the same field, and the key of a field (`SegmentKeys`) follows the overlap of row ids with the previous layout, so Enter, joins,
conversions and a ticked task move text inside the field instead of replacing it. `StructuralEditingTest` (15, unchanged in intent)
passes on the new structure.

## Smart checklist

A per-note mode (a flag in `index.json`, shown in the overflow menu as "Smart checklist: on/off"; never written into the
Markdown; off by default and for every external file). Ordinary Markdown task lists are never reordered.

- Checking an item moves it (with its nested rows) to the **bottom** of its sibling group; unchecking moves it to the **end of the
  unchecked group**, directly above the first completed item. Order inside each group is untouched, so completed items stay in
  the order they were completed. No earlier position is remembered.
- The check and the move are **one** undo step. Checked items are drawn struck through and dimmed (presentation only; the file
  says `- [x] Bread`, not `~~Bread~~`).
- Switching the mode on sorts the existing checklists once (stable: unchecked first) as one undoable step. After that the rule is
  applied when you check, uncheck or press Enter on a completed item (the new item goes above the completed ones). Items you add
  or convert in other ways stay where you put them.
- Conservative scope: only sibling groups where *every* item is a task item are reordered; a group that mixes plain and task
  items is left alone, and an item never leaves its parent or list. Nested checklists sort inside their parent.

## Undo / redo

`EditHistory` keeps at most 100 snapshots per document (rows are shared, so a step costs one list of references). A burst of
typing in one row within one second is one step. Undo/redo cover typing, formatting, paragraph style, list conversion and
checkbox toggles. Every open tab has its own `EditorSession` (document, caret/selection, history), kept while the tab is open.
A tab's session is reused only if its Markdown equals the file just loaded; if the file changed meanwhile, a fresh session is
built from disk (so undo can never write text over a newer file). Closing a tab drops its history. Caret and history are not
persisted across app restarts.

## Compose layer (`ui/editor/`)

- `RichEditor`: a `LazyColumn` of *segments* and *blocks*. A segment is a maximal run of consecutive text rows (paragraph, heading, quote,
  list item, code, a raw row shown as source) and is **one** `BasicTextField(state = TextFieldState, ...)` whose text is the rows joined by
  `"\n"`. A rule, table, image or simple-HTML row is a separate block item and splits the note into several segments. Segment logic is pure
  Kotlin (`editor/Segments.kt`, `SegmentEditing.kt`, `Numbering.kt`); the Compose side is `SegmentField.kt`.
- The field holds *plain* text. Formatting (bold/italic/strike/code/link), headings, quote and code backgrounds and the checked-task
  strike-through are applied by an `OutputTransformation` (`addStyle`) and drawn behind the text; bullets, numbers, checkboxes and
  quote bars are overlays aligned to the line positions. Nothing of Markdown syntax is ever in the field.
- **User edits are interpreted before they are committed.** An `InputTransformation` compares the field before and after a keyboard edit,
  `SegmentEditing.interpret` turns it into a `SegmentOp` (typing, newline, Backspace at a line start, delete, replace a selection,
  paste, ...) and the view model applies it to the document model (`applyFieldOp`). The model stays the single truth; the field is
  then rewritten to what the model says with the *smallest* text edit (`replaceMinimal`), so the IME's composing region and the caret
  survive. Cross-row Cut/Delete/Replace are single model operations (one undo step).
- The caret is kept as a logical position (row id + offset, `cursorEpoch` marks model-driven moves), not as a field offset.
- **Backspace at the start**: soft keyboards report nothing when there is nothing to delete, so every field's text starts with an
  invisible zero-width character (`FIELD_PREFIX`). Deleting it *is* "Backspace at the start of the first row" (leave the list, join
  the block above); the field never lets the caret in front of it, and all offsets in the field are one more than in the segment. Later
  rows have their line break instead. Hardware keys: Ctrl+B / Ctrl+I / Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y, Tab / Shift+Tab.
- The view model publishes one immutable `EditorUi` (rows, caret, active styles, undo availability).
- `FormattingBar`: one thin, horizontally scrolling row above the keyboard (paragraph style, **B**, *I*, ~~S~~, code, link,
  bullets, numbers, checklist, indent/outdent, rule, undo, redo). It exists only while the open document can be changed and
  never takes focus from the text. Formatting acts on every row the selection touches. Links: the dialog edits the address; opening is
  a separate, explicit "Open" button (http/https/mailto/tel only), so placing the caret never launches a browser.
- Read-only external documents render formatted with a read-only banner (and the reason); there is no bar, fields are read-only
  (selection, copy and search work), checkboxes are disabled.

## Density: why rows used to be far apart, and what a row costs now (Alpha 5)

**Cause.** In Alpha 4 each row's paragraph style (indent, line height) covered the row's text *and the line break after it*. Compose's
layout adds an empty line after a paragraph whose text ends in a line break (probed on a real emulator: `a\nb\nc` with three paragraph
styles = 5 lines, without styles = 3), so every row was followed by a phantom empty line: "Line one", blank, "Line two". Rows were also
28 sp apart for 16 sp text and checkboxes sat in 26 dp boxes.

**Fix.** (1) The break between two rows is *drawn* as an invisible zero-width character (`SegmentOutput`, one character replaced by one
character, so every offset is unchanged); the paragraph styles end there and no line break is inside any paragraph. The field's own
text - what the keyboard, the clipboard and screen readers see - still has the line breaks. (2) An empty *last* row gets one drawn
invisible character too, so that its paragraph style (the indent that keeps the caret to the right of the marker) applies to it.
(3) Line heights: text, list items and quotes 24 sp (1.5 x), headings 34/30/26/24 sp, code 20 sp. (4) The marker column is 32 dp.

**Blank lines.** Markdown blank lines separate blocks; they are not rows and not spacing. Enter creates an adjacent row and no blank
paragraph (an empty row is not written, `EditorOpsTest`). Extra blank lines in a file are kept byte for byte while the block is untouched.

**Touch targets.** A task's checkbox is drawn 24 dp but its toggle target is 48 dp wide (reaching into the page margin) and one line tall.
48 dp *tall* would overlap the neighbouring rows' targets at this density, so the height is the line height (24 dp, more with a larger font).
The tap on the text of the row places the caret as before.

## Caret hit testing (Alpha 6)

**The bug.** Tapping *on* the glyphs of a row placed the caret correctly, but a tap in the empty space to the right of a row's text put the
caret at the start of the *next* row (it looked like "offset 0": the row got the focus, the caret was in front of the next text). Only the
last row behaved, because it has nothing after it.

**Cause.** Alpha 5 draws the break between two rows as an invisible character that *ends the row's paragraph* (see "Density"). Compose lays out
every paragraph as its own text layout, and a layout's last line has no trailing break to exclude: asked for the offset at "x beyond the end
of the line" it answers with the end of the line *including* that invisible character - which is the start of the next row. (With an
ordinary line break the layout excludes it, which is why Alpha 4 was fine; a wrapped row's earlier lines were always fine.)
Nothing was calling `requestFocus()` without a selection and nothing assigned `TextRange(0)`: the field fills the row's width and
Compose's own gesture handling does the hit test; it was the answer of the layout that was wrong.

**Fix** (`SegmentField.kt`, `Segment.clampTapToRowOfLine`). A tap belongs to the row whose *line* it is on. The field watches the touches
(Initial pass, never consuming) and remembers the last one; the first collapsed caret that appears within 400 ms of it is limited to the
end of the row that owns the line at the tap's height (`layout.getLineForVerticalPosition` -> `getLineStart` -> row). This happens in the
input transformation, before the caret is committed (no flicker, no second selection event); a second check in the selection flow covers a
caret that got past it. Taps inside the text, to the left of it and on wrapped lines are the layout's own answers and are not touched: right
of a wrapped line's *first* lines the caret stays on that line; right of a row's last line it is the end of that row; left of a line it is the
start of that line. The row that is already focused is no special case: every tap goes through the same path.
The page margin around the field (16 dp left/right, 4 dp above and below) is part of the row: a tap there puts the caret at the nearest
place of the row (x and y clamped into the text, same clamp) and focuses the field - the caret is set first, then the focus.

**Markers.** A checkbox's 48 dp target (marker column and the margin in front of it) toggles it and never places a caret. A bullet or
number is only drawn: a tap on it is a tap in the marker column of the field and places the caret at the start of the item's text.

## Autosave and safety

Only document changes reach the autosave: caret moves and style toggles with an empty selection do not. Each change
serializes the document and hands the Markdown to `NoteEditor`; if the serializer or an operation throws, the previous
Markdown stays in the editor and on disk and the user sees a message. A blank page (one empty paragraph) is not a note: only
text that is not whitespace creates the file. Clear gives an empty document and an empty file; Delete/Rename/Favorite are
unchanged (metadata or Trash).

## Selecting across rows, clipboard (Alpha 4)

**Why Alpha 3's selection was not native.** Android's text selection - the handles, the floating toolbar, long-press word selection - belongs
to *one* text field and cannot leave it. With one field per row the handles stopped at the edge of a paragraph, and Alpha 3 papered over
it with a separate "logical" selection (custom long-press-and-drag gesture, our own handles and a selection bar). On a real phone that
was rejected: it was not the native behaviour.

**Alpha 4 makes it the native behaviour by removing the cause**: consecutive text rows share one `BasicTextField(state = TextFieldState)`,
so the *system's own* long press, selection handles, magnifier, double-tap word selection and floating toolbar run across paragraphs,
headings, list items, checklist items and quotes, forward and backward. There is no custom gesture, handle or bar any more. A selection
cannot cross a rule, table, image or HTML block (they are separate items); it ends at them.

- **Model**: `editor/DocumentSelection.kt` (`DocumentPosition(rowId, offset)`, `DocumentSelection`) is still the pure model for
  slices, readable text, Markdown and cross-row delete/replace; the view model builds it from the field's selection
  (`onFieldSelection`, offsets mapped through `Segment.position`).
- **Toolbar**: the official text context menu API (`appendTextContextMenuComponents` / `filterTextContextMenuComponents`) replaces the
  system's Cut / Copy / Paste / Select all with ours - they know the invisible marker, the structure and the undo step - and adds
  **Copy as Markdown**. Everything else the system offers stays.
- **Copy** = readable plain text: list items get their bullet / number / box (•, `1.`, ☐ / ☑), blocks are separated by a
  blank line, items of one list by a line break; a part of one row is just that text. **Copy as Markdown** keeps the structure
  (`# Heading`, `- [ ] task`, `1.`, `>`) and cuts partial first/last rows into plain text pieces; it is also in the overflow menu (the
  selection, or the whole note if nothing is selected).
- **Cut** = Copy + delete as **one** undo step. Deleting joins the first and last row when both are ordinary text rows (the first row's
  kind and id are kept; if the selection started at the very start of the first row, the last row gives the kind); whole rows that were
  covered simply disappear; code/raw rows at the edges are never merged with text; the document keeps a paragraph to type into.
  Typing or pasting over a selection replaces it the same way (one step).
- **Paste** is plain text everywhere (Markdown in it is *not* interpreted); with a selection it replaces it. "Paste as Markdown"
  (overflow menu) is the explicit alternative: the clipboard is parsed and its blocks inserted at the caret row (or over the
  selection), as one undo step.
- Android drops a range selection when a field loses focus, so a remembered range comes back as a caret.

## Tables, images, HTML (Alpha 3)

These stay **raw rows** in the model (the Markdown is kept byte for byte and written back untouched); only the drawing is richer
(`editor/RawBlocks.kt`, `ui/editor/RawViews.kt`). Each rendered block has "Edit source", which opens that block as text in the same row
(with "Done" to go back); there is no pretend-editing of tables.

- **Tables** (GFM): header row, column alignment (`:--`, `:-:`, `--:`), inline Markdown in cells (bold, italic, code, links), unescaped-pipe
  splitting that respects `\|` and code spans. Drawn by a small layout (column width = widest cell, 72-260 dp) inside a horizontal
  scroll, so wide tables scroll on a phone. Light/dark follow the theme. Not a table (no valid delimiter row) -> shown as source.
- **Images**: a paragraph that is just one image is a raw row (it is still a paragraph in the file; nothing is rewritten). Shown only if
  the address is a `content://` URI the app may read (decoded at a bounded size, off the UI thread); everything else - remote URLs,
  relative paths, `file:`, unreadable or missing content - is a quiet placeholder with the alt text and, for web addresses, the
  host and an explicit "Open" button. **Nothing is downloaded** (the app has no INTERNET permission); a failure is just "no image".
  Images inside a sentence stay text (shown as source).
- **HTML**: no WebView, no scripts, no network, ever. Simple HTML made only of `p`, `br`, `strong`/`b`, `em`/`i`, `code`, `pre` (no
  attributes, balanced) is shown as formatted text; entities become text, never markup. Anything else (attributes, `script`, `style`, `a`,
  `img`, `iframe`, `div`, comments, unknown tags) is shown as source in a block labelled "HTML (shown as source, not rendered)".

## Smart checklist consistency (Alpha 3)

In a smart checklist the editor keeps unchecked items above completed ones *after every edit* (`EditorSession.settled`): a task that
appears among completed ones (Enter on a completed item, converting a row to a task, a paste, indent/outdent, a join) goes to the end
of the unchecked group, as part of the same undo step. A document that is already in order is returned unchanged, so nothing is
rewritten for nothing. A smart checklist note that is out of order when it is opened is put in order (no undo entry). Groups that mix
plain and task items are left alone; nested items move with their parent. **New checklist** (drawer) opens a blank page with one empty
task and creates the note with Smart Checklist on; it is an ordinary `.md` file.

## Find, search (Alpha 3)

- **Find in note** (overflow menu): case-insensitive over the *visible* text of the rows (no Markdown punctuation; blocks drawn as tables /
  images / simple HTML have no text to find), count "2 of 5", previous / next (wrapping), highlights and scroll to the current match;
  it follows edits.
- **Search notes** (magnifier in the top bar): titles first, then content, direct search over the files (no index, nothing leaves the
  device; the open note is searched with what is on screen; external documents are included when readable within 1.5 s). Case-insensitive
  for all letters (umlauts, accents), a snippet with the line around the first match (list/heading markers removed) and the number of
  matches. Opening a result shows the note with Find prefilled.

## Remembered caret and undo (Alpha 3)

`editor-state.json` (next to `session.json`, never inside a note) stores per *open* note: the caret/selection (row index + offsets) and a
bounded history - the Markdown of the document before each of the last 25 edits (redo steps too), at most 400 000 characters in total
(oldest dropped first); a note over 150 000 characters keeps only its caret. Everything is tied to a **fingerprint** (SHA-256 of the exact
Markdown): if the file changed in the meantime (edited elsewhere, restored from a backup) nothing is restored - no wrong caret, no
history that could write old text over new. It is written about a second after the last change and when the app goes to the background
(after the note itself). A damaged file, a bad entry or a stale position is ignored; closing a tab forgets its entry. Restoring builds the
steps from Markdown, so Undo after a restart reproduces the earlier text exactly.

## Whitespace preservation (Alpha 3)

Untouched blocks are written back byte for byte (as in Alpha 1). New: around a block that *was* rewritten, the blank lines of the original
are kept as they were (`serializeIncremental` follows the lineage of each block, not only the verbatim ones), so editing one paragraph no
longer turns the surrounding "two blank lines" into one. A lone line break between blocks is not carried over to rewritten text (the new
text might not stay separate from its neighbour without a blank line); CRLF files stay CRLF.

## Localization, wide screens, accessibility (Alpha 3)

- All user-visible text is in `res/values/strings.xml` with a natural German translation in `values-de`; `locales_config.xml` registers
  English and German so Android's per-app language settings list them (no in-app selector). `LocalizationTest` checks parity, placeholders
  and that no UI text is hard-coded.
- A window at least 840 dp wide (tablet, unfolded foldable) shows the navigation as a permanent sidebar next to the editor, the writing
  area keeps a comfortable width (max 920 dp); on a phone the slide-in drawer is unchanged.
- Formatting and selection buttons are at least 48 dp high and grow with font size; checkboxes announce "Task, done/not done: ..." and
  their state; completed tasks are struck through *and* have a checked box (not colour alone); tabs announce "selected"; folders announce
  open/closed.

## Limitations (Alpha 4)

- Inline images (inside a sentence), tables with inline HTML cells, nested block content inside list items and reference-style images are
  still shown as text/source; tables cannot be edited cell by cell (edit source instead).
- A selection cannot continue across a rule, table, image or HTML block (those are separate blocks).
- Pasting from another app into a selection that spans rows is plain text (no rich paste).
- Copy puts plain text (or Markdown on request) on the clipboard, not rich text.
- External files: relative image paths cannot be resolved (no access to sibling files), so such images are placeholders.
- Search reads the notes directly (fine for hundreds of notes); there is no index.
- Android's `.md` "Open with" filter for generic MIME types matches paths with up to six dots.

## Editor tests

`editor/EditorOpsTest`, `EditorDocumentTest`, `StructuralEditingTest` (row identity, Enter/Backspace on lists, smart checklist), `EditorPropertyTest` (random operation sequences on adversarial documents: after
every operation the model written and read back must mean exactly what the editor holds; undoing everything restores the
original file byte for byte; 1500 sessions per run, 6000 checked during development) and `NotesViewModelEditorTest`; on a
device `SelectionTest` (real touch: long press and a native handle dragged across paragraphs, into a list, backwards, copy as Markdown, cut + undo, the toolbar), `Alpha3Test` (new checklist, tables, images, remembered caret/undo, search, folders, German, wide screens), `StructuralEditingTest` (focus and field identity, ghost rows, smart checklist) and `RichEditorTest` (formatting shown, typing persists, formatting bar, lists, checkboxes, tab switching with undo,
blank page, external Markdown).
