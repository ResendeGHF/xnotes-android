#include "HomeScreen.h"

#include <QBoxLayout>
#include <QContextMenuEvent>
#include <QDir>
#include <QHeaderView>
#include <QInputDialog>
#include <QLabel>
#include <QListWidget>
#include <QMenu>
#include <QPushButton>
#include <QSplitter>

#include <functional>

namespace xn {

HomeScreen::HomeScreen(QWidget* parent) : QWidget(parent) {
    auto* layout = new QVBoxLayout(this);
    layout->setContentsMargins(24, 24, 24, 24);
    layout->setSpacing(12);

    auto* title = new QLabel(QStringLiteral("xnotes companion"), this);
    QFont titleFont = title->font();
    titleFont.setPointSize(22);
    titleFont.setBold(true);
    title->setFont(titleFont);
    layout->addWidget(title);

    auto* localRow = new QHBoxLayout;
    auto* newNote = new QPushButton(QStringLiteral("New note"), this);
    auto* openFile = new QPushButton(QStringLiteral("Open…"), this);
    localRow->addWidget(newNote);
    localRow->addWidget(openFile);
    localRow->addStretch(1);
    layout->addLayout(localRow);
    connect(newNote, &QPushButton::clicked, this, &HomeScreen::newNote);
    connect(openFile, &QPushButton::clicked, this, &HomeScreen::openFile);

    auto* link = new QLabel(QStringLiteral("Companion link"), this);
    QFont sectionFont = link->font();
    sectionFont.setBold(true);
    link->setFont(sectionFont);
    layout->addWidget(link);

    status_ = new QLabel(this);
    status_->setWordWrap(true);
    layout->addWidget(status_);

    connected_ = new QLabel(this);
    layout->addWidget(connected_);

    auto* linkRow = new QHBoxLayout;
    serverButton_ = new QPushButton(this);
    serverButton_->setCheckable(true);
    linkRow->addWidget(serverButton_);
    pairButton_ = new QPushButton(QStringLiteral("Pair a new device…"), this);
    linkRow->addWidget(pairButton_);
    linkRow->addStretch(1);
    layout->addLayout(linkRow);
    connect(serverButton_, &QPushButton::toggled, this, &HomeScreen::serverToggled);
    connect(pairButton_, &QPushButton::clicked, this, &HomeScreen::pairBegin);

    code_ = new QLabel(this);
    QFont codeFont = code_->font();
    codeFont.setPointSize(32);
    codeFont.setBold(true);
    code_->setFont(codeFont);
    code_->setTextInteractionFlags(Qt::TextSelectableByMouse);
    layout->addWidget(code_);

    devices_ = new QListWidget(this);
    devices_->setMaximumHeight(96);
    layout->addWidget(devices_);
    connect(devices_, &QListWidget::itemDoubleClicked, this, [this](QListWidgetItem* item) {
        if (item) emit unpair(item->text());
    });

    auto* shared = new QLabel(QStringLiteral("Shared with you"), this);
    shared->setFont(sectionFont);
    layout->addWidget(shared);

    tree_ = new QTreeView(this);
    treeModel_ = new QStandardItemModel(this);
    treeModel_->setHorizontalHeaderLabels({QStringLiteral("Name"), QStringLiteral("Size")});
    tree_->setModel(treeModel_);
    tree_->setHeaderHidden(false);
    tree_->setContextMenuPolicy(Qt::CustomContextMenu);
    tree_->setMinimumHeight(240);
    layout->addWidget(tree_, 1);
    connect(tree_, &QTreeView::doubleClicked, this, &HomeScreen::onTreeDoubleClicked);
    connect(tree_, &QWidget::customContextMenuRequested, this, &HomeScreen::onTreeMenu);
}

void HomeScreen::setStatus(const QString& s) { status_->setText(s); }

void HomeScreen::setConnected(const QString& device) {
    connected_->setText(device.isEmpty() ? QString() : QStringLiteral("Connected: %1").arg(device));
}

void HomeScreen::setPairCode(const QString& code) {
    code_->setText(code.isEmpty() ? QString() : QStringLiteral("Pairing code: %1").arg(code));
    code_->setVisible(!code.isEmpty());
}

void HomeScreen::setDevices(const QStringList& names) {
    devices_->clear();
    devices_->addItems(names);
    devices_->setVisible(!names.isEmpty());
}

void HomeScreen::setSharing(bool sharing) {
    serverButton_->setChecked(sharing);
    serverButton_->setText(sharing ? QStringLiteral("Stop sharing") : QStringLiteral("Start sharing"));
    pairButton_->setEnabled(sharing);
}

void HomeScreen::setFiles(const QList<SharedFile>& files) {
    files_ = files;
    rebuildTree();
}

void HomeScreen::rebuildTree() {
    treeModel_->removeRows(0, treeModel_->rowCount());
    // Folders first (by path), then files; nested by their relative path.
    QHash<QString, QStandardItem*> dirs;
    dirs[QString()] = treeModel_->invisibleRootItem();
    std::function<QStandardItem*(const QString&)> dirFor = [&](const QString& path) -> QStandardItem* {
        if (dirs.contains(path)) return dirs[path];
        // Create parents first.
        const int slash = path.lastIndexOf(QLatin1Char('/'));
        QStandardItem* parent = slash < 0 ? dirs[QString()] : dirFor(path.left(slash));
        auto* name = new QStandardItem(path.mid(slash + 1));
        name->setEditable(false);
        name->setData(path, Qt::UserRole + 1);
        auto* size = new QStandardItem;
        size->setEditable(false);
        parent->appendRow({name, size});
        dirs[path] = name;
        return name;
    };
    QList<SharedFile> sorted = files_;
    std::sort(sorted.begin(), sorted.end(), [](const SharedFile& a, const SharedFile& b) {
        if ((a.kind == 1) != (b.kind == 1)) return a.kind == 1;
        return a.path < b.path;
    });
    for (const SharedFile& f : sorted) {
        const QString dir = f.path.contains(QLatin1Char('/')) ? f.path.section(QLatin1Char('/'), 0, -2) : QString();
        QStandardItem* parent = dirFor(dir);
        const QString name = f.path.section(QLatin1Char('/'), -1);
        auto* nameItem = new QStandardItem(name);
        nameItem->setEditable(false);
        nameItem->setData(f.id, Qt::UserRole);
        nameItem->setData(f.kind, Qt::UserRole + 1);
        auto* sizeItem = new QStandardItem(f.kind == 1 ? QString() : QString::number(f.size));
        sizeItem->setEditable(false);
        parent->appendRow({nameItem, sizeItem});
        if (f.kind == 1) dirs[f.path] = nameItem;
    }
    tree_->expandAll();
    for (int c = 0; c < treeModel_->columnCount(); ++c) tree_->resizeColumnToContents(c);
}

void HomeScreen::onTreeDoubleClicked(const QModelIndex& index) {
    if (!index.isValid()) return;
    const QStandardItem* item = treeModel_->itemFromIndex(index.siblingAtColumn(0));
    if (!item) return;
    if (item->data(Qt::UserRole + 1).toInt() == 1) return;  // a folder: expands in place
    const QString id = item->data(Qt::UserRole).toString();
    if (!id.isEmpty()) emit openShared(id);
}

void HomeScreen::onTreeMenu(const QPoint& at) {
    const QModelIndex index = tree_->indexAt(at);
    QMenu menu(tree_);
    QString dirId;
    QString fileId;
    if (index.isValid()) {
        const QStandardItem* item = treeModel_->itemFromIndex(index.siblingAtColumn(0));
        if (item && item->data(Qt::UserRole + 1).toInt() == 1) {
            // Folder rows carry their path, not an id; find it.
            const QString path = item->data(Qt::UserRole).toString();
            for (const SharedFile& f : files_)
                if (f.kind == 1 && f.path == path) {
                    dirId = f.id;
                    break;
                }
        } else if (item) {
            fileId = item->data(Qt::UserRole).toString();
        }
    }
    if (!fileId.isEmpty()) {
        menu.addAction(QStringLiteral("Delete on the host…"), this, [this, fileId] { emit deleteShared(fileId); });
    }
    menu.addAction(QStringLiteral("New shared note here…"), this, [this, dirId] {
        bool ok = false;
        const QString name = QInputDialog::getText(this, QStringLiteral("New shared note"), QStringLiteral("Name"),
                                                  QLineEdit::Normal, QStringLiteral("note.xnote"), &ok);
        if (ok && !name.isEmpty()) emit createShared(dirId, name, 0);
    });
    menu.addAction(QStringLiteral("New shared folder here…"), this, [this, dirId] {
        bool ok = false;
        const QString name = QInputDialog::getText(this, QStringLiteral("New shared folder"), QStringLiteral("Name"),
                                                  QLineEdit::Normal, QString(), &ok);
        if (ok && !name.isEmpty()) emit createShared(dirId, name, 1);
    });
    menu.exec(tree_->viewport()->mapToGlobal(at));
}

}  // namespace xn
