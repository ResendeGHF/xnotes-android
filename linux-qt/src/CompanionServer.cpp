#include "CompanionServer.h"

#include <QCryptographicHash>
#include <QDir>
#include <QFile>
#include <QHostInfo>
#include <QNetworkInterface>
#include <QProcess>
#include <QSslCertificate>
#include <QSslKey>
#include <QSslServer>
#include <QSslSocket>
#include <QStandardPaths>
#include <QSslConfiguration>
#include <QTimer>

namespace xn {

CompanionServer::CompanionServer(QObject* parent) : QObject(parent), deviceName_(loadDeviceName()) {
    host_.ctx = this;
    host_.sync_send = sendThunk;
    host_.sync_pair_request = pairRequestThunk;
    host_.sync_paired = pairedThunk;
    host_.sync_index_changed = indexChangedThunk;
    host_.sync_file_changed = fileChangedThunk;
    host_.sync_file_gone = fileGoneThunk;
    host_.sync_resync = resyncThunk;
    host_.sync_live_dirty = liveDirtyThunk;
    host_.sync_delete_result = deleteResultThunk;
    host_.sync_create_result = createResultThunk;
    host_.sync_error = errorThunk;
}

CompanionServer::~CompanionServer() {
    blockSignals(true);  // the home screen may already be gone
    stop();
}

QString CompanionServer::configDir() const {
    return QStandardPaths::writableLocation(QStandardPaths::AppConfigLocation);
}

QString CompanionServer::loadDeviceName() const {
    QFile f(configDir() + QStringLiteral("/device_name"));
    if (f.open(QIODevice::ReadOnly)) {
        const QString saved = QString::fromUtf8(f.readAll()).trimmed();
        if (!saved.isEmpty()) return saved;
    }
    return QSysInfo::machineHostName();
}

void CompanionServer::setDeviceName(const QString& name) {
    const QString next = name.trimmed();
    QFile f(configDir() + QStringLiteral("/device_name"));
    if (next.isEmpty()) {
        f.remove();
    } else if (f.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        f.write(next.toUtf8());
    }
    deviceName_ = next.isEmpty() ? QSysInfo::machineHostName() : next;
    if (sync_) {
        const QByteArray own = deviceName_.toUtf8();
        xn_sync_set_device_name(sync_, own.constData());
    }
    emit statusChanged(status_);
}

bool CompanionServer::renameDevice(const QString& oldName, const QString& newName) {
    if (sync_ && !oldName.isEmpty() && !newName.trimmed().isEmpty()) {
        return xn_sync_rename(sync_, QFile::encodeName(oldName).constData(), QFile::encodeName(newName.trimmed()).constData()) != 0;
    }
    return false;
}

bool CompanionServer::ensureIdentity() {
    const QString dir = configDir();
    if (!QDir().mkpath(dir)) {
        status_ = QStringLiteral("Could not create %1").arg(dir);
        emit statusChanged(status_);
        return false;
    }
    const QString key = dir + QStringLiteral("/companion.key");
    const QString crt = dir + QStringLiteral("/companion.crt");
    if (!QFile::exists(key) || !QFile::exists(crt)) {
        const QString openssl = QStandardPaths::findExecutable(QStringLiteral("openssl"));
        if (openssl.isEmpty()) {
            status_ = QStringLiteral("openssl not found: cannot mint the companion identity");
            emit statusChanged(status_);
            return false;
        }
        status_ = QStringLiteral("Minting the companion identity…");
        emit statusChanged(status_);
        QProcess proc;
        proc.start(openssl, {QStringLiteral("req"), QStringLiteral("-x509"), QStringLiteral("-newkey"),
                             QStringLiteral("rsa:2048"), QStringLiteral("-nodes"), QStringLiteral("-keyout"), key,
                             QStringLiteral("-out"), crt, QStringLiteral("-days"), QStringLiteral("3650"),
                             QStringLiteral("-subj"), QStringLiteral("/CN=xnotes-share")});
        if (!proc.waitForFinished(60000) || proc.exitCode() != 0) {
            status_ = QStringLiteral("openssl failed: %1").arg(QString::fromUtf8(proc.readAllStandardError()));
            emit statusChanged(status_);
            return false;
        }
    }
    const QList<QSslCertificate> chain = QSslCertificate::fromPath(crt);
    if (chain.isEmpty()) {
        status_ = QStringLiteral("Could not read %1").arg(crt);
        emit statusChanged(status_);
        return false;
    }
    certFp_ = QString::fromLatin1(chain.first().digest(QCryptographicHash::Sha256).toHex());
    return true;
}

bool CompanionServer::start(int port) {
    if (server_) return true;
    if (!ensureIdentity()) return false;
    auto* server = new QSslServer(this);
    QSslConfiguration conf = server->sslConfiguration();
    conf.setLocalCertificateChain(QSslCertificate::fromPath(configDir() + QStringLiteral("/companion.crt")));
    QFile keyFile(configDir() + QStringLiteral("/companion.key"));
    if (!keyFile.open(QIODevice::ReadOnly)) {
        status_ = QStringLiteral("Could not read the companion key");
        emit statusChanged(status_);
        delete server;
        return false;
    }
    conf.setPrivateKey(QSslKey(keyFile.readAll(), QSsl::Rsa));
    conf.setProtocol(QSsl::TlsV1_2OrLater);
    server->setSslConfiguration(conf);
    if (!server->listen(QHostAddress::Any, port)) {
        status_ = QStringLiteral("Could not listen on %1: %2").arg(port).arg(server->errorString());
        emit statusChanged(status_);
        delete server;
        return false;
    }
    server_ = server;
    // Sockets come only from startedEncryptionHandshake, which hands over a live
    // socket. The base-class pending queue is never touched: entries there can be
    // reaped by QSslServer's own handshake cleanup, leaving dangling pointers
    // behind (qobject_cast on one segfaulted here).
    connect(server_, &QSslServer::startedEncryptionHandshake, this, &CompanionServer::onHandshakeStarted);
    connect(server_, &QSslServer::handshakeInterruptedOnError, this, [this](QSslSocket* socket) {
        if (socket != nullptr && socket == socket_.data()) onDisconnected();
    });
    const QByteArray pins = QFile::encodeName(configDir());
    const QByteArray name = deviceName_.toUtf8();
    const QByteArray fp = certFp_.toLatin1();
    sync_ = xn_sync_create(&host_, pins.constData(), name.constData(), fp.constData());
    tick_ = new QTimer(this);
    tick_->setInterval(5000);
    connect(tick_, &QTimer::timeout, this, &CompanionServer::onTick);
    tick_->start();
    status_ = QStringLiteral("Listening");
    emit statusChanged(status_);
    return true;
}

void CompanionServer::stop() {
    if (tick_) {
        tick_->stop();
        tick_->deleteLater();
        tick_ = nullptr;
    }
    if (socket_) {
        // Same clear-first discipline as dropSocket(): disconnectFromHost() may
        // emit disconnected synchronously, which must see an empty socket_.
        QPointer<QSslSocket> s = socket_;
        socket_ = nullptr;
        s->disconnectFromHost();
        s->deleteLater();
    }
    if (server_) {
        server_->close();
        server_->deleteLater();
        server_ = nullptr;
    }
    if (sync_) {
        xn_sync_destroy(sync_);
        sync_ = nullptr;
    }
    status_ = QStringLiteral("Off");
    emit statusChanged(status_);
}

QStringList CompanionServer::lanAddresses() const {
    QStringList out;
    for (const QHostAddress& a : QNetworkInterface::allAddresses()) {
        if (a.protocol() == QHostAddress::IPv4Protocol && !a.isLoopback() && !a.isMulticast()) out << a.toString();
    }
    return out;
}

QString CompanionServer::pairBegin() {
    if (!sync_) return {};
    char* code = xn_sync_pair_begin(sync_);
    const QString out = code ? QString::fromUtf8(code) : QString();
    if (code) xn_free_string(code);
    return out;
}

void CompanionServer::pairDecide(bool accept) {
    if (sync_) xn_sync_pair_decide(sync_, accept ? 1 : 0);
}

void CompanionServer::unpair(const QString& name) {
    if (sync_) xn_sync_unpair(sync_, QFile::encodeName(name).constData());
    emit indexChanged();
}

void CompanionServer::openFile(const QString& id) const {
    if (sync_) xn_sync_open(sync_, QFile::encodeName(id).constData());
}

void CompanionServer::requestDelete(const QString& id) const {
    if (sync_) xn_sync_request_delete(sync_, QFile::encodeName(id).constData());
}

void CompanionServer::requestCreate(const QString& dirId, const QString& name, int kind) const {
    if (!sync_) return;
    const QByteArray dir = QFile::encodeName(dirId);
    xn_sync_request_create(sync_, dirId.isEmpty() ? nullptr : dir.constData(), QFile::encodeName(name).constData(), kind);
}

void CompanionServer::closeFile(const QString& id) const {
    if (sync_) xn_sync_close_file(sync_, QFile::encodeName(id).constData());
}

void CompanionServer::presence(const QString& id, int page) const {
    if (sync_) xn_sync_presence(sync_, QFile::encodeName(id).constData(), page);
}

void CompanionServer::bindEditor(xn_editor* editor, const QString& id) const {
    if (sync_ && editor) xn_sync_bind(sync_, editor, QFile::encodeName(id).constData());
}

void CompanionServer::unbindEditor(xn_editor* editor) const {
    if (sync_ && editor) xn_sync_unbind_editor(sync_, editor);
}

void CompanionServer::takeSocket(QSslSocket* socket) {
    if (socket != nullptr && socket == socket_.data()) return;  // already adopted
    // One host at a time: a new dial replaces the old one.
    dropSocket();
    socket_ = socket;
    if (!socket_) return;
    socket_->setSocketOption(QAbstractSocket::LowDelayOption, 1);
    // Each signal is bound to its own socket: a late signal from a dropped
    // connection must never tear down the current one.
    connect(socket_, &QSslSocket::encrypted, this, [this, socket = socket_.data()] {
        if (socket != nullptr && socket == this->socket_.data()) onEncrypted();
    });
    connect(socket_, &QSslSocket::readyRead, this, [this, socket = socket_.data()] {
        if (socket != nullptr && socket == this->socket_.data()) onReadyRead();
    });
    connect(socket_, &QSslSocket::disconnected, this, [this, socket = socket_.data()] {
        if (socket != nullptr && socket == this->socket_.data()) onDisconnected();
    });
    connect(socket_, &QSslSocket::errorOccurred, this, [this, socket = socket_.data()](QAbstractSocket::SocketError) {
        if (socket != nullptr && socket == this->socket_.data()) onDisconnected();
    });
    if (socket_->isEncrypted()) onEncrypted();
}

void CompanionServer::dropSocket() {
    // Clear first: disconnectFromHost() can emit disconnected/error synchronously,
    // and the per-socket guard then sees we already let go instead of recursing
    // into a second deleteLater on a null QPointer (that was the segfault).
    QPointer<QSslSocket> s = socket_;
    socket_ = nullptr;
    if (s) {
        // deleteLater, not delete: signals already in flight for it stay valid.
        s->disconnectFromHost();
        s->deleteLater();
    }
}

void CompanionServer::onHandshakeStarted(QSslSocket* socket) {
    if (!server_ || socket == nullptr) return;
    takeSocket(socket);
}

void CompanionServer::onEncrypted() {
    // QSslServer queues the socket in the base pending list once encrypted; take our
    // adopted one back out so close() never deletes a live socket out from under us.
    while (server_ != nullptr && server_->hasPendingConnections()) {
        if (auto* queued = qobject_cast<QSslSocket*>(server_->nextPendingConnection())) {
            if (queued != socket_.data()) {
                // Not ours (should not happen with one host at a time): put it back
                // by leaving it alone is impossible, so drop it rather than leak it.
                queued->disconnectFromHost();
                queued->deleteLater();
            }
        } else {
            break;
        }
    }
    if (socket_ && sync_) xn_sync_hello(sync_);
}

void CompanionServer::onReadyRead() {
    if (!socket_ || !sync_) return;
    const QByteArray bytes = socket_->readAll();
    if (!bytes.isEmpty()) xn_sync_feed(sync_, reinterpret_cast<const uint8_t*>(bytes.constData()), bytes.size());
}

void CompanionServer::onDisconnected() {
    dropSocket();
    if (sync_) xn_sync_reset(sync_);
    status_ = server_ ? QStringLiteral("Listening") : status_;
    emit statusChanged(status_);
}

void CompanionServer::onTick() {
    if (sync_) xn_sync_tick(sync_);
}

void CompanionServer::sendThunk(void* ctx, const uint8_t* data, int len) {
    auto* self = static_cast<CompanionServer*>(ctx);
    if (!self) return;
    if (self->socket_ && data && len > 0) {
        self->socket_->write(reinterpret_cast<const char*>(data), len);
    }
}

void CompanionServer::pairRequestThunk(void* ctx, const char* device, const char* fp) {
    auto* self = static_cast<CompanionServer*>(ctx);
    if (!self || !device || !fp) return;
    emit self->pairRequested(QString::fromUtf8(device), QString::fromUtf8(fp));
}

void CompanionServer::pairedThunk(void* ctx, const char* device) {
    auto* self = static_cast<CompanionServer*>(ctx);
    if (!self || !device) return;
    emit self->paired(QString::fromUtf8(device));
}

void CompanionServer::indexChangedThunk(void* ctx) {
    emit static_cast<CompanionServer*>(ctx)->indexChanged();
}

void CompanionServer::fileChangedThunk(void* ctx, const char* id) {
    auto* self = static_cast<CompanionServer*>(ctx);
    if (!self || !id) return;
    emit self->fileReady(QString::fromUtf8(id));
}

void CompanionServer::fileGoneThunk(void* ctx, const char* id) {
    auto* self = static_cast<CompanionServer*>(ctx);
    if (!self || !id) return;
    emit self->fileGone(QString::fromUtf8(id));
}

void CompanionServer::resyncThunk(void* ctx, const char* id, long long version) {
    auto* self = static_cast<CompanionServer*>(ctx);
    if (!self || !id) return;
    emit self->resync(QString::fromUtf8(id), version);
}

void CompanionServer::liveDirtyThunk(void* ctx) {
    emit static_cast<CompanionServer*>(ctx)->paint();
}

void CompanionServer::deleteResultThunk(void* ctx, int ok, const char* reason) {
    auto* self = static_cast<CompanionServer*>(ctx);
    emit self->deleteAnswered(ok != 0, reason ? QString::fromUtf8(reason) : QString());
}

void CompanionServer::createResultThunk(void* ctx, int ok, const char* id, const char* reason) {
    auto* self = static_cast<CompanionServer*>(ctx);
    emit self->createAnswered(ok != 0, id ? QString::fromUtf8(id) : QString(), reason ? QString::fromUtf8(reason) : QString());
}

void CompanionServer::errorThunk(void* ctx, const char* reason) {
    auto* self = static_cast<CompanionServer*>(ctx);
    if (!self) return;
    emit self->error(reason ? QString::fromUtf8(reason) : QStringLiteral("sync error"));
}

}  // namespace xn
