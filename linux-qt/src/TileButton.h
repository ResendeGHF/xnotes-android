// A file tile: thumbnail or icon, name and a sub-line. Click to open.
#pragma once

#include <QFrame>
#include <QPixmap>
#include <QString>

class QLabel;

namespace xn {

class TileButton : public QFrame {
    Q_OBJECT
public:
    TileButton(const QPixmap& thumb, const QString& iconName, const QString& name, const QString& sub, const QString& id,
               QWidget* parent = nullptr);

signals:
    void opened(const QString& id);

protected:
    void mousePressEvent(QMouseEvent* e) override;

private:
    QString id_;
};

}  // namespace xn
