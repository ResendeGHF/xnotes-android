#include "NoteThumb.h"

#include "CoreHost.h"

#include <QFile>
#include <QImage>
#include <QPainter>
#include <QTemporaryDir>

namespace xn {

QPixmap NoteThumb::render(const QString& path, int width) {
    if (width <= 0) return {};
    QTemporaryDir workDir;
    if (!workDir.isValid()) return {};
    CoreHost host;  // synchronous: thumbnails render inline
    char* error = nullptr;
    xn_note* note = xn_note_open(QFile::encodeName(path).constData(), QFile::encodeName(workDir.path()).constData(), host.host(), &error);
    if (!note) {
        if (error) xn_free_string(error);
        return {};
    }
    QPixmap out;
    if (xn_note_page_count(note) > 0) {
        double box[4] = {};
        if (xn_note_page_box(note, 0, box) && box[2] > 0 && box[3] > 0) {
            const int w = width;
            const int h = int(width * box[3] / box[2]);
            QImage image(QSize(w, h), QImage::Format_ARGB32_Premultiplied);
            image.fill(Qt::white);
            {
                QPainter p(&image);
                p.setRenderHints(QPainter::Antialiasing | QPainter::SmoothPixmapTransform | QPainter::TextAntialiasing);
                const double scale = double(w) / box[2];
                p.scale(scale, scale);
                p.translate(-box[0], -box[1]);
                PainterTarget target(&p);
                xn_note_paint_page(note, 0, &target, host.host(), 0xFFFFFFFFu, scale);
            }
            out = QPixmap::fromImage(image);
        }
    }
    xn_note_close(note);
    return out;
}

}  // namespace xn
