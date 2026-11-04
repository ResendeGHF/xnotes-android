#include "HomeScreen.h"

#include "Icons.h"
#include "NoteThumb.h"
#include "Recents.h"
#include "TileButton.h"

#include <QBoxLayout>
#include <QFileInfo>
#include <QGridLayout>
#include <QLabel>
#include <QLayoutItem>
#include <QListWidget>
#include <QMouseEvent>
#include <QPushButton>
#include <QScrollArea>

namespace xn {

namespace {

void clearLayout(QGridLayout* grid) {
    while (QLayoutItem* child = grid->takeAt(0)) {
        if (child->widget()) child->widget()->deleteLater();
        delete child;
    }
}

}  // namespace

HomeScreen::HomeScreen(QWidget* parent) : QWidget(parent) {
    auto* outer = new QVBoxLayout(this);
    outer->setContentsMargins(24, 24, 24, 24);
    outer->setSpacing(12);

    auto* title = new QLabel(QStringLiteral("xnotes companion"), this);
    QFont titleFont = title->font();
    titleFont.setPointSize(22);
    titleFont.setBold(true);
    title->setFont(titleFont);
    outer->addWidget(title);

    // --- connection card ---
    auto* link = new QLabel(QStringLiteral("Companion link"), this);
    QFont section = link->font();
    section.setBold(true);
    link->setFont(section);
    outer->addWidget(link);

    status_ = new QLabel(this);
    status_->setWordWrap(true);
    outer->addWidget(status_);

    connected_ = new QLabel(this);
    outer->addWidget(connected_);

    auto* linkRow = new QHBoxLayout;
    serverButton_ = new QPushButton(this);
    serverButton_->setCheckable(true);
    linkRow->addWidget(serverButton_);
    pairButton_ = new QPushButton(QStringLiteral("Pair a new device…"), this);
    linkRow->addWidget(pairButton_);
    linkRow->addStretch(1);
    outer->addLayout(linkRow);
    connect(serverButton_, &QPushButton::toggled, this, &HomeScreen::serverToggled);
    connect(pairButton_, &QPushButton::clicked, this, &HomeScreen::pairBegin);

    code_ = new QLabel(this);
    QFont codeFont = code_->font();
    codeFont.setPointSize(32);
    codeFont.setBold(true);
    code_->setFont(codeFont);
    code_->setTextInteractionFlags(Qt::TextSelectableByMouse);
    code_->setVisible(false);
    outer->addWidget(code_);

    devices_ = new QListWidget(this);
    devices_->setMaximumHeight(96);
    devices_->setVisible(false);
    outer->addWidget(devices_);
    connect(devices_, &QListWidget::itemDoubleClicked, this, [this](QListWidgetItem* item) {
        if (item) emit unpair(item->text());
    });

    // --- recents ---
    auto* recent = new QLabel(QStringLiteral("Recent on this device"), this);
    recent->setFont(section);
    outer->addWidget(recent);

    auto* recentScroll = new QScrollArea(this);
    recentScroll->setWidgetResizable(true);
    recentScroll->setFrameShape(QFrame::NoFrame);
    recentScroll->setMaximumHeight(230);
    auto* recentInner = new QWidget(recentScroll);
    recentGrid_ = new QGridLayout(recentInner);
    recentGrid_->setSpacing(8);
    recentScroll->setWidget(recentInner);
    outer->addWidget(recentScroll);

    sharedRecentLabel_ = new QLabel(QStringLiteral("Recent from the server"), this);
    sharedRecentLabel_->setFont(section);
    outer->addWidget(sharedRecentLabel_);

    sharedRecentScroll_ = new QScrollArea(this);
    sharedRecentScroll_->setWidgetResizable(true);
    sharedRecentScroll_->setFrameShape(QFrame::NoFrame);
    sharedRecentScroll_->setMaximumHeight(230);
    auto* sharedInner = new QWidget(sharedRecentScroll_);
    sharedRecentGrid_ = new QGridLayout(sharedInner);
    sharedRecentGrid_->setSpacing(8);
    sharedRecentScroll_->setWidget(sharedInner);
    outer->addWidget(sharedRecentScroll_);
    outer->addStretch(1);

    auto* localRow = new QHBoxLayout;
    auto* newNote = new QPushButton(QStringLiteral("New note"), this);
    auto* openFile = new QPushButton(QStringLiteral("Open…"), this);
    localRow->addWidget(newNote);
    localRow->addWidget(openFile);
    localRow->addStretch(1);
    outer->addLayout(localRow);
    connect(newNote, &QPushButton::clicked, this, &HomeScreen::newNote);
    connect(openFile, &QPushButton::clicked, this, &HomeScreen::openFile);
}

void HomeScreen::setStatus(const QString& s) { status_->setText(s); }

void HomeScreen::setConnected(const QString& device) {
    connected_->setText(device.isEmpty() ? QString() : QStringLiteral("Connected: %1").arg(device));
    sharedRecentLabel_->setVisible(!device.isEmpty());
    sharedRecentScroll_->setVisible(!device.isEmpty());
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

void HomeScreen::refreshRecents() {
    clearLayout(recentGrid_);
    int col = 0;
    for (const RecentEntry& e : Recents::local()) {
        QPixmap thumb;
        if (e.id.endsWith(QStringLiteral(".xnote"), Qt::CaseInsensitive)) thumb = NoteThumb::render(e.id, 140);
        auto* tile = new TileButton(thumb, QStringLiteral("file"), e.name, Recents::ago(e.opened), e.id, this);
        connect(tile, &TileButton::opened, this, [this](const QString& id) { emit openLocal(id); });
        recentGrid_->addWidget(tile, 0, col++);
    }
    clearLayout(sharedRecentGrid_);
    col = 0;
    for (const RecentEntry& e : Recents::shared()) {
        auto* tile = new TileButton(QPixmap(), QStringLiteral("share"), e.name, Recents::ago(e.opened), e.id, this);
        connect(tile, &TileButton::opened, this, [this](const QString& id) { emit openShared(id); });
        sharedRecentGrid_->addWidget(tile, 0, col++);
    }
}

}  // namespace xn

