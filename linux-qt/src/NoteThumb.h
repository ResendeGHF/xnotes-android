// A note's first page rendered small, for home recents and the file manager.
#pragma once

#include <QPixmap>
#include <QString>

namespace xn {

class NoteThumb {
public:
    /* Page 0 of the note at [path] at [width] px wide, or a null pixmap. */
    static QPixmap render(const QString& path, int width);
};

}  // namespace xn
