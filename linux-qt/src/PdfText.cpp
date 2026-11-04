#include "PdfText.h"

#include <QChar>
#include <QPdfDocument>
#include <QPdfSelection>
#include <QPolygonF>
#include <QRectF>
#include <QString>

namespace xn::pdftext {

namespace {

void add(Page& out, uint32_t cp, const QRectF& box) {
    out.codepoints.append(cp);
    out.boxes << float(box.left()) << float(box.top()) << float(box.right()) << float(box.bottom());
}

/* The characters of text[from, to), a word with no space in it, each a code point (a surrogate pair is one). */
void addWord(Page& out, QPdfDocument* doc, int page, const QString& text, int from, int to) {
    QVector<uint32_t> cps;
    for (int k = from; k < to; ++k) {
        const QChar c = text.at(k);
        if (c.isHighSurrogate() && k + 1 < to && text.at(k + 1).isLowSurrogate()) {
            cps.append(QChar::surrogateToUcs4(c, text.at(k + 1)));
            ++k;
        } else {
            cps.append(c.unicode());
        }
    }
    const int n = cps.size();
    // QtPdf gives the word's box as one polygon per line it runs over. The characters are shared
    // out over those lines in turn, each taking an equal share of its line's width: close to the
    // glyphs for ordinary text, which is what selecting and marking need.
    const QList<QPolygonF> lines = doc->getSelectionAtIndex(page, from, to - from).bounds();
    if (lines.isEmpty()) {
        for (uint32_t cp : cps) add(out, cp, QRectF());
        return;
    }
    const int lineCount = lines.size();
    for (int line = 0; line < lineCount; ++line) {
        const int a = line * n / lineCount;
        const int b = (line + 1) * n / lineCount;
        if (a == b) continue;
        const QRectF rect = lines[line].boundingRect();
        const double w = rect.width() / (b - a);
        for (int k = a; k < b; ++k) {
            const double left = rect.left() + (k - a) * w;
            add(out, cps[k], QRectF(QPointF(left, rect.top()), QPointF(left + w, rect.bottom())));
        }
    }
}

}  // namespace

Page read(QPdfDocument* doc, int page) {
    Page out;
    const QString text = doc->getAllText(page).text();
    const int n = text.size();
    int i = 0;
    while (i < n) {
        if (text.at(i).isSpace()) {
            // A CR LF pair is one line break.
            if (text.at(i) == QLatin1Char('\r') && i + 1 < n && text.at(i + 1) == QLatin1Char('\n')) {
                ++i;
                continue;
            }
            const bool breaks = text.at(i) == QLatin1Char('\n') || text.at(i) == QLatin1Char('\r');
            add(out, breaks ? uint32_t('\n') : uint32_t(' '), QRectF());
            ++i;
            continue;
        }
        int j = i;
        while (j < n && !text.at(j).isSpace()) ++j;
        addWord(out, doc, page, text, i, j);
        i = j;
    }
    return out;
}

}  // namespace xn::pdftext
