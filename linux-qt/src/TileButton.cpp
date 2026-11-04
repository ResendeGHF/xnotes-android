#include "TileButton.h"

#include "Icons.h"

#include <QBoxLayout>
#include <QLabel>
#include <QMouseEvent>

namespace xn {

TileButton::TileButton(const QPixmap& thumb, const QString& iconName, const QString& name, const QString& sub, const QString& id,
                       QWidget* parent)
    : QFrame(parent), id_(id) {
    setFrameShape(QFrame::StyledPanel);
    setCursor(Qt::PointingHandCursor);
    auto* layout = new QVBoxLayout(this);
    layout->setContentsMargins(6, 6, 6, 6);
    layout->setSpacing(2);
    auto* image = new QLabel(this);
    image->setAlignment(Qt::AlignCenter);
    image->setMinimumHeight(96);
    if (!thumb.isNull()) {
        image->setPixmap(thumb.scaledToWidth(140, Qt::SmoothTransformation));
    } else {
        image->setPixmap(xnIcon(iconName).pixmap(48, 48));
    }
    layout->addWidget(image);
    auto* title = new QLabel(name, this);
    title->setAlignment(Qt::AlignCenter);
    title->setWordWrap(true);
    title->setMaximumHeight(34);
    QFont f = title->font();
    f.setBold(true);
    title->setFont(f);
    layout->addWidget(title);
    auto* when = new QLabel(sub, this);
    when->setAlignment(Qt::AlignCenter);
    QFont small = when->font();
    small.setPointSize(small.pointSize() - 1);
    when->setFont(small);
    layout->addWidget(when);
    setMinimumWidth(150);
    setMaximumWidth(170);
}

void TileButton::mousePressEvent(QMouseEvent*) { emit opened(id_); }

}  // namespace xn

