// Recently opened notes: local files and, once paired, shared server files.
#pragma once

#include <QDateTime>
#include <QList>
#include <QString>

namespace xn {

struct RecentEntry {
    QString id;    // local path, or the sync id for shared files
    QString name;  // display name
    qint64 opened = 0;  // seconds since epoch
};

class Recents {
public:
    static QList<RecentEntry> local();
    static QList<RecentEntry> shared();
    static void touchLocal(const QString& path);
    static void touchShared(const QString& id, const QString& path);
    static void forgetShared(const QString& id);
    static void clearShared();

    /* "2 h ago" style relative time. */
    static QString ago(qint64 when);

private:
    static QList<RecentEntry> read(const QString& key);
    static void write(const QString& key, const QList<RecentEntry>& entries);
};

}  // namespace xn
