#include "SharedPage.h"

#include <QBoxLayout>
#include <QDir>
#include <QInputDialog>
#include <QLabel>
#include <QLineEdit>
#include <QMenu>
#include <QPushButton>
#include <QStandardItemModel>
#include <QTreeView>

namespace xn {

SharedPage::SharedPage(QWidget* parent) : QWidget(parent) {
    auto* outer = new QVBoxLayout(this);
    outer->setContentsMargins(24, 24, 24, 24);
    outer->setSpacing(8);

    auto* titleRow = new QHBoxLayout();
    auto* title = new QLabel(QStringLiteral("Shared with you"), this);
    QFont titleFont = title->font();
    titleFont.setPointSize(18);
    titleFont.setBold(true);
    title->setFont(titleFont);
    titleRow->addWidget(title);
    titleRow->addStretch(1);
    auto* refreshButton = new QPushButton(QStringLiteral("Refresh"), this);
    refreshButton->setToolTip(QStringLiteral("Ask the phone for the latest shared files"));
    titleRow->addWidget(refreshButton);
    outer->addLayout(titleRow);
    connect(refreshButton, &QPushButton::clicked, this, &SharedPage::refreshRequested);

    search_ = new QLineEdit(this);
    search_->setPlaceholderText(QStringLiteral("Search shared files"));
    search_->setClearButtonEnabled(true);
    search_->setMaximumWidth(260);
    outer->addWidget(search_);
    connect(search_, &QLineEdit::textChanged, this, &SharedPage::onSearch);

    tree_ = new QTreeView(this);
    treeModel_ = new QStandardItemModel(this);
    treeModel_->setHorizontalHeaderLabels({QStringLiteral("Name"), QStringLiteral("Size")});
    tree_->setModel(treeModel_);
    tree_->setHeaderHidden(false);
    tree_->setContextMenuPolicy(Qt::CustomContextMenu);
    outer->addWidget(tree_, 1);
    connect(tree_, &QTreeView::doubleClicked, this, &SharedPage::onTreeDoubleClicked);
    connect(tree_, &QWidget::customContextMenuRequested, this, &SharedPage::onTreeMenu);
}

void SharedPage::setFiles(const QList<SharedFile>& files) {
    files_ = files;
    rebuildTree();
}

void SharedPage::onSearch(const QString& text) {
    filter_ = text.trimmed();
    rebuildTree();
}

void SharedPage::rebuildTree() {
    treeModel_->removeRows(0, treeModel_->rowCount());
    QHash<QString, QStandardItem*> dirs;
    dirs[QString()] = treeModel_->invisibleRootItem();
    std::function<QStandardItem*(const QString&)> dirFor = [&](const QString& path) -> QStandardItem* {
        if (dirs.contains(path)) return dirs[path];
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
        if (!filter_.isEmpty() && !f.path.contains(filter_, Qt::CaseInsensitive)) continue;
        const QString dir = f.path.contains(QLatin1Char('/')) ? f.path.section(QLatin1Char('/'), 0, -2) : QString();
        QStandardItem* parent = dirFor(dir);
        const QString name = f.path.section(QLatin1Char('/'), -1);
        auto* nameItem = new QStandardItem(name);
        nameItem->setEditable(false);
        nameItem->setData(f.id, Qt::UserRole);
        nameItem->setData(f.kind, Qt::UserRole + 1);
        auto* sizeItem = new QStandardItem(f.kind == 1 ? QString() : QLocale().formattedDataSize(f.size));
        sizeItem->setEditable(false);
        parent->appendRow({nameItem, sizeItem});
        if (f.kind == 1) dirs[f.path] = nameItem;
    }
    tree_->expandAll();
    for (int c = 0; c < treeModel_->columnCount(); ++c) tree_->resizeColumnToContents(c);
}

void SharedPage::onTreeDoubleClicked(const QModelIndex& index) {
    if (!index.isValid()) return;
    const QStandardItem* item = treeModel_->itemFromIndex(index.siblingAtColumn(0));
    if (!item || item->data(Qt::UserRole + 1).toInt() == 1) return;  // folders expand in place
    const QString id = item->data(Qt::UserRole).toString();
    if (!id.isEmpty()) emit openShared(id);
}

void SharedPage::onTreeMenu(const QPoint& at) {
    const QModelIndex index = tree_->indexAt(at);
    QMenu menu(tree_);
    QString dirId;
    QString fileId;
    if (index.isValid()) {
        const QStandardItem* item = treeModel_->itemFromIndex(index.siblingAtColumn(0));
        if (item && item->data(Qt::UserRole + 1).toInt() == 1) {
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

