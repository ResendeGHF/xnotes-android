#include "BrowsePage.h"

#include "Icons.h"
#include "NoteThumb.h"
#include "TileButton.h"

#include <QBoxLayout>
#include <QDateTime>
#include <QDir>
#include <QFileInfo>
#include <QGridLayout>
#include <QLayoutItem>
#include <QLabel>
#include <QLineEdit>
#include <QPushButton>
#include <QScrollArea>
#include <QSettings>
#include <QStandardPaths>

namespace xn {

namespace {

bool isNoteFile(const QFileInfo& info) {
    if (!info.isFile() || info.isHidden()) return false;
    const QString suffix = info.suffix().toLower();
    return suffix == QStringLiteral("xnote") || suffix == QStringLiteral("xcanvas") || suffix == QStringLiteral("pdf");
}

QString fileIcon(const QFileInfo& info) {
    const QString suffix = info.suffix().toLower();
    if (suffix == QStringLiteral("pdf")) return QStringLiteral("pdfFile");
    if (suffix == QStringLiteral("xcanvas")) return QStringLiteral("canvas");
    return QStringLiteral("file");
}

QString ago(qint64 secs) {
    const qint64 s = qMax<qint64>(0, QDateTime::currentSecsSinceEpoch() - secs);
    if (s < 3600) return QStringLiteral("%1 min ago").arg(s / 60 + 1);
    if (s < 86400) return QStringLiteral("%1 h ago").arg(s / 3600);
    if (s < 86400 * 30) return QStringLiteral("%1 d ago").arg(s / 86400);
    return QDateTime::fromSecsSinceEpoch(secs).toString(QStringLiteral("yyyy-MM-dd"));
}

void clearLayout(QGridLayout* grid) {
    while (QLayoutItem* child = grid->takeAt(0)) {
        if (child->widget()) child->widget()->deleteLater();
        delete child;
    }
}

}  // namespace

BrowsePage::BrowsePage(QWidget* parent) : QWidget(parent) {
    auto* outer = new QVBoxLayout(this);
    outer->setContentsMargins(24, 24, 24, 24);
    outer->setSpacing(8);

    auto* title = new QLabel(QStringLiteral("Browse notes on this device"), this);
    QFont titleFont = title->font();
    titleFont.setPointSize(18);
    titleFont.setBold(true);
    title->setFont(titleFont);
    outer->addWidget(title);

    auto* bar = new QHBoxLayout;
    auto* up = new QPushButton(xnIcon(QStringLiteral("arrowUp")), QStringLiteral("Up"), this);
    bar->addWidget(up);
    connect(up, &QPushButton::clicked, this, &BrowsePage::goUp);
    crumb_ = new QLabel(this);
    crumb_->setTextInteractionFlags(Qt::TextSelectableByMouse);
    bar->addWidget(crumb_, 1);
    search_ = new QLineEdit(this);
    search_->setPlaceholderText(QStringLiteral("Search"));
    search_->setClearButtonEnabled(true);
    search_->setMaximumWidth(220);
    bar->addWidget(search_);
    connect(search_, &QLineEdit::textChanged, this, &BrowsePage::onSearch);
    auto* fresh = new QPushButton(QStringLiteral("New note"), this);
    bar->addWidget(fresh);
    connect(fresh, &QPushButton::clicked, this, &BrowsePage::newNote);
    outer->addLayout(bar);

    auto* scroll = new QScrollArea(this);
    scroll->setWidgetResizable(true);
    scroll->setFrameShape(QFrame::NoFrame);
    auto* inner = new QWidget(scroll);
    grid_ = new QGridLayout(inner);
    grid_->setSpacing(8);
    scroll->setWidget(inner);
    outer->addWidget(scroll, 1);

    QSettings settings;
    dir_ = settings.value(QStringLiteral("browse/dir"), QDir::homePath()).toString();
    rebuild();
}

void BrowsePage::refresh() { rebuild(); }

void BrowsePage::onSearch(const QString& text) {
    filter_ = text.trimmed();
    rebuild();
}

void BrowsePage::goUp() {
    QDir d(dir_);
    if (d.cdUp()) setDir(d.absolutePath());
}

void BrowsePage::setDir(const QString& path) {
    dir_ = path;
    QSettings().setValue(QStringLiteral("browse/dir"), dir_);
    rebuild();
}

void BrowsePage::rebuild() {
    clearLayout(grid_);
    // Reset stretches from the previous build (row/col counts change), then
    // re-anchor them: a trailing stretch row/column absorbs the viewport
    // surplus so tiles keep their content size instead of spreading out.
    for (int r = 0; r < grid_->rowCount(); ++r) grid_->setRowStretch(r, 0);
    for (int c = 0; c < grid_->columnCount(); ++c) grid_->setColumnStretch(c, 0);
    crumb_->setText(dir_);
    QDir d(dir_);
    const auto entries = d.entryInfoList(QDir::Dirs | QDir::Files | QDir::NoDotAndDotDot, QDir::DirsFirst | QDir::Name | QDir::IgnoreCase);
    int col = 0, row = 0;
    constexpr int kCols = 5;
    for (const QFileInfo& info : entries) {
        if (info.fileName().startsWith(QLatin1Char('.'))) continue;
        const bool isDir = info.isDir();
        if (!isDir && !isNoteFile(info)) continue;
        if (!filter_.isEmpty() && !info.fileName().contains(filter_, Qt::CaseInsensitive)) continue;
        QPixmap thumb;
        if (!isDir && info.suffix().compare(QStringLiteral("xnote"), Qt::CaseInsensitive) == 0) {
            thumb = NoteThumb::render(info.absoluteFilePath(), 140);
        }
        const QString sub = isDir ? QStringLiteral("folder")
                                  : QStringLiteral("%1 · %2").arg(ago(info.lastModified().toSecsSinceEpoch()),
                                                                 QLocale().formattedDataSize(info.size()));
        auto* tile = new TileButton(thumb, isDir ? QStringLiteral("folder") : fileIcon(info), info.fileName(), sub,
                                    info.absoluteFilePath(), this);
        if (isDir) {
            connect(tile, &TileButton::opened, this, [this](const QString& path) { setDir(path); });
        } else {
            connect(tile, &TileButton::opened, this, [this](const QString& path) { emit openLocal(path); });
        }
        grid_->addWidget(tile, row, col);
        if (++col >= kCols) {
            col = 0;
            ++row;
        }
    }
    grid_->setRowStretch(row + (col > 0 ? 1 : 0), 1);
    grid_->setColumnStretch(kCols, 1);
}

}  // namespace xn

