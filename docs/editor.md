# Editor

Target design (Milestone 5): Markdown file → parser → document model → Compose editor → document model →
serializer → Markdown file. The model (blocks: paragraph, heading, lists, checklist, quote, code block, rule;
inlines: text, bold, italic, strikethrough, code, link) is independent of Compose. Unknown Markdown must be
preserved, not dropped; round-trip tests guard this.

Until then the editor is a temporary plain text field showing raw Markdown.
