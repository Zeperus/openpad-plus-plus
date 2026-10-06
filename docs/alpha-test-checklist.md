# Alpha test checklist (0.1.0-alpha.1)

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
