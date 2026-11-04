// A PDF page's text for the canvas: its characters in reading order, each with its box, read
// through QtPdf for the xn_host pdf_text service.
#pragma once

#include <QVector>

#include <cstdint>

class QPdfDocument;

namespace xn::pdftext {

struct Page {
    QVector<uint32_t> codepoints;  /* one per character; a space or line break where the page has one */
    QVector<float> boxes;          /* left, top, right, bottom per character, in points, top-left origin */
};

/* The page read on the calling thread: one QtPdf query for its text, then one per word for its boxes. */
Page read(QPdfDocument* doc, int page);

}  // namespace xn::pdftext
