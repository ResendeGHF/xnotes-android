# Text to handwriting (text → strokes)

Like the referenced
[Text-To-Handwritten-Notes](https://github.com/YuvrajSingh-16/Text-To-Handwritten-Notes)
project — typed text set in a handwriting font — but producing editable vectors
instead of a flat PDF: every line becomes a filled glyph shape in the Hand face,
equations become editable formula boxes, tables become editable tables.

## Pipeline

1. **Blocks** (`handwrite/HandwriteBlocks`, pure Kotlin)
   - Typed/selected/PDF text: `plainBlocks` (one paragraph per line).
   - `.md`: headings, bullets, GFM pipe tables, fences, `$`/`$$` math.
   - `.tex`: sections, itemize/enumerate, equation environments, `tabular`,
     inline and display math. Preamble and comments are dropped.
   - Whole documents convert: the cap is 100k chars and every block flows.
2. **Rows** (`handwrite/HandwriteLayout`, needs the platform text/math backends)
   - Words → one filled `ShapeKind.GLYPHS` shape per visual line (the same seam
     shape labels use; solid glyphs stay exactly the letters at any zoom).
   - Math → a centred editable math box (falls back to words when the source
     will not set, so nothing is silently dropped).
   - Tables → a `TableItem` with content-proportioned columns and rows tall
     enough for one-line cells (tables never wrap).
3. **Flow + place** (`canvas/InteractionController`, additive only)
   - `insertPdfPages` converts a whole document in order: the first entry
     starts centred on the viewport page, every later entry starts on a fresh
     page; lines running past the paper continue onto appended blank pages.
     `replaceTextWithHandwritingBlocks` swaps a text box in place.
   - The whole import — page adds, item adds, box removal — is a single
     undoable `CompositeCommand`.
4. **Scans** (`handwrite/HandwriteTrace` + the existing `scan/InkVectorizer`)
   - PDF pages with no text layer are rendered and traced to pen strokes on
     their own fresh pages, so image-only PDFs still convert in full (capped at
     100 pages per import).

File reads, parsing and tracing run off the main thread; failures and skip
lists report through the normal toast path.

## Entry points

- **Insert → Text to handwriting** (`ui/TextToStrokesDialog`, hosted by
  `NoteInsertMenu`): type text, pull in the selected text box, import
  `.md`/`.tex`/`.txt` (structure kept while the field is untouched; editing
  falls back to plain words), or convert a whole PDF. Hand-size slider
  (12–32 pt, defaults to at least 18 pt), optional *replace the selected box*.

## Editing the result

Everything placed is a first-class item, selectable one by one:

- glyph lines: move, resize, delete, and recolour (fill follows the outline
  colour through Change-style, or set directly through the fill popup);
- math boxes: Edit reopens the LaTeX source; colour follows the text style;
- tables: the cell editor, Data-lab export and cell readout all work;
- scanned strokes: full pen restyle/erase like any ink.

## Deliberately untouched

`Editor.kt`, `TraceScanScreen.kt`, the sdocx/math/pencil modules and
`strings.xml` are being worked on by others: the dialog hangs off the public
controller API, its strings live in `strings_handwrite.xml`.

## Follow-ups (not in v1)

- Infinite-canvas hosting (`InfiniteEditor` has its own interaction model).
- A selection-menu shortcut next to *Convert to text* (needs a host-interface
  addition once `Editor.kt` is free).
- User-imported handwriting fonts (today the Hand face resolves to cursive).
