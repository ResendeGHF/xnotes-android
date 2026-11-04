// The companion home: reachable notes on this device, the companion link
// (status, pairing, paired devices) and the files the host shares with us.
#pragma once

#include <QLabel>
#include <QList>
#include <QPushButton>
#include <QStandardItemModel>
#include <QTreeView>
#include <QWidget>

class QListWidget;

namespace xn {

/* One shared file or folder, as the engine lists it. */
struct SharedFile {
    QString id;
    QString path;
    int kind = 0;  // 0 file, 1 dir
    long long version = 0;
    long long size = 0;
};

class HomeScreen : public QWidget {
    Q_OBJECT
public:
    explicit HomeScreen(QWidget* parent = nullptr);

signals:
    void newNote();
    void openFile();
    void openShared(const QString& id);
    void serverToggled(bool on);
    void pairBegin();
    void unpair(const QString& name);
    void deleteShared(const QString& id);
    void createShared(const QString& dirId, const QString& name, int kind);

public slots:
    /* Listening/connectivity line, e.g. "Listening on 192.168.1.5:42317". */
    void setStatus(const QString& s);
    /* Connected device name, empty when none. */
    void setConnected(const QString& device);
    /* Pairing code to display (empty hides it), with the note when waiting. */
    void setPairCode(const QString& code);
    void setDevices(const QStringList& names);
    void setFiles(const QList<SharedFile>& files);
    void setSharing(bool sharing);

private slots:
    void onTreeDoubleClicked(const QModelIndex& index);
    void onTreeMenu(const QPoint& at);

private:
    void rebuildTree();

    QLabel* status_ = nullptr;
    QLabel* connected_ = nullptr;
    QLabel* code_ = nullptr;
    QPushButton* pairButton_ = nullptr;
    QPushButton* serverButton_ = nullptr;
    QListWidget* devices_ = nullptr;
    QTreeView* tree_ = nullptr;
    QStandardItemModel* treeModel_ = nullptr;
    QList<SharedFile> files_;
};

}  // namespace xn
