# Alpha test checklist (0.1.0-alpha.3)

Short on purpose. Tick what works, note what does not (what you did, what you expected). **Keep backups of important
notes while testing.** Settings shows the installed version at the bottom.

## Startup
- [ ] App launches
- [ ] "Resume + blank note" is the default (Settings); a blank "New note" tab is there after a restart
- [ ] No duplicate blank tabs after several restarts

## Writing and saving
- [ ] Type a note, wait a second, swipe the app away (kill it), reopen: the text is still there
- [ ] Typing, selecting, copy / cut / paste, emoji and umlauts behave normally
- [ ] An untouched "New note" never shows up in FILES

## Formatting (nothing should show `#`, `**`, `- [ ]`)
- [ ] Heading: paragraph style (Aa) -> Heading 1/2/3
- [ ] Bold, italic, strikethrough on a selection (and switching them on before typing)
- [ ] Bullet list, numbered list; Enter continues, Enter on an empty item leaves the list; Backspace at the start of an item
- [ ] Checklist: tap a checkbox, it ticks and nothing moves
- [ ] Inline code, code block, quote, horizontal rule
- [ ] Link: select text, Link, enter an address; "Open" in the dialog opens it
- [ ] Undo / redo (arrows in the bar)
- [ ] Paste text that contains `**x**` or `# y`: it stays as plain text

## Keyboard / lists (Alpha 2)
- [ ] Hold Backspace while leaving a bullet list
- [ ] Hold Backspace while leaving a numbered list
- [ ] Hold Backspace while leaving a checklist
- [ ] The keyboard never visibly closes/reopens
- [ ] The cursor does not jump to another row
- [ ] Enter on an empty list item leaves the list cleanly
- [ ] No extra ghost list row remains
- [ ] Switching a line between paragraph / bullet / numbered / checklist (bar) keeps the keyboard and the caret

## Smart checklist (Alpha 2)
- [ ] Menu: "Smart checklist: off (turn on)" on a note with a checklist
- [ ] Check a middle item: it moves below all unchecked items and is struck through
- [ ] Check several items: they keep the order in which you completed them
- [ ] Uncheck one: it returns to the end of the unchecked group
- [ ] Undo restores checkbox and previous order in one action
- [ ] Tapping a checkbox does not close the keyboard or move the text cursor
- [ ] A normal Markdown checklist (mode off) does not auto-sort

## Alpha 3

### Selection
- [ ] Long press in a paragraph and drag into another paragraph: the text between is highlighted, handles appear
- [ ] Select across a paragraph and a list, and across a checklist
- [ ] Copy, paste somewhere else: readable text (bullets/boxes shown)
- [ ] Copy as Markdown (selection bar, or menu): the Markdown structure
- [ ] Cut, then Undo: everything comes back in one step
- [ ] Menu: Select all; Paste as Markdown with Markdown text on the clipboard

### Keyboard (must still be perfect)
- [ ] Held Backspace still works across list boundaries; no keyboard flicker; lists still exit correctly

### Checklist
- [ ] Drawer: + New Checklist; Smart mode is already on; add tasks with Enter
- [ ] Check the middle item: it moves below the unchecked ones; uncheck it: it returns to the end of the unchecked group
- [ ] Close and reopen the app: the order is still right; a normal Markdown checklist (mode off) does not sort

### Persistence
- [ ] Place the cursor, swipe the app away, reopen: the cursor comes back
- [ ] Type something, swipe the app away, reopen, Undo: the typing is undone
- [ ] Edit the same note in another app/on a PC, reopen: no stale cursor or undo
- [ ] Tabs restore

### Markdown
- [ ] A GFM table is drawn as a table; wide tables scroll sideways; "Edit source" works and Done returns
- [ ] An image from a content:// address shows; a web image shows a placeholder (the app works without Internet)
- [ ] HTML: simple `<p><strong>` is formatted; `<script>`/`<div class=...>` is shown as source and nothing runs
- [ ] A file with 2 blank lines between blocks: edit one paragraph, the blank lines stay

### Search and folders
- [ ] Search icon: find by title and by content (also with ä/ö/ü); opening a result shows the matches
- [ ] Menu: Find in note: count, next/previous
- [ ] Menu: Move to folder... New folder; the drawer shows the folder; restart; the note is still there
- [ ] Long press a folder: Rename, Delete (only when empty)

### German and large screens
- [ ] Phone language German (or Settings -> Apps -> openPad++ -> Language): the UI is German
- [ ] Tablet/unfolded: sidebar next to the editor, nothing overlaps

### Update
- [ ] Install Alpha 3 directly over Alpha 2: notes, favorites, Smart Checklist settings, session remain

## Tabs
- [ ] Open several notes, switch, order stays
- [ ] Close, Close others, Close all (long press a tab); closing never deletes a note
- [ ] Switch away and back: caret and undo are still there

## Files
- [ ] Rename, Favorite (FAVORITES section), RECENT, Delete -> TRASH -> Restore
- [ ] Clear note (asks first) leaves an empty note

## External files and sharing
- [ ] Open file... (picker) a `.md` from your phone; edit it; open the original in another app: changes are there
- [ ] "Open with" openPad++ from a file manager
- [ ] Share a note: the receiving app gets a `.md` file
- [ ] A read-only file shows the banner and cannot be changed

## Bonus
- [ ] Open a `.md` file you wrote elsewhere (tables, HTML, odd spacing): it must not be damaged; look at the file afterwards
