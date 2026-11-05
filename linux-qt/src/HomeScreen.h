// The companion home: the server connection, paired devices, this device.
#pragma once

#include <QLabel>
#include <QList>
#include <QPushButton>
#include <QWidget>

class QListWidget;

namespace xn {

class HomeScreen : public QWidget {
    Q_OBJECT
public:
    explicit HomeScreen(QWidget* parent = nullptr);

signals:
    void serverToggled(bool on);
    void pairBegin();
    void unpair(const QString& name);
    void renameDevice(const QString& oldName);
    void renameSelf();

public slots:
    /* Listening/connectivity line, e.g. "Listening on 192.168.1.5:42317". */
    void setStatus(const QString& s);
    /* Connected device name, empty when none. */
    void setConnected(const QString& device);
    /* Pairing code to display (empty hides it). */
    void setPairCode(const QString& code);
    void setDevices(const QStringList& names);
    void setSharing(bool sharing);
    /* This companion's own nickname, shown to the host in requests. */
    void setSelfName(const QString& name);

private:
    QLabel* status_ = nullptr;
    QLabel* connected_ = nullptr;
    QLabel* code_ = nullptr;
    QPushButton* serverButton_ = nullptr;
    QPushButton* pairButton_ = nullptr;
    QListWidget* devices_ = nullptr;
    QPushButton* forgetButton_ = nullptr;
    QPushButton* renameButton_ = nullptr;
    QLabel* selfName_ = nullptr;
};

}  // namespace xn
