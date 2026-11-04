// The companion side of SYNC.md v1: a TLS server the Android host dials, with
// pairing (SAS code + desktop approval), the shared-file index, fetch, committed
// ops both ways and true-streamed live strokes. Bytes go straight into the
// core's sync engine; pairing and file events come back as signals.
#pragma once

#include "xnotes.h"

#include <QHostAddress>
#include <QObject>
#include <QSslError>
#include <QString>

#include <memory>

class QSslServer;
class QSslSocket;
class QTimer;

namespace xn {

class CompanionServer : public QObject {
    Q_OBJECT
public:
    explicit CompanionServer(QObject* parent = nullptr);
    ~CompanionServer() override;

    /* The engine handle for the xn_sync_* queries (index, files, pairing). Null until start(). */
    xn_sync* sync() const { return sync_; }

    /* Start listening (cert bootstrap included). False with status() saying why. */
    bool start();
    void stop();
    bool running() const { return server_ != nullptr; }

    /* This device's name and cert fingerprint, for the engine handshake. */
    QString deviceName() const { return deviceName_; }
    QString certFingerprint() const { return certFp_; }
    /* LAN IPv4 addresses to show, and the port. */
    QStringList lanAddresses() const;
    static constexpr int kPort = 42317;

    /* Pairing: show the code, answer the approval dialog. */
    QString pairBegin();
    void pairDecide(bool accept);
    void unpair(const QString& name);

    /* Files: fetch one for opening, ask the host to delete/create. */
    void openFile(const QString& id) const;
    void requestDelete(const QString& id) const;
    void requestCreate(const QString& dirId, const QString& name, int kind) const;
    void closeFile(const QString& id) const;
    void presence(const QString& id, int page) const;

    /* Tie an open editor to a sync file (local edits submit, remote ops apply). */
    void bindEditor(xn_editor* editor, const QString& id) const;
    void unbindEditor(xn_editor* editor) const;

signals:
    void statusChanged(const QString& status);
    void pairRequested(const QString& device, const QString& fp);
    void paired(const QString& device);
    void indexChanged();
    void fileReady(const QString& id);
    void fileGone(const QString& id);
    void resync(const QString& id, long long version);
    void paint();
    void deleteAnswered(bool ok, const QString& reason);
    void createAnswered(bool ok, const QString& id, const QString& reason);
    void error(const QString& reason);

private slots:
    void onNewConnection();
    void onEncrypted();
    void onReadyRead();
    void onDisconnected();
    void onTick();

private:
    static void sendThunk(void* ctx, const uint8_t* data, int len);
    static void pairRequestThunk(void* ctx, const char* device, const char* fp);
    static void pairedThunk(void* ctx, const char* device);
    static void indexChangedThunk(void* ctx);
    static void fileChangedThunk(void* ctx, const char* id);
    static void fileGoneThunk(void* ctx, const char* id);
    static void resyncThunk(void* ctx, const char* id, long long version);
    static void liveDirtyThunk(void* ctx);
    static void deleteResultThunk(void* ctx, int ok, const char* reason);
    static void createResultThunk(void* ctx, int ok, const char* id, const char* reason);
    static void errorThunk(void* ctx, const char* reason);

    /* ~/.config/xnotes, holding companion.{key,crt} and the pins file. */
    QString configDir() const;
    /* Make the identity if missing; loads it into certFp_. False with status(). */
    bool ensureIdentity();

    xn_host host_{};
    xn_sync* sync_ = nullptr;
    QSslServer* server_ = nullptr;
    QSslSocket* socket_ = nullptr;
    QTimer* tick_ = nullptr;
    QString deviceName_;
    QString certFp_;
    QString status_;
};

}  // namespace xn
