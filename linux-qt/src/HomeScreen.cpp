#include "HomeScreen.h"

#include <QBoxLayout>
#include <QGroupBox>
#include <QLabel>
#include <QListWidget>
#include <QPushButton>

namespace xn {

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
    auto* link = new QGroupBox(QStringLiteral("Connection"), this);
    auto* linkLayout = new QVBoxLayout(link);
    linkLayout->setSpacing(8);

    status_ = new QLabel(link);
    status_->setWordWrap(true);
    linkLayout->addWidget(status_);

    connected_ = new QLabel(link);
    linkLayout->addWidget(connected_);

    auto* linkRow = new QHBoxLayout;
    linkRow->setContentsMargins(0, 0, 0, 0);
    serverButton_ = new QPushButton(link);
    serverButton_->setCheckable(true);
    linkRow->addWidget(serverButton_);
    pairButton_ = new QPushButton(QStringLiteral("Pair a new device…"), link);
    linkRow->addWidget(pairButton_);
    linkRow->addStretch(1);
    linkLayout->addLayout(linkRow);
    connect(serverButton_, &QPushButton::toggled, this, &HomeScreen::serverToggled);
    connect(pairButton_, &QPushButton::clicked, this, &HomeScreen::pairBegin);

    code_ = new QLabel(link);
    QFont codeFont = code_->font();
    codeFont.setPointSize(32);
    codeFont.setBold(true);
    code_->setFont(codeFont);
    code_->setTextInteractionFlags(Qt::TextSelectableByMouse);
    code_->setVisible(false);
    linkLayout->addWidget(code_);
    outer->addWidget(link);

    // --- paired devices card ---
    auto* devicesBox = new QGroupBox(QStringLiteral("Paired devices"), this);
    auto* devicesLayout = new QVBoxLayout(devicesBox);
    devicesLayout->setSpacing(8);
    devices_ = new QListWidget(devicesBox);
    devices_->setVisible(false);
    devices_->setMaximumHeight(120);
    devicesLayout->addWidget(devices_);
    auto* devRow = new QHBoxLayout;
    devRow->setContentsMargins(0, 0, 0, 0);
    forgetButton_ = new QPushButton(QStringLiteral("Forget…"), devicesBox);
    renameButton_ = new QPushButton(QStringLiteral("Rename…"), devicesBox);
    forgetButton_->setEnabled(false);
    renameButton_->setEnabled(false);
    devRow->addWidget(forgetButton_);
    devRow->addWidget(renameButton_);
    devRow->addStretch(1);
    devicesLayout->addLayout(devRow);
    outer->addWidget(devicesBox);
    auto selectedDevice = [this]() -> QString {
        auto* item = devices_->currentItem();
        return item ? item->text() : QString();
    };
    connect(devices_, &QListWidget::itemSelectionChanged, this, [this] {
        const bool has = devices_->currentRow() >= 0;
        forgetButton_->setEnabled(has);
        renameButton_->setEnabled(has);
    });
    connect(devices_, &QListWidget::itemDoubleClicked, this, [this](QListWidgetItem* item) {
        if (item) emit unpair(item->text());
    });
    connect(forgetButton_, &QPushButton::clicked, this, [this, selectedDevice] {
        const QString name = selectedDevice();
        if (!name.isEmpty()) emit unpair(name);
    });
    connect(renameButton_, &QPushButton::clicked, this, [this, selectedDevice] {
        const QString name = selectedDevice();
        if (!name.isEmpty()) emit renameDevice(name);
    });

    // --- this device card ---
    auto* selfBox = new QGroupBox(QStringLiteral("This device"), this);
    auto* selfLayout = new QHBoxLayout(selfBox);
    selfName_ = new QLabel(selfBox);
    selfLayout->addWidget(selfName_, 1);
    auto* selfRename = new QPushButton(QStringLiteral("Rename…"), selfBox);
    selfLayout->addWidget(selfRename);
    outer->addWidget(selfBox);
    connect(selfRename, &QPushButton::clicked, this, &HomeScreen::renameSelf);

    outer->addStretch(1);
}

void HomeScreen::setStatus(const QString& s) { status_->setText(s); }

void HomeScreen::setConnected(const QString& device) {
    connected_->setText(device.isEmpty() ? QString() : QStringLiteral("Connected: %1").arg(device));
    connected_->setVisible(!device.isEmpty());
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

void HomeScreen::setSelfName(const QString& name) {
    selfName_->setText(QStringLiteral("This device: %1").arg(name.isEmpty() ? QStringLiteral("?") : name));
}

void HomeScreen::setSharing(bool sharing) {
    serverButton_->setChecked(sharing);
    serverButton_->setText(sharing ? QStringLiteral("Stop sharing") : QStringLiteral("Start sharing"));
    pairButton_->setEnabled(sharing);
}

}  // namespace xn

