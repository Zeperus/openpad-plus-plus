# Alpha test checklist (0.1.0-alpha.2)

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
