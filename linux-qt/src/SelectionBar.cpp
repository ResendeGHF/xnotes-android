#include "SelectionBar.h"

#include "CanvasWidget.h"
#include "Icons.h"

#include <QColorDialog>
#include <QGuiApplication>
#include <QClipboard>
#include <QGridLayout>
#include <QHBoxLayout>
#include <QIcon>
#include <QLabel>
#include <QMenu>
#include <QPainter>
#include <QPixmap>
#include <QSlider>
#include <QToolButton>
#include <QVBoxLayout>

#include <cmath>

QColor fromRgba(xn_rgba c) { return QColor(int(c >> 24), int((c >> 16) & 0xFF), int((c >> 8) & 0xFF), int(c & 0xFF)); }

xn_rgba toRgba(const QColor& c) {
    return (xn_rgba(c.red()) << 24) | (xn_rgba(c.green()) << 16) | (xn_rgba(c.blue()) << 8) | xn_rgba(c.alpha());
}

QIcon swatchIcon(const QColor& c, int size) {
    QPixmap pm(size * 2, size * 2);
    pm.setDevicePixelRatio(2);
    pm.fill(Qt::transparent);
    QPainter p(&pm);
    p.setRenderHint(QPainter::Antialiasing);
    p.setPen(QPen(QColor(128, 128, 128), 1));
    p.setBrush(c);
    p.drawEllipse(QRectF(1.5, 1.5, size - 3, size - 3));
    return QIcon(pm);
}

namespace {

QToolButton* actionButton(QWidget* parent, const char* icon, const QString& text, std::function<void()> fn) {
    auto* b = new QToolButton(parent);
    b->setIcon(xnIcon(QString::fromLatin1(icon)));
    b->setIconSize(QSize(20, 20));
    b->setText(text);
    b->setToolButtonStyle(Qt::ToolButtonIconOnly);
    b->setToolTip(text);
    b->setAutoRaise(true);
    b->setFocusPolicy(Qt::NoFocus);
    QObject::connect(b, &QToolButton::clicked, parent, std::move(fn));
    return b;
}

}  // namespace

// --- the bar -------------------------------------------------------------------------------------

SelectionBar::SelectionBar(CanvasWidget* canvas) : QFrame(canvas), canvas_(canvas) {
    setFrameShape(QFrame::StyledPanel);
    setAutoFillBackground(true);
    auto* row = new QHBoxLayout(this);
    row->setContentsMargins(4, 2, 4, 2);
    row->setSpacing(0);
    row->addWidget(actionButton(this, "trash", QStringLiteral("Delete"), [this] { if (auto* e = editor()) xn_editor_delete_selection(e); }));
    row->addWidget(actionButton(this, "cut", QStringLiteral("Cut"), [this] { if (auto* e = editor()) xn_editor_cut(e); }));
    row->addWidget(actionButton(this, "copy", QStringLiteral("Copy"), [this] { if (auto* e = editor()) xn_editor_copy(e); }));
    row->addWidget(actionButton(this, "front", QStringLiteral("Bring to front"), [this] { if (auto* e = editor()) xn_editor_bring_to_front(e); }));
    row->addWidget(actionButton(this, "duplicate", QStringLiteral("Duplicate"), [this] { if (auto* e = editor()) xn_editor_duplicate(e); }));
    auto* more = new QToolButton(this);
    more->setIcon(xnIcon(QStringLiteral("more")));
    more->setText(QStringLiteral("More"));
    more->setToolButtonStyle(Qt::ToolButtonIconOnly);
    more->setIconSize(QSize(20, 20));
    more->setToolTip(QStringLiteral("More"));
    more->setAutoRaise(true);
    more->setFocusPolicy(Qt::NoFocus);
    more->setPopupMode(QToolButton::InstantPopup);
    auto* menu = new QMenu(more);
    QAction* style = menu->addAction(xnIcon(QStringLiteral("sliders")), QStringLiteral("Edit style…"), this, &SelectionBar::showStyle);
    menu->addAction(xnIcon(QStringLiteral("lock")), QStringLiteral("Pin"), this, [this] { if (auto* e = editor()) xn_editor_lock_selection(e); });
    connect(menu, &QMenu::aboutToShow, this, [this, style] {
        style->setEnabled(editor() && xn_editor_selection_style(editor(), nullptr, nullptr) > 0);
    });
    more->setMenu(menu);
    row->addWidget(more);
    hide();
}

xn_editor* SelectionBar::editor() const { return canvas_->editor(); }

void SelectionBar::refresh() {
    const QRectF sel = canvas_->selectionRect();
    if (sel.isEmpty()) {
        hide();
        return;
    }
    adjustSize();
    const int gap = 10;
    const QSize size = sizeHint();
    // Above the selection, or below it when there is no room; always inside the canvas.
    double x = sel.center().x() - size.width() / 2.0;
    double y = sel.top() - size.height() - gap;
    if (y < 4) y = sel.bottom() + gap;
    x = std::clamp(x, 4.0, std::max(4.0, double(canvas_->width() - size.width() - 4)));
    y = std::clamp(y, 4.0, std::max(4.0, double(canvas_->height() - size.height() - 4)));
    move(int(x), int(y));
    resize(size);
    show();
    raise();
}

// --- the PDF text bar ----------------------------------------------------------------------------

PdfTextBar::PdfTextBar(CanvasWidget* canvas) : QFrame(canvas), canvas_(canvas) {
    setFrameShape(QFrame::StyledPanel);
    setAutoFillBackground(true);
    auto* row = new QHBoxLayout(this);
    row->setContentsMargins(4, 2, 4, 2);
    row->setSpacing(0);
    auto mark = [this](int type) { return [this, type] { if (auto* e = editor()) xn_editor_pdf_mark(e, type); }; };
    row->addWidget(actionButton(this, "ic_stroke_highlighter", QStringLiteral("Highlight"), mark(XN_MARK_HIGHLIGHT)));
    row->addWidget(actionButton(this, "underline", QStringLiteral("Underline"), mark(XN_MARK_UNDERLINE)));
    row->addWidget(actionButton(this, "strikethrough", QStringLiteral("Strikeout"), mark(XN_MARK_STRIKEOUT)));
    row->addWidget(actionButton(this, "text", QStringLiteral("Squiggly underline"), mark(XN_MARK_SQUIGGLY)));
    row->addWidget(actionButton(this, "link", QStringLiteral("Link to note…"), [this] { emit linkRequested(); }));
    row->addWidget(actionButton(this, "copy", QStringLiteral("Copy"), [this] {
        auto* e = editor();
        if (!e) return;
        char* text = xn_editor_pdf_selected_text(e);
        if (!text) return;
        QGuiApplication::clipboard()->setText(QString::fromUtf8(text));
        xn_free_string(text);
    }));
    hide();
}

xn_editor* PdfTextBar::editor() const { return canvas_->editor(); }

void PdfTextBar::refresh() {
    double xywh[4] = {0, 0, 0, 0};
    if (!editor() || xn_editor_pdf_has_selection(editor()) == 0 || xn_editor_pdf_selection_rect(editor(), xywh) == 0) {
        hide();
        return;
    }
    const QRectF sel(xywh[0], xywh[1], xywh[2], xywh[3]);
    adjustSize();
    const int gap = 10;
    const QSize size = sizeHint();
    double x = sel.center().x() - size.width() / 2.0;
    double y = sel.top() - size.height() - gap;
    if (y < 4) y = sel.bottom() + gap;
    x = std::clamp(x, 4.0, std::max(4.0, double(canvas_->width() - size.width() - 4)));
    y = std::clamp(y, 4.0, std::max(4.0, double(canvas_->height() - size.height() - 4)));
    move(int(x), int(y));
    resize(size);
    show();
    raise();
}

void SelectionBar::showStyle() {
    xn_rgba color = 0;
    double width = 0;
    if (!editor() || xn_editor_selection_style(editor(), &color, &width) == 0) return;
    auto* popup = new StylePopup(editor(), swatches_, color, width, this);
    popup->setAttribute(Qt::WA_DeleteOnClose);
    popup->move(mapToGlobal(QPoint(0, height() + 4)));
    popup->show();
}

// --- the style popup -----------------------------------------------------------------------------

StylePopup::StylePopup(xn_editor* editor, const QList<QColor>& swatches, xn_rgba color, double width, QWidget* parent)
    : QFrame(parent, Qt::Popup), editor_(editor) {
    setFrameShape(QFrame::StyledPanel);
    auto* col = new QVBoxLayout(this);
    col->addWidget(new QLabel(QStringLiteral("Color"), this));
    auto* grid = new QGridLayout;
    grid->setSpacing(4);
    int i = 0;
    for (const QColor& c : swatches) {
        auto* b = new QToolButton(this);
        b->setIcon(swatchIcon(c, 22));
        b->setIconSize(QSize(22, 22));
        b->setAutoRaise(true);
        b->setCheckable(true);
        b->setChecked(toRgba(c) == color);
        connect(b, &QToolButton::clicked, this, [this, c] { xn_editor_restyle_selection(editor_, 1, toRgba(c), 0, 0, 0); });
        grid->addWidget(b, i / 6, i % 6);
        i++;
    }
    auto* other = new QToolButton(this);
    other->setText(QStringLiteral("…"));
    other->setToolTip(QStringLiteral("Custom color"));
    connect(other, &QToolButton::clicked, this, [this, color] {
        const QColor c = QColorDialog::getColor(fromRgba(color), this, QStringLiteral("Color"));
        if (c.isValid()) xn_editor_restyle_selection(editor_, 1, toRgba(c), 0, 0, 0);
    });
    grid->addWidget(other, i / 6, i % 6);
    col->addLayout(grid);

    widthLabel_ = new QLabel(this);
    col->addWidget(widthLabel_);
    width_ = new QSlider(Qt::Horizontal, this);
    // Half-pixel steps; the scale is the square root so thin lines get most of the travel.
    auto toSlider = [](double w) { return int(std::lround(std::sqrt(w) * 100)); };
    width_->setRange(toSlider(XN_STYLE_MIN_WIDTH), toSlider(XN_STYLE_MAX_WIDTH));
    width_->setValue(toSlider(width));
    width_->setMinimumWidth(220);
    auto label = [this](double w) { widthLabel_->setText(QStringLiteral("Width: %1").arg(w, 0, 'f', 1)); };
    label(width);
    connect(width_, &QSlider::valueChanged, this, [this, label](int v) {
        const double w = std::round(std::pow(v / 100.0, 2) * 2) / 2;
        label(w);
        xn_editor_restyle_selection(editor_, 0, 0, 1, w, 1);  // live, one undo step on release
    });
    connect(width_, &QSlider::sliderReleased, this, [this] { xn_editor_restyle_selection(editor_, 0, 0, 0, 0, 0); });
    col->addWidget(width_);
}

void StylePopup::hideEvent(QHideEvent* e) {
    xn_editor_restyle_selection(editor_, 0, 0, 0, 0, 0);  // settle a pending preview
    QFrame::hideEvent(e);
}
