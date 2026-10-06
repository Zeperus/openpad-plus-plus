# Roadmap

| # | Milestone | Status |
|---|-----------|--------|
| 0 | Environment | done |
| 1 | Repository/bootstrap (Compose app, CI, docs) | done |
| 2 | Storage vertical slice (internal `.md` notes, autosave, trash, rename) | done |
| 3 | Application shell (Favorites, Recent, Files, Trash sections) | done |
| 4 | Session / open documents / startup modes | done |
| 5 | External Markdown (file picker, Open with, Share, in-place editing) | done |
| 6 | Markdown engine (parser, document model, verified serializer, property tests) | done |
| 7 | Rich/WYSIWYG editor, formatting bar, undo, per-tab state | done (Alpha `0.1.0-alpha.1`) |
| 7a | Alpha 2: focus/keyboard stability, list editing, Smart Checklist (completed items sink) | done (`0.1.0-alpha.2`) |
| 7b | Alpha 3: selection across rows, clipboard, tables/images/HTML, search, folders, German, wide screens, remembered caret/undo | done (`0.1.0-alpha.3`) |
| 7c | Alpha 4: native selection across blocks (one text field per run of rows), external files writable (Open File asks for write access; read-only explained), language selector | done (`0.1.0-alpha.4`) |
| 7d | Alpha 5: compact editor spacing, caret after Enter in lists, title rename from the top bar | done (`0.1.0-alpha.5`) |
| 7e | Alpha 6: caret hit testing in the whole row | done (`0.1.0-alpha.6`) |
| 8 | Checklist polish (global default, drag reorder), table cell editing | planned |
| 9 | Trash and safety polish | planned |
| 10 | Polish (themes, accessibility, tablet layout, tables/images) | planned |
| 11 | First release candidate | planned |

Out of scope on purpose: cloud sync, accounts, analytics, plugin systems, workspaces/folders (for now).
