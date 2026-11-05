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
    setFixedWidth(168);
    setToolTip(name);
    auto* layout = new QVBoxLayout(this);
    layout->setContentsMargins(8, 8, 8, 8);
    layout->setSpacing(4);
    auto* image = new QLabel(this);
    image->setAlignment(Qt::AlignCenter);
    image->setFixedSize(152, 96);
    if (!thumb.isNull()) {
        image->setPixmap(thumb.scaled(152, 96, Qt::KeepAspectRatio, Qt::SmoothTransformation));
    } else {
        image->setPixmap(xnIcon(iconName).pixmap(40, 40));
    }
    image->setToolTip(name);
    layout->addWidget(image, 0, Qt::AlignHCenter);
    auto* title = new QLabel(this);
    QFont f = title->font();
    f.setBold(true);
    title->setFont(f);
    title->setText(QFontMetrics(f).elidedText(name, Qt::ElideMiddle, 152));
    title->setToolTip(name);
    title->setAlignment(Qt::AlignCenter);
    layout->addWidget(title, 0, Qt::AlignHCenter);
    auto* when = new QLabel(sub, this);
    QFont small = when->font();
    small.setPointSize(qMax(small.pointSize() - 1, 7));
    when->setFont(small);
    when->setEnabled(false);  // dimmed metadata, display only
    when->setToolTip(name);
    when->setAlignment(Qt::AlignCenter);
    layout->addWidget(when, 0, Qt::AlignHCenter);
}

void TileButton::mousePressEvent(QMouseEvent*) { emit opened(id_); }

}  // namespace xn

