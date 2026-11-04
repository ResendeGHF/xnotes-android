// The Browse page: a file manager for notes imported locally, like the
// Android app's explorer (folders and note tiles, search to filter).
#pragma once

#include <QDir>
#include <QWidget>

class QGridLayout;
class QLabel;
class QLineEdit;

namespace xn {

class BrowsePage : public QWidget {
    Q_OBJECT
public:
    explicit BrowsePage(QWidget* parent = nullptr);

signals:
    void openLocal(const QString& path);
    void newNote();

public slots:
    void refresh();

private slots:
    void onSearch(const QString& text);
    void goUp();

private:
    void rebuild();
    void setDir(const QString& path);

    QString dir_;
    QString filter_;
    QLineEdit* search_ = nullptr;
    QLabel* crumb_ = nullptr;
    QGridLayout* grid_ = nullptr;
};

}  // namespace xn
