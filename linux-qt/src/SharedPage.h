// The Shared files page: a file manager for the tree the server shares.
#pragma once

#include <QList>
#include <QWidget>

class QLineEdit;
class QModelIndex;
class QStandardItemModel;
class QTreeView;

namespace xn {

struct SharedFile {
    QString id;
    QString path;
    int kind = 0;  // 0 file, 1 dir
    long long version = 0;
    long long size = 0;
};

class SharedPage : public QWidget {
    Q_OBJECT
public:
    explicit SharedPage(QWidget* parent = nullptr);

signals:
    void openShared(const QString& id);
    void deleteShared(const QString& id);
    void createShared(const QString& dirId, const QString& name, int kind);

public slots:
    void setFiles(const QList<SharedFile>& files);

private slots:
    void onTreeDoubleClicked(const QModelIndex& index);
    void onTreeMenu(const QPoint& at);
    void onSearch(const QString& text);

private:
    void rebuildTree();

    QLineEdit* search_ = nullptr;
    QTreeView* tree_ = nullptr;
    QStandardItemModel* treeModel_ = nullptr;
    QList<SharedFile> files_;
    QString filter_;
};

}  // namespace xn
