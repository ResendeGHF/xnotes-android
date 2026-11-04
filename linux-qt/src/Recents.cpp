#include "Recents.h"

#include <QFileInfo>
#include <QSettings>

namespace xn {

namespace {

constexpr int kKeep = 20;

QList<RecentEntry> readList(const QString& key) {
    QList<RecentEntry> out;
    QSettings settings;
    const int n = settings.beginReadArray(key);
    for (int i = 0; i < n; ++i) {
        settings.setArrayIndex(i);
        RecentEntry e;
        e.id = settings.value(QStringLiteral("id")).toString();
        e.name = settings.value(QStringLiteral("name")).toString();
        e.opened = settings.value(QStringLiteral("opened")).toLongLong();
        if (!e.id.isEmpty()) out.append(e);
    }
    settings.endArray();
    return out;
}

void writeList(const QString& key, QList<RecentEntry> entries) {
    std::sort(entries.begin(), entries.end(), [](const RecentEntry& a, const RecentEntry& b) { return a.opened > b.opened; });
    while (entries.size() > kKeep) entries.removeLast();
    QSettings settings;
    settings.beginWriteArray(key, entries.size());
    for (int i = 0; i < entries.size(); ++i) {
        settings.setArrayIndex(i);
        settings.setValue(QStringLiteral("id"), entries[i].id);
        settings.setValue(QStringLiteral("name"), entries[i].name);
        settings.setValue(QStringLiteral("opened"), entries[i].opened);
    }
    settings.endArray();
}

}  // namespace

QList<RecentEntry> Recents::local() { return readList(QStringLiteral("recent/local")); }

QList<RecentEntry> Recents::shared() { return readList(QStringLiteral("recent/shared")); }

void Recents::touchLocal(const QString& path) {
    if (path.isEmpty()) return;
    auto entries = readList(QStringLiteral("recent/local"));
    entries.removeIf([&](const RecentEntry& e) { return e.id == path; });
    entries.prepend({path, QFileInfo(path).fileName(), QDateTime::currentSecsSinceEpoch()});
    writeList(QStringLiteral("recent/local"), entries);
}

void Recents::touchShared(const QString& id, const QString& path) {
    if (id.isEmpty()) return;
    auto entries = readList(QStringLiteral("recent/shared"));
    entries.removeIf([&](const RecentEntry& e) { return e.id == id; });
    const QString name = path.section(QLatin1Char('/'), -1);
    entries.prepend({id, name.isEmpty() ? id : name, QDateTime::currentSecsSinceEpoch()});
    writeList(QStringLiteral("recent/shared"), entries);
}

void Recents::forgetShared(const QString& id) {
    auto entries = readList(QStringLiteral("recent/shared"));
    entries.removeIf([&](const RecentEntry& e) { return e.id == id; });
    writeList(QStringLiteral("recent/shared"), entries);
}

void Recents::clearShared() { writeList(QStringLiteral("recent/shared"), {}); }

QString Recents::ago(qint64 when) {
    const qint64 s = qMax<qint64>(0, QDateTime::currentSecsSinceEpoch() - when);
    if (s < 60) return QStringLiteral("just now");
    if (s < 3600) return QStringLiteral("%1 min ago").arg(s / 60);
    if (s < 86400) return QStringLiteral("%1 h ago").arg(s / 3600);
    if (s < 86400 * 30) return QStringLiteral("%1 d ago").arg(s / 86400);
    return QDateTime::fromSecsSinceEpoch(when).toString(QStringLiteral("yyyy-MM-dd"));
}

}  // namespace xn
