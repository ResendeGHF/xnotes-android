#include "MainWindow.h"

#include "CanvasWidget.h"
#include "BrowsePage.h"
#include "CompanionServer.h"
#include "HomeScreen.h"
#include "Recents.h"
#include "SharedPage.h"
#include "Icons.h"
#include "NoteFinder.h"
#include "PageStylePopup.h"
#include "PagesPanel.h"
#include "PenStylePopup.h"
#include "PdfExport.h"
#include "SelectionBar.h"

#include <QApplication>
#include <QCloseEvent>
#include <QClipboard>
#include <QColorDialog>
#include <QDateTime>
#include <QDir>
#include <QDockWidget>
#include <QGridLayout>
#include <QInputDialog>
#include <QPushButton>
#include <QScrollBar>
#include <QImageReader>
#include <QMimeData>
#include <QUuid>
#include <QCheckBox>
#include <QComboBox>
#include <QSlider>
#include <QSpinBox>
#include <QMenu>
#include <QToolButton>
#include <QFileDialog>
#include <QFileInfo>
#include <QLabel>
#include <QLineEdit>
#include <QMessageBox>
#include <QPainter>
#include <QStackedWidget>
#include <QPixmap>
#include <QStatusBar>
#include <QTimer>
#include <QToolBar>

namespace {

struct ToolEntry {
    const char* id;
    const char* label;
    const char* key;
    const char* icon;
};

const ToolEntry kTools[] = {
    {"pan", "Pan", "1", "pan"},
    {"pen", "Pen", "2", "ic_stroke_regular"},
    {"highlighter", "Highlighter", "3", "ic_stroke_highlighter"},
    {"eraser", "Eraser", "4", "eraser"},
    {"lasso", "Lasso", "5", "lasso"},
    {"select", "Selection", "6", "select"},
    {"insert_space", "Insert space", "", "insertSpace"},
    {"shape", "Shape", "7", "shape"},
    {"text_box", "Text", "8", "textBox"},
    {"markup", "Markup", "9", "ic_stroke_highlighter"},
};

/* Kinds of pen (the Android stroke tools), drawn by the pen button. */
const ToolEntry kPens[] = {
    {"pen", "Pen", "", "ic_stroke_regular"},
    {"dashed", "Dashed", "", "ic_stroke_dashed"},
    {"calligraphy", "Calligraphy", "", "ic_stroke_calligraphy"},
    {"speed", "Speed", "", "ic_stroke_speed"},
    {"taper", "Tapered", "", "ic_stroke_taper"},
};

const ToolEntry* penEntry(const QByteArray& kind) {
    for (const ToolEntry& p : kPens)
        if (kind == p.id) return &p;
    return nullptr;
}

/* Pens whose width can follow the pressure. */
bool hasPressure(const QByteArray& tool) { return tool == "pen" || tool == "calligraphy" || tool == "speed" || tool == "taper"; }

struct FaceEntry {
    const char* id;
    const char* label;
};

const FaceEntry kFaces[] = {{"mono", "Monospace"}, {"sans", "Sans serif"}, {"serif", "Serif"}, {"hand", "Handwriting"}};

struct ShapeEntry {
    const char* id;
    const char* label;
    const char* icon;
};

const ShapeEntry kShapes[] = {
    {"line", "Line", "shapeLine"}, {"arrow", "Arrow", "shapeArrow"}, {"rectangle", "Rectangle", "shapeRect"},
    {"ellipse", "Ellipse", "shapeEllipse"}, {"circle", "Circle", "shapeCircle"}, {"triangle", "Triangle", "shapeTriangle"},
};

/* The app's ink presets (InkPalette) plus black and blue for light paper. */
const QList<QColor> kSwatches = {
    QColor(0, 230, 118), QColor(236, 236, 236), QColor(255, 92, 92), QColor(255, 199, 64),
    QColor(88, 196, 255), QColor(199, 134, 255), QColor(128, 128, 128), QColor(28, 28, 28), QColor(33, 150, 243),
};

/* Tools whose ink width the toolbar sets. */
/* The width slider's range per tool, as on Android (ToolDefaults.widthRange); the eraser's is its radius. */
std::pair<double, double> widthRange(const QByteArray& tool) {
    if (tool == "highlighter") return {4.0, 40.0};
    if (tool == "eraser") return {1.0, 80.0};
    return {1.0, 20.0};
}

/* Tools whose ink width (the eraser: its radius) the toolbar sets. */
bool hasWidth(const QByteArray& tool) { return penEntry(tool) || tool == "highlighter" || tool == "shape" || tool == "eraser"; }

QString takeError(char* error) {
    QString message = error ? QString::fromUtf8(error) : QStringLiteral("unknown error");
    xn_free_string(error);
    return message;
}

}  // namespace

MainWindow::MainWindow() {
    // The canvas with scroll bars beside and under it (device pixels, as the core counts).
    auto* central = new QWidget(this);
    auto* grid = new QGridLayout(central);
    grid->setContentsMargins(0, 0, 0, 0);
    grid->setSpacing(0);
    canvas_ = new CanvasWidget(central);
    vbar_ = new QScrollBar(Qt::Vertical, central);
    hbar_ = new QScrollBar(Qt::Horizontal, central);
    grid->addWidget(canvas_, 0, 0);
    grid->addWidget(vbar_, 0, 1);
    grid->addWidget(hbar_, 1, 0);
    // Sidebar navigation: Home, Browse, Shared files. The canvas is page 0.
    auto* nav = new QToolBar(tr("Navigate"), this);
    nav->setOrientation(Qt::Vertical);
    nav->setMovable(false);
    nav->setToolButtonStyle(Qt::ToolButtonTextUnderIcon);
    navHome_ = nav->addAction(xnIcon(QStringLiteral("home")), QStringLiteral("Home"));
    navBrowse_ = nav->addAction(xnIcon(QStringLiteral("folder")), QStringLiteral("Browse"));
    navShared_ = nav->addAction(xnIcon(QStringLiteral("share")), QStringLiteral("Shared files"));
    for (QAction* a : {navHome_, navBrowse_, navShared_}) a->setCheckable(true);
    connect(navHome_, &QAction::triggered, this, [this] { showHome(); });
    connect(navBrowse_, &QAction::triggered, this, [this] { showPage(2); });
    connect(navShared_, &QAction::triggered, this, [this] { showPage(3); });

    stack_ = new QStackedWidget(this);
    stack_->addWidget(central);
    home_ = new xn::HomeScreen(stack_);
    stack_->addWidget(home_);
    browse_ = new xn::BrowsePage(stack_);
    stack_->addWidget(browse_);
    shared_ = new xn::SharedPage(stack_);
    stack_->addWidget(shared_);
    auto* middle = new QWidget(this);
    auto* row = new QHBoxLayout(middle);
    row->setContentsMargins(0, 0, 0, 0);
    row->setSpacing(0);
    row->addWidget(nav);
    row->addWidget(stack_, 1);
    setCentralWidget(middle);
    auto scrolled = [this] {
        if (syncingBars_ || !canvas_->editor()) return;
        xn_editor_scroll_to(canvas_->editor(), hbar_->value(), vbar_->value());
    };
    connect(vbar_, &QScrollBar::valueChanged, this, scrolled);
    connect(hbar_, &QScrollBar::valueChanged, this, scrolled);
    // Page thumbnails on the left (the Android side panel), shown with notes, not canvases.
    pages_ = new PagesPanel(this);
    pagesDock_ = new QDockWidget(QStringLiteral("Pages"), this);
    pagesDock_->setObjectName(QStringLiteral("pagesDock"));
    pagesDock_->setFeatures(QDockWidget::NoDockWidgetFeatures);
    pagesDock_->setTitleBarWidget(new QWidget(pagesDock_));
    pagesDock_->setWidget(pages_);
    addDockWidget(Qt::LeftDockWidgetArea, pagesDock_);
    buildUi();
    connect(canvas_, &CanvasWidget::contentChanged, pages_, &PagesPanel::contentChanged);
    connect(canvas_, &CanvasWidget::viewChanged, pages_, &PagesPanel::viewChanged);
    connect(canvas_, &CanvasWidget::contentChanged, this, &MainWindow::refresh);
    connect(canvas_, &CanvasWidget::viewChanged, this, &MainWindow::refreshView);
    connect(canvas_, &CanvasWidget::notice, this, [this](int code) {
        statusBar()->showMessage(code == XN_NOTICE_KEEP_ONE_PAGE ? QStringLiteral("The note must keep at least one page")
                                 : code == XN_NOTICE_PAGE_ALREADY_EMPTY ? QStringLiteral("Page is already empty")
                                                                        : QString(), 4000);
    });
    // Input and speed, for checking a tablet: whether Qt reports it as a pen with pressure.
    inputLabel_ = new QLabel(this);
    statusBar()->addPermanentWidget(inputLabel_);
    auto* stats = new QTimer(this);
    connect(stats, &QTimer::timeout, this, [this] {
        const QString input = canvas_->lastInput();
        const double interval = canvas_->frameIntervalMs();
        const int b = canvas_->penButtons();
        const QString buttons = b == 0 ? QString() : b == 1 ? QStringLiteral("pen button 1   ") : b == 2 ? QStringLiteral("pen button 2   ") : QStringLiteral("pen buttons 1+2   ");
        inputLabel_->setText(buttons + (input.isEmpty() ? QString() : QStringLiteral("input: %1, pressure %2   ").arg(input).arg(canvas_->lastPressure(), 0, 'f', 2)) +
                             QStringLiteral("%1: frame %2 ms").arg(canvas_->render() == CanvasWidget::Render::Gl ? QStringLiteral("GPU") : QStringLiteral("CPU"))
                                 .arg(canvas_->lastFrameMs(), 0, 'f', 1) +
                             (interval > 0 ? QStringLiteral(", %1 fps").arg(int(std::lround(1000 / interval))) : QString()));
    });
    stats->start(250);
    setupCompanion();
    resize(1200, 900);
}

MainWindow::~MainWindow() {
    pages_->setDocument(nullptr, nullptr);
    canvas_->setNote(nullptr);
    if (note_) xn_note_close(note_);
}

void MainWindow::buildUi() {
    QToolBar* bar = addToolBar(QStringLiteral("Main"));
    bar->setMovable(false);
    // Back to Home: the canvas is entered from Browse/Shared, so there must be
    // a way back without picking the sidebar. Lives with the editor bars, which
    // are only visible on the canvas (see showPage()).
    QAction* back = bar->addAction(xnIcon(QStringLiteral("prev")), QStringLiteral("Back"));
    back->setShortcut(QKeySequence(Qt::ALT | Qt::Key_Left));
    back->setToolTip(QStringLiteral("Back to Home (Alt+Left)"));
    connect(back, &QAction::triggered, this, &MainWindow::showHome);
    pagesAction_ = bar->addAction(xnIcon(QStringLiteral("sidebar")), QStringLiteral("Pages"));
    pagesAction_->setCheckable(true);
    pagesAction_->setChecked(true);
    pagesAction_->setShortcut(QKeySequence(Qt::Key_F9));
    connect(pagesAction_, &QAction::toggled, this, [this](bool on) {
        pagesDock_->setVisible(on && !(note_ && xn_note_is_canvas(note_)));
    });
    bar->addSeparator();
    auto* create = new QToolButton(this);
    create->setText(QStringLiteral("New"));
    create->setIcon(xnIcon(QStringLiteral("file")));
    create->setToolTip(QStringLiteral("New"));
    create->setPopupMode(QToolButton::InstantPopup);
    auto* createMenu = new QMenu(create);
    createMenu->addAction(xnIcon(QStringLiteral("file")), QStringLiteral("Note"), QKeySequence::New, this, [this] { if (confirmDiscard()) newNote(); });
    createMenu->addAction(xnIcon(QStringLiteral("canvas")), QStringLiteral("Canvas"), QKeySequence(Qt::CTRL | Qt::SHIFT | Qt::Key_N), this, [this] { if (confirmDiscard()) newCanvas(); });
    create->setMenu(createMenu);
    bar->addWidget(create);
    bar->addAction(QStringLiteral("Open…"), QKeySequence::Open, this, &MainWindow::chooseOpen);
    bar->addAction(QStringLiteral("Save"), QKeySequence::Save, this, [this] { save(false); });
    bar->addAction(QStringLiteral("Save As…"), QKeySequence::SaveAs, this, [this] { save(true); });
    exportAction_ = bar->addAction(QStringLiteral("Export to PDF…"), QKeySequence(Qt::CTRL | Qt::Key_E), this, &MainWindow::exportPdf);
    bar->addSeparator();

    undo_ = bar->addAction(QStringLiteral("↶"), QKeySequence::Undo, this, [this] { if (canvas_->editor()) xn_editor_undo(canvas_->editor()); });
    undo_->setToolTip(QStringLiteral("Undo (Ctrl+Z)"));
    redo_ = bar->addAction(QStringLiteral("↷"), this, [this] { if (canvas_->editor()) xn_editor_redo(canvas_->editor()); });
    redo_->setShortcuts({QKeySequence(Qt::CTRL | Qt::SHIFT | Qt::Key_Z), QKeySequence(Qt::CTRL | Qt::Key_Y)});
    redo_->setToolTip(QStringLiteral("Redo (Ctrl+Shift+Z)"));
    bar->addSeparator();

    pageActions_ << bar->addAction(QStringLiteral("←"), QKeySequence(Qt::Key_PageUp), this, [this] {
        if (auto* e = canvas_->editor()) xn_editor_go_to_page(e, std::max(0, xn_editor_current_page(e) - 1));
    });
    pageLabel_ = new QLabel(this);
    pageLabel_->setMinimumWidth(60);
    pageLabel_->setAlignment(Qt::AlignCenter);
    pageActions_ << bar->addWidget(pageLabel_);
    pageActions_ << bar->addAction(QStringLiteral("→"), QKeySequence(Qt::Key_PageDown), this, [this] {
        if (auto* e = canvas_->editor()) xn_editor_go_to_page(e, std::min(xn_note_page_count(note_) - 1, xn_editor_current_page(e) + 1));
    });
    bar->addAction(QStringLiteral("−"), QKeySequence::ZoomOut, this, [this] { if (auto* e = canvas_->editor()) xn_editor_zoom_step(e, 0); });
    zoomLabel_ = new QLabel(this);
    zoomLabel_->setMinimumWidth(50);
    zoomLabel_->setAlignment(Qt::AlignCenter);
    bar->addWidget(zoomLabel_);
    bar->addAction(QStringLiteral("+"), QKeySequence(Qt::CTRL | Qt::Key_Equal), this, [this] { if (auto* e = canvas_->editor()) xn_editor_zoom_step(e, 1); });
    fitAction_ = bar->addAction(QStringLiteral("Fit width"), this, [this] { if (auto* e = canvas_->editor()) xn_editor_fit_width(e); });
    pageActions_ << bar->addAction(QStringLiteral("+ Page"), this, [this] { if (auto* e = canvas_->editor()) xn_editor_add_page(e); });
    pageActions_ << bar->addAction(QStringLiteral("- Page"), this, [this] {
        if (!canvas_->editor()) return;
        if (QMessageBox::question(this, QStringLiteral("Delete page"), QStringLiteral("Delete the current page?")) == QMessageBox::Yes)
            xn_editor_delete_current_page(canvas_->editor());
    });
    bar->addSeparator();
    bar->addAction(QStringLiteral("Background…"), this, [this] {
        if (!canvas_->editor()) return;
        if (!pageStyle_) {
            pageStyle_ = new PageStylePopup(canvas_->editor(), xn_note_is_canvas(note_) != 0, this);
            pageStyle_->setAttribute(Qt::WA_DeleteOnClose);
        }
        pageStyle_->show();
        pageStyle_->raise();
    });
    QAction* dark = bar->addAction(QStringLiteral("Dark"));
    dark->setCheckable(true);
    dark->setChecked(dark_);
    connect(dark, &QAction::toggled, this, [this](bool on) {
        dark_ = on;
        canvas_->setDark(on);
        pages_->setPaper(paper());
    });

    // Second row: tools and their style.
    addToolBarBreak();
    QToolBar* tools = addToolBar(QStringLiteral("Tools"));
    editorBars_ << bar << tools;
    tools->setMovable(false);
    tools_ = new QActionGroup(this);
    for (const ToolEntry& t : kTools) {
        QAction* a = tools->addAction(QString::fromUtf8(t.label));
        a->setCheckable(true);
        a->setShortcut(QKeySequence(QString::fromLatin1(t.key)));
        a->setData(QByteArray(t.id));
        tools_->addAction(a);
        a->setIcon(xnIcon(QString::fromLatin1(t.icon)));
        if (qstrcmp(t.id, "pen") == 0) a->setChecked(true);
        if (qstrcmp(t.id, "pen") == 0) {
            // The pen button draws with the kind of pen picked on its menu.
            penAction_ = a;
            auto* menu = new QMenu(this);
            auto* kinds = new QActionGroup(menu);
            for (const ToolEntry& k : kPens) {
                QAction* ka = menu->addAction(xnIcon(QString::fromLatin1(k.icon)), QString::fromUtf8(k.label));
                ka->setCheckable(true);
                ka->setChecked(qstrcmp(k.id, "pen") == 0);
                kinds->addAction(ka);
                penKinds_.insert(k.id, ka);
                connect(ka, &QAction::triggered, this, [this, k] {
                    setPenKind(k.id);
                    penAction_->trigger();
                });
            }
            menu->addSeparator();
            menu->addAction(xnIcon(QStringLiteral("sliders")), QStringLiteral("Pen settings…"), this, &MainWindow::showPenStyle);
            a->setMenu(menu);
            if (auto* button = qobject_cast<QToolButton*>(tools->widgetForAction(a))) button->setPopupMode(QToolButton::MenuButtonPopup);
        }
        if (qstrcmp(t.id, "markup") == 0) {
            // The markup button picks select or the mark a drag makes.
            static const ToolEntry kMarkupModes[] = {
                {"select", "Select", "", ""},
                {"highlight", "Highlight", "", ""},
                {"underline", "Underline", "", ""},
                {"strikeout", "Strikeout", "", ""},
                {"squiggly", "Squiggly", "", ""},
            };
            auto* menu = new QMenu(this);
            auto* modes = new QActionGroup(menu);
            for (const ToolEntry& m : kMarkupModes) {
                QAction* ma = menu->addAction(QString::fromUtf8(m.label));
                ma->setCheckable(true);
                ma->setChecked(markupMode_ == m.id);
                ma->setData(QByteArray(m.id));
                modes->addAction(ma);
                markupModes_.insert(m.id, ma);
                connect(ma, &QAction::triggered, this, [this, a, m] {
                    markupMode_ = m.id;
                    applyMarkupMode();
                    a->trigger();
                });
            }
            a->setMenu(menu);
            if (auto* button = qobject_cast<QToolButton*>(tools->widgetForAction(a))) button->setPopupMode(QToolButton::MenuButtonPopup);
        }
        if (qstrcmp(t.id, "shape") == 0) {
            // The shape tool's button also opens what it draws.
            auto* menu = new QMenu(this);
            auto* kinds = new QActionGroup(menu);
            for (const ShapeEntry& k : kShapes) {
                QAction* ka = menu->addAction(xnIcon(QString::fromLatin1(k.icon)), QString::fromUtf8(k.label));
                ka->setCheckable(true);
                ka->setChecked(shapeKind_ == k.id);
                kinds->addAction(ka);
                connect(ka, &QAction::triggered, this, [this, a, k] {
                    shapeKind_ = k.id;
                    applyShape();
                    a->trigger();
                });
            }
            menu->addSeparator();
            QAction* fill = menu->addAction(QStringLiteral("Fill"));
            fill->setCheckable(true);
            connect(fill, &QAction::toggled, this, [this](bool on) { shapeFill_ = on; applyShape(); });
            QAction* dashed = menu->addAction(QStringLiteral("Dashed"));
            dashed->setCheckable(true);
            connect(dashed, &QAction::toggled, this, [this](bool on) { shapeDashed_ = on; applyShape(); });
            a->setMenu(menu);
            if (auto* button = qobject_cast<QToolButton*>(tools->widgetForAction(a))) button->setPopupMode(QToolButton::MenuButtonPopup);
        }
    }
    connect(tools_, &QActionGroup::triggered, this, [this](QAction* a) {
        canvas_->setTool(a->data().toByteArray().constData());
        syncWidth();
    });
    tools->addSeparator();

    widthLabel_ = new QLabel(this);
    widthActions_ << tools->addWidget(widthLabel_);
    // A slider in half-pixel steps (its value is twice the width), with the width beside it.
    width_ = new QSlider(Qt::Horizontal, this);
    width_->setFixedWidth(160);
    width_->setFocusPolicy(Qt::ClickFocus);
    widthValue_ = new QLabel(this);
    widthValue_->setMinimumWidth(36);
    connect(width_, &QSlider::valueChanged, this, [this](int v) {
        const double w = v / 2.0;
        widthValue_->setText(QString::number(w, 'f', 1));
        const QByteArray tool = currentTool();
        if (!hasWidth(tool) || syncingWidth_) return;
        widths_[tool] = w;
        if (tool == "shape") applyShape();
        else if (auto* e = canvas_->editor()) xn_editor_set_tool_width(e, tool.constData(), w);
    });
    widthActions_ << tools->addWidget(width_) << tools->addWidget(widthValue_);

    // Pressure: off, a line keeps its width however hard the pen presses (every pen at once).
    pressure_ = new QCheckBox(QStringLiteral("Pressure"), this);
    pressure_->setChecked(true);
    pressure_->setFocusPolicy(Qt::ClickFocus);
    pressure_->setToolTip(QStringLiteral("Line thickness follows pen pressure. Turn off for even lines"));
    connect(pressure_, &QCheckBox::toggled, this, &MainWindow::setPressure);
    pressureActions_ << tools->addWidget(pressure_);

    // The eraser: whole strokes or just the ink under it.
    eraseMode_ = new QComboBox(this);
    eraseMode_->addItem(QStringLiteral("Whole stroke"), int(XN_ERASE_STROKE));
    eraseMode_->addItem(QStringLiteral("Area"), int(XN_ERASE_AREA));
    eraseMode_->setToolTip(QStringLiteral("The eraser removes touched strokes whole or only what is under it"));
    eraseMode_->setFocusPolicy(Qt::ClickFocus);
    connect(eraseMode_, &QComboBox::currentIndexChanged, this, [this] {
        if (auto* e = canvas_->editor()) xn_editor_set_erase_mode(e, eraseMode_->currentData().toInt());
    });
    eraserActions_ << tools->addWidget(eraseMode_);

    // Text box style: the box being edited or selected, and the next one.
    face_ = new QComboBox(this);
    for (const FaceEntry& f : kFaces) face_->addItem(QString::fromUtf8(f.label), QByteArray(f.id));
    face_->setFocusPolicy(Qt::ClickFocus);
    connect(face_, &QComboBox::currentIndexChanged, this, [this] {
        if (auto* e = canvas_->editor()) xn_editor_set_text_face(e, face_->currentData().toByteArray().constData());
    });
    textSize_ = new QSpinBox(this);
    textSize_->setRange(6, 96);
    textSize_->setValue(13);
    textSize_->setSuffix(QStringLiteral(" pt"));
    textSize_->setFocusPolicy(Qt::ClickFocus);
    connect(textSize_, &QSpinBox::valueChanged, this, [this](int pt) { if (auto* e = canvas_->editor()) xn_editor_set_text_size(e, pt); });
    textActions_ << tools->addWidget(face_) << tools->addWidget(textSize_);
    tools->addSeparator();

    for (const QColor& c : kSwatches) {
        QAction* a = tools->addAction(swatchIcon(c), QString());
        a->setToolTip(c.name());
        connect(a, &QAction::triggered, this, [this, c] { setInk(c); });
    }
    colorAction_ = tools->addAction(QStringLiteral("Color…"), this, &MainWindow::chooseColor);
    tools->addSeparator();
    tools->addAction(QStringLiteral("Picture…"), this, &MainWindow::chooseImage);
    tools->addSeparator();
    tools->addAction(xnIcon(QStringLiteral("search")), QStringLiteral("Search"), QKeySequence::Find, this,
                            [this] { toggleFind(findBar_ && !findBar_->isVisible()); });
    tools->addAction(xnIcon(QStringLiteral("pin")), QStringLiteral("Sticky note"), QKeySequence(QStringLiteral("T")), this, [this] {
        if (auto* e = canvas_->editor()) {
            const QPointF dev = canvas_->toDevice(QPointF(canvas_->width() / 2.0, canvas_->height() / 3.0));
            xn_editor_insert_sticky(e, dev.x(), dev.y());
        }
    });

    // Find bar: in-note search, over the canvas like the selection bar.
    findBar_ = addToolBar(QStringLiteral("Find"));
    findBar_->setMovable(false);
    editorBars_ << findBar_;
    findField_ = new QLineEdit(findBar_);
    findField_->setPlaceholderText(QStringLiteral("Search in note"));
    findField_->setClearButtonEnabled(true);
    findField_->setMinimumWidth(220);
    findBar_->addWidget(findField_);
    connect(findField_, &QLineEdit::textChanged, this, &MainWindow::runFind);
    findCase_ = findBar_->addAction(QStringLiteral("Aa"));
    findCase_->setCheckable(true);
    findCase_->setToolTip(QStringLiteral("Match case"));
    connect(findCase_, &QAction::toggled, this, &MainWindow::runFind);
    findWords_ = findBar_->addAction(QStringLiteral("ab|"));
    findWords_->setCheckable(true);
    findWords_->setToolTip(QStringLiteral("Whole words"));
    connect(findWords_, &QAction::toggled, this, &MainWindow::runFind);
    findCount_ = new QLabel(findBar_);
    findCount_->setMinimumWidth(64);
    findBar_->addWidget(findCount_);
    findBar_->addAction(xnIcon(QStringLiteral("arrowUp")), QStringLiteral("Previous match"), this, [this] {
        if (auto* e = canvas_->editor()) xn_editor_search_prev(e);
    });
    findBar_->addAction(xnIcon(QStringLiteral("arrowDown")), QStringLiteral("Next match"), this, [this] {
        if (auto* e = canvas_->editor()) xn_editor_search_next(e);
    });
    findBar_->addAction(xnIcon(QStringLiteral("close")), QStringLiteral("Close search"), this, [this] { toggleFind(false); });
    findPoll_ = new QTimer(this);
    findPoll_->setInterval(200);
    connect(findPoll_, &QTimer::timeout, this, &MainWindow::refreshFind);
    findBar_->hide();
    colorAction_->setToolTip(QStringLiteral("Current color; click to choose another"));

    // Icons from the Android app's set; the words stay as tooltips (and as the actions' names).
    const QHash<QString, QString> icons = {
        {QStringLiteral("Open…"), QStringLiteral("folder")},
        {QStringLiteral("Save"), QStringLiteral("save")},
        {QStringLiteral("Save As…"), QStringLiteral("download")},
        {QStringLiteral("Export to PDF…"), QStringLiteral("exportDoc")},
        {QStringLiteral("↶"), QStringLiteral("undo")},
        {QStringLiteral("↷"), QStringLiteral("redo")},
        {QStringLiteral("←"), QStringLiteral("prev")},
        {QStringLiteral("→"), QStringLiteral("next")},
        {QStringLiteral("−"), QStringLiteral("zoomOut")},
        {QStringLiteral("+"), QStringLiteral("zoomIn")},
        {QStringLiteral("Fit width"), QStringLiteral("fit")},
        {QStringLiteral("+ Page"), QStringLiteral("plus")},
        {QStringLiteral("- Page"), QStringLiteral("minus")},
        {QStringLiteral("Background…"), QStringLiteral("sliders")},
        {QStringLiteral("Dark"), QStringLiteral("view")},
        {QStringLiteral("Picture…"), QStringLiteral("image")},
        {QStringLiteral("Pages"), QStringLiteral("sidebar")},
    };
    const QHash<QString, QString> tips = {
        {QStringLiteral("↶"), QStringLiteral("Undo")}, {QStringLiteral("↷"), QStringLiteral("Redo")},
        {QStringLiteral("←"), QStringLiteral("Previous page")}, {QStringLiteral("→"), QStringLiteral("Next page")},
        {QStringLiteral("−"), QStringLiteral("Zoom out")}, {QStringLiteral("+"), QStringLiteral("Zoom in")},
        {QStringLiteral("+ Page"), QStringLiteral("Add page")}, {QStringLiteral("- Page"), QStringLiteral("Delete page")},
        {QStringLiteral("Dark"), QStringLiteral("Dark paper")},
        {QStringLiteral("Pages"), QStringLiteral("Pages panel")},
    };
    for (QToolBar* tb : {bar, tools}) {
        tb->setToolButtonStyle(Qt::ToolButtonIconOnly);
        tb->setIconSize(QSize(22, 22));
        for (QAction* a : tb->actions()) {
            if (auto it = icons.constFind(a->text()); it != icons.constEnd()) a->setIcon(xnIcon(*it));
            const QString tip = tips.value(a->text(), a->text());
            a->setToolTip(a->shortcut().isEmpty() ? tip : QStringLiteral("%1 (%2)").arg(tip, a->shortcut().toString(QKeySequence::NativeText)));
        }
    }

    selectionBar_ = new SelectionBar(canvas_);
    selectionBar_->setSwatches(kSwatches);
    connect(canvas_, &CanvasWidget::selectionMenu, this, [this](bool shown, QRectF) { selectionBar_->onMenu(shown); });
    connect(canvas_, &CanvasWidget::viewChanged, selectionBar_, &SelectionBar::refresh);
    connect(canvas_, &CanvasWidget::contentChanged, selectionBar_, &SelectionBar::refresh);
    textBar_ = new PdfTextBar(canvas_);
    connect(canvas_, &CanvasWidget::selectionMenu, this, [this](bool shown, QRectF) { textBar_->onMenu(shown); });
    connect(textBar_, &PdfTextBar::linkRequested, this, &MainWindow::linkFromSelection);
    connect(canvas_, &CanvasWidget::viewChanged, textBar_, &PdfTextBar::refresh);
    connect(canvas_, &CanvasWidget::contentChanged, textBar_, &PdfTextBar::refresh);
    connect(canvas_, &CanvasWidget::contextMenu, this, &MainWindow::showContextMenu);
    connect(canvas_, &CanvasWidget::toolChanged, this, [this](const QString& id) {
        if (installing_) return;
        if (penEntry(id.toUtf8())) setPenKind(id.toUtf8());
        for (QAction* a : tools_->actions())
            if (a->data().toByteArray() == id.toUtf8()) a->setChecked(true);
        syncWidth();
    });

    // Canvas commands without buttons.
    auto bind = [this](const QKeySequence& key, auto fn) {
        auto* a = new QAction(this);
        a->setShortcut(key);
        connect(a, &QAction::triggered, this, fn);
        addAction(a);
    };
    bind(QKeySequence(Qt::Key_Escape), [this] { if (canvas_->editor()) xn_editor_escape(canvas_->editor()); });
    bind(QKeySequence(Qt::Key_Delete), [this] { if (canvas_->editor()) xn_editor_delete_selection(canvas_->editor()); });

    bind(QKeySequence::Copy, [this] { if (canvas_->editor()) xn_editor_copy(canvas_->editor()); clipboardIsNewer_ = false; });
    bind(QKeySequence::Cut, [this] { if (canvas_->editor()) xn_editor_cut(canvas_->editor()); clipboardIsNewer_ = false; });
    connect(QApplication::clipboard(), &QClipboard::dataChanged, this, [this] { clipboardIsNewer_ = true; });
    connect(canvas_, &CanvasWidget::filesDropped, this, [this](const QStringList& files, QPointF at) {
        for (const QString& f : files) insertImageFile(f, canvas_->toDevice(at), true);
    });
    bind(QKeySequence::Paste, [this] { pasteAt(canvas_->mapFromGlobal(QCursor::pos())); });
    bind(QKeySequence(Qt::CTRL | Qt::Key_D), [this] { if (canvas_->editor()) xn_editor_duplicate(canvas_->editor()); });
    bind(QKeySequence::SelectAll, [this] { selectAll(); });
    bind(QKeySequence::Quit, [this] { close(); });
    setInk(kSwatches.first());
    syncWidth();
}

QByteArray MainWindow::currentTool() const {
    QAction* a = tools_->checkedAction();
    return a ? a->data().toByteArray() : QByteArray();
}

void MainWindow::syncWidth() {
    const QByteArray tool = currentTool();
    for (QAction* a : std::as_const(widthActions_)) a->setVisible(tool != "text_box");
    for (QAction* a : std::as_const(textActions_)) a->setVisible(tool == "text_box");
    for (QAction* a : std::as_const(eraserActions_)) a->setVisible(tool == "eraser");
    for (QAction* a : std::as_const(pressureActions_)) a->setVisible(hasPressure(tool));
    width_->setEnabled(hasWidth(tool));
    widthLabel_->setText(tool == "eraser" ? QStringLiteral(" Size ") : QStringLiteral(" Width "));
    if (!hasWidth(tool)) return;
    double w = widths_.value(tool, 0);
    if (w <= 0) w = tool == "shape" ? 3.0 : canvas_->editor() ? xn_editor_tool_width(canvas_->editor(), tool.constData()) : 3.0;
    const auto [lo, hi] = widthRange(tool);
    syncingWidth_ = true;
    width_->setRange(int(lo * 2), int(hi * 2));
    width_->setValue(int(std::lround(std::clamp(w, lo, hi) * 2)));
    widthValue_->setText(QString::number(width_->value() / 2.0, 'f', 1));
    syncingWidth_ = false;
}

void MainWindow::applyShape() {
    auto* e = canvas_->editor();
    if (!e) return;
    const xn_shape_style style{shapeKind_.constData(), widths_.value("shape", 3.0), shapeFill_ ? 1 : 0, 0.25, shapeDashed_ ? 1 : 0};
    xn_editor_set_shape_style(e, &style);
}

/* The markup tool's mode, handed to the editor. */
void MainWindow::applyMarkupMode() {
    if (QAction* m = markupModes_.value(markupMode_)) m->setChecked(true);
    if (auto* e = canvas_->editor()) xn_editor_set_markup_mode(e, markupMode_.constData());
}

/* The pen button draws with kind from now on; its icon and tip say which. */
void MainWindow::setPenKind(const QByteArray& kind) {
    const ToolEntry* entry = penEntry(kind);
    if (!entry || !penAction_) return;
    penAction_->setData(kind);
    penAction_->setIcon(xnIcon(QString::fromLatin1(entry->icon)));
    penAction_->setText(QString::fromUtf8(entry->label));
    penAction_->setToolTip(QStringLiteral("%1 (%2)").arg(QString::fromUtf8(entry->label), penAction_->shortcut().toString(QKeySequence::NativeText)));
    if (QAction* k = penKinds_.value(kind)) k->setChecked(true);
}

xn_pen_style MainWindow::penStyle(const QByteArray& kind) const {
    if (auto it = penStyles_.constFind(kind); it != penStyles_.constEnd()) return *it;
    xn_pen_style s{1, 65, 2, 74, 30, 10, 8};  // the core's defaults, should there be no editor
    if (auto* e = canvas_->editor()) xn_editor_pen_style(e, kind.constData(), &s);
    return s;
}

void MainWindow::setPenStyle(const QByteArray& kind, xn_pen_style style) {
    if (hasPressure(kind)) style.pressure = pressure_->isChecked() ? 1 : 0;
    penStyles_.insert(kind, style);
    if (auto* e = canvas_->editor()) xn_editor_set_pen_style(e, kind.constData(), &style);
}

void MainWindow::setPressure(bool) {
    for (const ToolEntry& p : kPens)
        if (hasPressure(p.id)) setPenStyle(p.id, penStyle(p.id));
}

/* The current pen's own settings, under the pen button. */
void MainWindow::showPenStyle() {
    const QByteArray kind = penAction_->data().toByteArray();
    xn_pen_style style = penStyle(kind);
    if (hasPressure(kind)) style.pressure = pressure_->isChecked() ? 1 : 0;
    auto* popup = new PenStylePopup(kind, style, [this, kind](const xn_pen_style& s) { setPenStyle(kind, s); }, this);
    popup->setAttribute(Qt::WA_DeleteOnClose);
    QWidget* anchor = nullptr;
    for (QToolBar* tb : findChildren<QToolBar*>())
        if (QWidget* w = tb->widgetForAction(penAction_)) anchor = w;
    popup->adjustSize();
    popup->move(anchor ? anchor->mapToGlobal(QPoint(0, anchor->height())) : QCursor::pos());
    popup->show();
}

/* Settings the toolbar holds, handed to a fresh editor. */
void MainWindow::applyToolSettings() {
    auto* e = canvas_->editor();
    if (!e) return;
    for (auto it = widths_.cbegin(); it != widths_.cend(); ++it)
        if (it.key() != "shape") xn_editor_set_tool_width(e, it.key().constData(), it.value());
    applyShape();
    for (auto it = penStyles_.cbegin(); it != penStyles_.cend(); ++it) xn_editor_set_pen_style(e, it.key().constData(), &it.value());
    xn_editor_set_erase_mode(e, eraseMode_->currentData().toInt());
    xn_editor_set_text_face(e, face_->currentData().toByteArray().constData());
    xn_editor_set_text_size(e, textSize_->value());
    applyMarkupMode();
}

/*
 * Paste what was copied last: items copied in the editor, or an image another program put on the
 * system clipboard since.
 */
void MainWindow::pasteAt(QPointF widget) {
    auto* e = canvas_->editor();
    if (!e) return;
    if (!canvas_->rect().contains(widget.toPoint())) widget = QPointF(canvas_->width() / 2.0, canvas_->height() / 3.0);
    const QPointF at = canvas_->toDevice(widget);
    const QMimeData* mime = QApplication::clipboard()->mimeData();
    const bool image = mime && mime->hasImage();
    if (image && (clipboardIsNewer_ || !xn_editor_can_paste(e))) {
        const QImage picture = qvariant_cast<QImage>(mime->imageData());
        const QString file = workFile(QStringLiteral("png"));
        if (!picture.isNull() && !file.isEmpty() && picture.save(file, "PNG")) insertImage(file, at, true);
        return;
    }
    if (!xn_editor_can_paste(e)) return;
    xn_editor_paste_at(e, at.x(), at.y());
    selectionBar_->refresh();
}

/* In-note search through the find bar. */
void MainWindow::toggleFind(bool on) {
    if (!findBar_) return;
    findBar_->setVisible(on);
    if (on) {
        findField_->setFocus();
        runFind();
        findPoll_->start();
    } else {
        findPoll_->stop();
        if (auto* e = canvas_->editor()) xn_editor_search_close(e);
    }
}

void MainWindow::runFind() {
    auto* e = canvas_->editor();
    if (!e || !findBar_->isVisible()) return;
    xn_editor_search(e, QFile::encodeName(findField_->text()).constData(), findCase_->isChecked() ? 1 : 0,
                     findWords_->isChecked() ? 1 : 0);
    refreshFind();
}

void MainWindow::refreshFind() {
    auto* e = canvas_->editor();
    if (!e || !findBar_->isVisible()) return;
    const int count = xn_editor_search_count(e);
    const int current = xn_editor_search_current(e);
    findCount_->setText(count <= 0 ? QString() : QStringLiteral("%1/%2").arg(current + 1).arg(count));
}

/* Retype the selected sticky note. */
void MainWindow::editStickyText(xn_editor* e) {
    char* current = xn_editor_sticky_text(e);
    const QString text = current ? QString::fromUtf8(current) : QString();
    if (current) xn_free_string(current);
    bool ok = false;
    const QString next = QInputDialog::getMultiLineText(this, QStringLiteral("Sticky note"), QString(), text, &ok);
    if (!ok) return;
    xn_editor_set_sticky_text(e, QFile::encodeName(next).constData());
}

/* Edit the tapped markup's note (empty clears it). */
void MainWindow::editMarkupNote(xn_editor* e, const QString& current) {
    bool ok = false;
    const QString text = QInputDialog::getMultiLineText(this, QStringLiteral("Markup note"), QString(), current, &ok);
    if (!ok) return;
    xn_editor_set_markup_note(e, text.isEmpty() ? nullptr : QFile::encodeName(text).constData());
}

/* A fresh file name in the note's work folder, which lives as long as the note. */
QString MainWindow::workFile(const QString& suffix) {
    if (!workDir_ || !workDir_->isValid()) workDir_ = std::make_unique<QTemporaryDir>();
    if (!workDir_->isValid()) return {};
    return workDir_->filePath(QStringLiteral("inserted-%1.%2").arg(QUuid::createUuid().toString(QUuid::Id128), suffix));
}

void MainWindow::chooseImage() {
    if (!canvas_->editor()) return;
    QStringList patterns;
    for (const QByteArray& f : QImageReader::supportedImageFormats()) patterns << QStringLiteral("*.") + QString::fromLatin1(f);
    const QString path = QFileDialog::getOpenFileName(this, QStringLiteral("Insert picture"), QString(),
                                                      QStringLiteral("Images (%1)").arg(patterns.join(QLatin1Char(' '))));
    if (!path.isEmpty()) insertImageFile(path, QPointF(), false);
}

/* Copy an image file into the work folder and put it on the page (at a device point, if given). */
bool MainWindow::insertImageFile(const QString& path, QPointF at, bool atPoint) {
    const QString copy = workFile(QFileInfo(path).suffix().isEmpty() ? QStringLiteral("img") : QFileInfo(path).suffix().toLower());
    if (copy.isEmpty() || !QFile::copy(path, copy)) {
        QMessageBox::warning(this, QStringLiteral("Picture"), QStringLiteral("Could not copy %1").arg(path));
        return false;
    }
    return insertImage(copy, at, atPoint);
}

bool MainWindow::insertImage(const QString& file, QPointF at, bool atPoint) {
    auto* e = canvas_->editor();
    if (!e) return false;
    if (!xn_editor_insert_image(e, QFile::encodeName(file).constData(), atPoint ? 1 : 0, at.x(), at.y())) {
        QFile::remove(file);
        QMessageBox::warning(this, QStringLiteral("Picture"), QStringLiteral("Could not read the image"));
        return false;
    }
    return true;
}

void MainWindow::selectAll() {
    auto* e = canvas_->editor();
    if (!e) return;
    xn_editor_select_all(e);
    selectionBar_->refresh();
}

void MainWindow::showContextMenu(QPointF at, bool onLocked) {
    auto* e = canvas_->editor();
    if (!e) return;
    QMenu menu(this);
    const bool onMarkup = xn_editor_has_markup(e) != 0;
    if (onMarkup) {
        if (char* note = xn_editor_markup_note(e)) {
            const QString text = QString::fromUtf8(note);
            xn_free_string(note);
            menu.addAction(xnIcon(QStringLiteral("textBox")), QStringLiteral("Note: %1").arg(text.left(40)), this, [this, e, text] {
                editMarkupNote(e, text);
            });
        } else {
            menu.addAction(xnIcon(QStringLiteral("textBox")), QStringLiteral("Add note…"), this, [this, e] { editMarkupNote(e, {}); });
        }
        menu.addAction(xnIcon(QStringLiteral("trash")), QStringLiteral("Delete markup"), this, [e] { xn_editor_delete_markup(e); });
        menu.exec(canvas_->mapToGlobal(at.toPoint()));
        xn_editor_clear_markup(e);
        return;
    }
    QAction* paste = menu.addAction(xnIcon(QStringLiteral("paste")), QStringLiteral("Paste"), this, [this, at] { pasteAt(at); });
    const QMimeData* mime = QApplication::clipboard()->mimeData();
    paste->setEnabled(xn_editor_can_paste(e) || (mime && mime->hasImage()));
    if (onLocked) menu.addAction(xnIcon(QStringLiteral("unlock")), QStringLiteral("Unpin"), this, [e] { xn_editor_unlock_pressed(e); });
    if (xn_editor_selection_has_sticky(e)) {
        menu.addAction(xnIcon(QStringLiteral("textBox")), QStringLiteral("Edit sticky text…"), this, [this, e] { editStickyText(e); });
        auto* colors = menu.addMenu(QStringLiteral("Paper color"));
        // Same papers as StickyNoteItem.PALETTE.
        const QColor papers[] = {QColor(255, 241, 118), QColor(255, 205, 210), QColor(200, 230, 201),
                                 QColor(187, 222, 251), QColor(255, 224, 178), QColor(225, 190, 231)};
        for (const QColor& paper : papers) {
            QAction* swatch = colors->addAction(swatchIcon(paper), paper.name());
            connect(swatch, &QAction::triggered, this, [e, paper] { xn_editor_set_sticky_color(e, toRgba(paper)); });
        }
        menu.addAction(xnIcon(QStringLiteral("pin")), QStringLiteral("Collapse/expand sticky"), this, [e] { xn_editor_toggle_sticky(e); });
    }
    menu.addAction(xnIcon(QStringLiteral("pin")), QStringLiteral("Sticky note"), this, [this, at] {
        if (auto* e = canvas_->editor()) {
            const QPointF dev = canvas_->toDevice(at);
            xn_editor_insert_sticky(e, dev.x(), dev.y());
        }
    });
    menu.addAction(xnIcon(QStringLiteral("select")), QStringLiteral("Select all"), this, &MainWindow::selectAll);
    menu.exec(canvas_->mapToGlobal(at.toPoint()));
}

void MainWindow::install(xn_note* note, std::unique_ptr<QTemporaryDir> workDir, const QString& path) {
    if (!boundFileId_.isEmpty() && canvas_->editor()) {
        companion_->closeFile(boundFileId_);
        companion_->unbindEditor(canvas_->editor());
        boundFileId_.clear();
    }
    suggestedName_.clear();
    if (pageStyle_) pageStyle_->close();  // it edits the editor going away
    pages_->setDocument(nullptr, nullptr);
    canvas_->setNote(nullptr);
    if (note_) xn_note_close(note_);
    note_ = note;
    workDir_ = std::move(workDir);
    path_ = path;
    installing_ = true;
    canvas_->setNote(note_);
    installing_ = false;
    applyKind();
    canvas_->setDark(dark_);
    pages_->setPaper(paper());
    pages_->setDocument(note_, canvas_->editor());
    if (QAction* tool = tools_->checkedAction()) canvas_->setTool(tool->data().toByteArray().constData());
    if (!path.isEmpty() && !QDir(path).absolutePath().startsWith(QDir::tempPath() + QLatin1Char('/')))
        xn::Recents::touchLocal(path);
    if (colorAction_->data().isValid()) setInk(colorAction_->data().value<QColor>());
    applyToolSettings();
    syncWidth();
    refresh();
    refreshView();
    showPage(0);
}

/* Sidebar navigation: Home, Browse, Shared files, or the open note's canvas. */
void MainWindow::showPage(int index) {
    stack_->setCurrentWidget(stack_->widget(index));
    const bool onCanvas = index == 0;
    for (QToolBar* bar : std::as_const(editorBars_)) bar->setVisible(onCanvas);
    pagesDock_->setVisible(onCanvas && pagesAction_->isChecked() && !(note_ && xn_note_is_canvas(note_)));
    navHome_->setChecked(index == 1);
    navBrowse_->setChecked(index == 2);
    navShared_->setChecked(index == 3);
    if (index == 1) {
        home_->refreshRecents();
        refreshHome();
    } else if (index == 2) {
        browse_->refresh();
    } else if (index == 3) {
        refreshSharedPage();
    }
}

/* --- companion home (SYNC.md v1) --- */

void MainWindow::setupCompanion() {
    companion_ = new xn::CompanionServer(this);
    connect(home_, &xn::HomeScreen::newNote, this, [this] { if (confirmDiscard()) newNote(); });
    connect(home_, &xn::HomeScreen::openFile, this, [this] { chooseOpen(); });
    connect(home_, &xn::HomeScreen::openLocal, this, [this](const QString& path) { open(path); });
    connect(home_, &xn::HomeScreen::openShared, this, [this](const QString& id) { openSharedFile(id); });
    connect(browse_, &xn::BrowsePage::newNote, this, [this] { if (confirmDiscard()) newNote(); });
    connect(browse_, &xn::BrowsePage::openLocal, this, [this](const QString& path) { open(path); });
    connect(shared_, &xn::SharedPage::openShared, this, [this](const QString& id) { openSharedFile(id); });
    connect(home_, &xn::HomeScreen::serverToggled, this, [this](bool on) {
        if (on) companion_->start();
        else companion_->stop();
        refreshHome();
    });
    connect(home_, &xn::HomeScreen::pairBegin, this, [this] { home_->setPairCode(companion_->pairBegin()); });
    connect(home_, &xn::HomeScreen::unpair, this, [this](const QString& name) {
        if (QMessageBox::question(this, QStringLiteral("Forget device"),
                                  QStringLiteral("Forget %1? It will have to pair again to connect.").arg(name),
                                  QMessageBox::Yes | QMessageBox::No) != QMessageBox::Yes)
            return;
        companion_->unpair(name);
        refreshHomeDevices();
    });
    connect(home_, &xn::HomeScreen::renameDevice, this, [this](const QString& oldName) {
        bool ok = false;
        const QString name = QInputDialog::getText(this, QStringLiteral("Rename device"),
                                                   QStringLiteral("Alias for %1 (shown instead of its name):").arg(oldName),
                                                   QLineEdit::Normal, oldName, &ok);
        if (!ok || name.trimmed().isEmpty() || name == oldName) return;
        if (!companion_->renameDevice(oldName, name.trimmed()))
            statusBar()->showMessage(QStringLiteral("Could not rename %1").arg(oldName), 4000);
        refreshHomeDevices();
    });
    connect(home_, &xn::HomeScreen::renameSelf, this, [this] {
        bool ok = false;
        const QString name = QInputDialog::getText(this, QStringLiteral("This device's nickname"),
                                                   QStringLiteral("Shown to the host in requests (empty restores the host name):"),
                                                   QLineEdit::Normal, companion_->deviceName(), &ok);
        if (!ok) return;
        companion_->setDeviceName(name);
        home_->setSelfName(companion_->deviceName());
    });
    connect(shared_, &xn::SharedPage::deleteShared, this, [this](const QString& id) {
        if (QMessageBox::question(this, QStringLiteral("Delete"),
                                  QStringLiteral("Ask the host to delete this file? This cannot be undone."),
                                  QMessageBox::Yes | QMessageBox::No) != QMessageBox::Yes)
            return;
        companion_->requestDelete(id);
    });
    connect(shared_, &xn::SharedPage::createShared, this, [this](const QString& dirId, const QString& name, int kind) {
        companion_->requestCreate(dirId, name, kind);
    });
    connect(companion_, &xn::CompanionServer::statusChanged, this, [this](const QString&) { refreshHome(); });
    connect(companion_, &xn::CompanionServer::pairRequested, this, &MainWindow::approvePairing);
    connect(companion_, &xn::CompanionServer::paired, this, [this](const QString&) {
        home_->setPairCode({});
        refreshHome();
    });
    connect(companion_, &xn::CompanionServer::indexChanged, this, &MainWindow::refreshHomeFiles);
    connect(companion_, &xn::CompanionServer::fileReady, this, &MainWindow::openSharedReady);
    connect(companion_, &xn::CompanionServer::fileGone, this, [this](const QString& id) {
        if (boundFileId_ == id) {
            boundFileId_.clear();
            showHome();
            statusBar()->showMessage(QStringLiteral("The host unshared the open file"), 4000);
        }
        xn::Recents::forgetShared(id);
        refreshHomeFiles();
    });
    connect(companion_, &xn::CompanionServer::resync, this, [this](const QString& id, long long) { reopenSharedFile(id); });
    connect(companion_, &xn::CompanionServer::paint, this, [this] { canvas_->update(); });
    connect(companion_, &xn::CompanionServer::deleteAnswered, this, [this](bool ok, const QString& reason) {
        statusBar()->showMessage(ok ? QStringLiteral("Deleted on the host") : QStringLiteral("Host declined: %1").arg(reason), 4000);
    });
    connect(companion_, &xn::CompanionServer::createAnswered, this, [this](bool ok, const QString&, const QString& reason) {
        if (!ok) statusBar()->showMessage(QStringLiteral("Host declined: %1").arg(reason), 4000);
    });
    connect(companion_, &xn::CompanionServer::error, this, [this](const QString& reason) {
        statusBar()->showMessage(reason, 6000);
    });
    companion_->start();
    refreshHome();
}

void MainWindow::showHome() {
    showPage(1);
    refreshHome();
}

void MainWindow::refreshHome() {
    if (!companion_ || !companion_->sync()) {
        home_->setStatus(QStringLiteral("Companion is off."));
        home_->setConnected({});
        home_->setSharing(false);
        return;
    }
    const QStringList addrs = companion_->lanAddresses();
    QString status = QStringLiteral("Listening on %1:%2").arg(addrs.isEmpty() ? QStringLiteral("…") : addrs.join(QStringLiteral(", "))).arg(xn::CompanionServer::kPort);
    char* paired = xn_sync_paired_name(companion_->sync());
    const QString device = paired ? QString::fromUtf8(paired) : QString();
    if (paired) xn_free_string(paired);
    if (!device.isEmpty()) status += QStringLiteral(" — connected: %1").arg(device);
    home_->setStatus(status);
    home_->setConnected(device);
    home_->setSharing(true);
    home_->setSelfName(companion_->deviceName());
    refreshHomeDevices();
    refreshHomeFiles();
}

void MainWindow::refreshHomeDevices() {
    QStringList names;
    if (auto* s = companion_ ? companion_->sync() : nullptr) {
        const int n = xn_sync_pin_count(s);
        for (int i = 0; i < n; ++i) {
            char* name = xn_sync_pin_name(s, i);
            if (name) {
                names << QString::fromUtf8(name);
                xn_free_string(name);
            }
        }
    }
    home_->setDevices(names);
}

void MainWindow::refreshSharedPage() {
    QList<xn::SharedFile> files;
    if (auto* s = companion_ ? companion_->sync() : nullptr) {
        const int n = xn_sync_file_count(s);
        for (int i = 0; i < n; ++i) {
            char *id = nullptr, *path = nullptr;
            int kind = 0;
            long long version = 0, size = 0;
            if (!xn_sync_file_info(s, i, &id, &path, &kind, &version, &size)) continue;
            files << xn::SharedFile{QString::fromUtf8(id), QString::fromUtf8(path), kind, version, size};
            xn_free_string(id);
            xn_free_string(path);
        }
    }
    shared_->setFiles(files);
}

void MainWindow::refreshHomeFiles() { refreshSharedPage(); }

QString MainWindow::syncFilePath(const QString& id) const {
    if (auto* s = companion_ ? companion_->sync() : nullptr) {
        const int n = xn_sync_file_count(s);
        for (int i = 0; i < n; ++i) {
            char *fid = nullptr, *fpath = nullptr;
            int kind = 0;
            long long version = 0, size = 0;
            if (!xn_sync_file_info(s, i, &fid, &fpath, &kind, &version, &size)) continue;
            const bool match = id == QString::fromUtf8(fid);
            const QString path = QString::fromUtf8(fpath);
            xn_free_string(fid);
            xn_free_string(fpath);
            if (match) return path;
        }
    }
    return id;
}

void MainWindow::approvePairing(const QString& device, const QString& fp) {
    QString grouped;
    for (int i = 0; i + 1 < fp.size(); i += 2) {
        if (!grouped.isEmpty()) grouped += QLatin1Char(':');
        grouped += fp.mid(i, 2).toUpper();
    }
    const bool yes =
        QMessageBox::question(this, QStringLiteral("Pair device"),
                              QStringLiteral("Allow %1 to share notes?\nFingerprint: %2\nOnly approve if you just asked to pair.")
                                  .arg(device, grouped),
                              QMessageBox::Yes | QMessageBox::No) == QMessageBox::Yes;
    companion_->pairDecide(yes);
    if (yes) home_->setPairCode({});
}

void MainWindow::openSharedFile(const QString& id) {
    if (!confirmDiscard()) return;
    pendingOpenId_ = id;
    statusBar()->showMessage(QStringLiteral("Fetching…"), 2000);
    companion_->openFile(id);
}

void MainWindow::openSharedReady(const QString& id) {
    if (id != pendingOpenId_) return;
    pendingOpenId_.clear();
    char* path = xn_sync_file_path(companion_->sync(), QFile::encodeName(id).constData());
    if (!path) {
        statusBar()->showMessage(QStringLiteral("Fetch failed"), 4000);
        return;
    }
    const QString local = QString::fromUtf8(path);
    xn_free_string(path);
    if (!open(local)) return;
    xn::Recents::touchShared(id, syncFilePath(id));
    companion_->bindEditor(canvas_->editor(), id);
    boundFileId_ = id;
    lastPresencePage_ = -2;
    syncPresence();
}

void MainWindow::linkFromSelection() {
    auto* e = canvas_->editor();
    auto* server = companion_;
    if (!e || !server || !server->running() || !server->sync()) return;
    // Shared files by display path; the link stores the file id.
    QStringList names;
    QHash<QString, QString> idByName;
    const int n = xn_sync_file_count(server->sync());
    for (int i = 0; i < n; i++) {
        char* id = nullptr;
        char* path = nullptr;
        int kind = 0;
        long long version = 0, size = 0;
        if (xn_sync_file_info(server->sync(), i, &id, &path, &kind, &version, &size) == 0 || kind != 0) {
            xn_free_string(id);
            xn_free_string(path);
            continue;
        }
        const QString qid = QString::fromUtf8(id);
        QString name = QString::fromUtf8(path).section(QLatin1Char('/'), -1);
        if (name.isEmpty()) name = qid;
        xn_free_string(id);
        xn_free_string(path);
        if (idByName.contains(name)) continue;
        idByName.insert(name, qid);
        names << name;
    }
    if (names.isEmpty()) return;
    std::sort(names.begin(), names.end());
    bool ok = false;
    const QString picked = QInputDialog::getItem(this, tr("Link to note"), tr("Shared file:"), names, 0, false, &ok);
    if (!ok) return;
    const QString target = idByName.value(picked);
    if (!target.isEmpty()) xn_editor_pdf_link(e, target.toUtf8().constData());
}

void MainWindow::reopenSharedFile(const QString& id) {    if (boundFileId_ != id) return;
    if (canvas_->editor()) companion_->unbindEditor(canvas_->editor());
    boundFileId_.clear();
    statusBar()->showMessage(QStringLiteral("Reloading the shared file…"), 2000);
    install(nullptr, std::make_unique<QTemporaryDir>(), QString());
    showHome();
    pendingOpenId_ = id;
    // The engine already queued the fetch on resync.
}

void MainWindow::syncPresence() {
    if (boundFileId_.isEmpty() || !canvas_->editor()) return;
    const int page = xn_editor_current_page(canvas_->editor());
    if (page == lastPresencePage_) return;
    lastPresencePage_ = page;
    companion_->presence(boundFileId_, page);
}

void MainWindow::newNote() { install(xn_note_new(1), std::make_unique<QTemporaryDir>(), QString()); }

void MainWindow::newCanvas() { install(xn_canvas_new(), std::make_unique<QTemporaryDir>(), QString()); }

/* An infinite canvas has no pages, text boxes or PDF export: their controls step aside. */
void MainWindow::applyKind() {
    const bool canvas = note_ && xn_note_is_canvas(note_);
    for (QAction* a : std::as_const(pageActions_)) a->setVisible(!canvas);
    pagesAction_->setVisible(!canvas);
    pagesDock_->setVisible(!canvas && pagesAction_->isChecked());
    exportAction_->setEnabled(!canvas);
    fitAction_->setText(canvas ? QStringLiteral("Show all") : QStringLiteral("Fit width"));
    fitAction_->setToolTip(fitAction_->text());
    for (QAction* a : tools_->actions()) {
        if (a->data().toByteArray() != "text_box") continue;
        a->setVisible(!canvas);
        if (canvas && a->isChecked()) {
            for (QAction* pen : tools_->actions())
                if (pen->data().toByteArray() == "pen") pen->trigger();
        }
    }
}

bool MainWindow::open(const QString& path) {
    auto workDir = std::make_unique<QTemporaryDir>();
    if (!workDir->isValid()) {
        QMessageBox::warning(this, QStringLiteral("Open"), QStringLiteral("Could not create a temporary folder"));
        return false;
    }
    if (path.endsWith(QLatin1String(".pdf"), Qt::CaseInsensitive)) {
        // A note made from this PDF before is usually what is wanted, rather than a fresh one.
        const QStringList notes = notesForPdf(path);
        if (!notes.isEmpty()) {
            bool cancelled = false;
            const QString note = chooseExistingNote(path, notes, &cancelled);
            if (cancelled) return false;
            if (!note.isEmpty()) return open(note);
        }
        return importPdf(path, std::move(workDir));
    }
    char* error = nullptr;
    const QByteArray file = QFile::encodeName(QFileInfo(path).absoluteFilePath());
    const QByteArray dir = QFile::encodeName(workDir->path());
    xn_note* note = xn_note_open(file.constData(), dir.constData(), canvas_->host(), &error);
    if (!note) {
        QMessageBox::warning(this, QStringLiteral("Open"), QStringLiteral("Could not open %1:\n%2").arg(path, takeError(error)));
        return false;
    }
    install(note, std::move(workDir), QFileInfo(path).absoluteFilePath());
    return true;
}

/* Which of the notes made from the PDF to open; empty for a new one. */
QString MainWindow::chooseExistingNote(const QString& pdf, const QStringList& notes, bool* cancelled) {
    const QString pdfName = QFileInfo(pdf).fileName();
    if (notes.size() == 1) {
        QMessageBox box(QMessageBox::Question, QStringLiteral("Open PDF"),
                        QStringLiteral("A note '%2' already exists for '%1'. Open it?").arg(pdfName, QFileInfo(notes.first()).fileName()),
                        QMessageBox::NoButton, this);
        QPushButton* openNote = box.addButton(QStringLiteral("Open note"), QMessageBox::AcceptRole);
        QPushButton* fresh = box.addButton(QStringLiteral("Create new"), QMessageBox::DestructiveRole);
        box.addButton(QMessageBox::Cancel);
        box.setDefaultButton(openNote);
        box.exec();
        if (box.clickedButton() == openNote) return notes.first();
        if (box.clickedButton() == fresh) return {};
        *cancelled = true;
        return {};
    }
    QStringList items;
    for (const QString& n : notes)
        items << QStringLiteral("%1 — modified %2").arg(QFileInfo(n).fileName(), QFileInfo(n).lastModified().toString(QStringLiteral("yyyy-MM-dd HH:mm")));
    const QString freshItem = QStringLiteral("Create new note");
    items << freshItem;
    bool ok = false;
    const QString picked = QInputDialog::getItem(this, QStringLiteral("Open PDF"),
                                                 QStringLiteral("Notes already exist for '%1':").arg(pdfName), items, 0, false, &ok);
    if (!ok) {
        *cancelled = true;
        return {};
    }
    const qsizetype index = items.indexOf(picked);
    return picked == freshItem || index < 0 ? QString() : notes.at(index);
}

/* A new note over a copy of the PDF (the note embeds it when saved); saving asks for a name. */
bool MainWindow::importPdf(const QString& path, std::unique_ptr<QTemporaryDir> workDir) {
    const QString copy = workDir->filePath(QStringLiteral("source.pdf"));
    if (!QFile::copy(path, copy)) {
        QMessageBox::warning(this, QStringLiteral("Open"), QStringLiteral("Could not copy %1").arg(path));
        return false;
    }
    char* error = nullptr;
    xn_note* note = xn_note_import_pdf(QFile::encodeName(copy).constData(), canvas_->host(), &error);
    if (!note) {
        QMessageBox::warning(this, QStringLiteral("Open"), QStringLiteral("Could not open PDF %1:\n%2").arg(path, takeError(error)));
        return false;
    }
    install(note, std::move(workDir), QString());
    suggestedName_ = QFileInfo(path).absolutePath() + QLatin1Char('/') + QFileInfo(path).completeBaseName() + QStringLiteral(".xnote");
    refresh();
    return true;
}

void MainWindow::chooseOpen() {
    if (!confirmDiscard()) return;
    const QString path = QFileDialog::getOpenFileName(this, QStringLiteral("Open"), QFileInfo(path_).absolutePath(),
                                                      QStringLiteral("Notes, canvases and PDFs (*.xnote *.xcanvas *.pdf);;Notes (*.xnote);;Canvases (*.xcanvas);;PDFs (*.pdf)"));
    if (!path.isEmpty()) open(path);
}

/*
 * The note as a PDF, a page per page at its own size: ink, shapes and text stay vector; a PDF
 * background is rendered at 300 dpi; paper is the screen's paper, so light ink stays visible.
 */
void MainWindow::exportPdf() {
    if (!note_) return;
    QString base = !path_.isEmpty() ? path_ : suggestedName_;
    base = base.isEmpty() ? QStringLiteral("note.pdf") : QFileInfo(base).absolutePath() + QLatin1Char('/') + QFileInfo(base).completeBaseName() + QStringLiteral(".pdf");
    QString out = QFileDialog::getSaveFileName(this, QStringLiteral("Export to PDF"), base, QStringLiteral("PDF (*.pdf)"));
    if (out.isEmpty()) return;
    if (!out.endsWith(QLatin1String(".pdf"), Qt::CaseInsensitive)) out += QLatin1String(".pdf");
    const QString error = writePdf(note_, out, paper(), [this](int done, int total) {
        statusBar()->showMessage(QStringLiteral("Exporting: page %1 of %2").arg(done).arg(total));
        QApplication::processEvents(QEventLoop::ExcludeUserInputEvents);
    });
    if (!error.isEmpty()) QMessageBox::warning(this, QStringLiteral("Export to PDF"), error);
    else statusBar()->showMessage(QStringLiteral("Exported: %1").arg(out), 4000);
}

bool MainWindow::save(bool askPath) {
    if (!note_) return false;
    QString path = path_;
    if (askPath || path.isEmpty()) {
        // A canvas is saved as .xcanvas, a note as .xnote: the format follows the document.
        const bool canvas = xn_note_is_canvas(note_);
        const QString ext = canvas ? QStringLiteral(".xcanvas") : QStringLiteral(".xnote");
        const QString start = !path.isEmpty() ? path : !suggestedName_.isEmpty() ? suggestedName_ : (canvas ? QStringLiteral("canvas") : QStringLiteral("pin")) + ext;
        path = QFileDialog::getSaveFileName(this, canvas ? QStringLiteral("Save canvas") : QStringLiteral("Save note"), start,
                                            canvas ? QStringLiteral("xnotes canvases (*.xcanvas)") : QStringLiteral("xnotes notes (*.xnote)"));
        if (path.isEmpty()) return false;
        if (!path.endsWith(ext)) path += ext;
    }
    char* error = nullptr;
    if (!xn_note_save(note_, QFile::encodeName(path).constData(), canvas_->host(), &error)) {
        QMessageBox::warning(this, QStringLiteral("Save"), QStringLiteral("Could not save %1:\n%2").arg(path, takeError(error)));
        return false;
    }
    path_ = path;
    if (!path.startsWith(QDir::tempPath() + QLatin1Char('/'))) xn::Recents::touchLocal(path);
    statusBar()->showMessage(QStringLiteral("Saved: %1").arg(path), 3000);
    refresh();
    return true;
}

bool MainWindow::confirmDiscard() {
    if (!note_ || !xn_note_is_dirty(note_)) return true;
    const auto answer = QMessageBox::question(this, QStringLiteral("Unsaved changes"),
                                              QStringLiteral("Save changes to the note?"),
                                              QMessageBox::Save | QMessageBox::Discard | QMessageBox::Cancel);
    if (answer == QMessageBox::Save) return save(false);
    return answer == QMessageBox::Discard;
}

void MainWindow::closeEvent(QCloseEvent* e) {
    if (confirmDiscard()) e->accept(); else e->ignore();
}

void MainWindow::chooseColor() {
    const QColor c = QColorDialog::getColor(colorAction_->data().value<QColor>(), this, QStringLiteral("Ink color"));
    if (c.isValid()) setInk(c);
}

void MainWindow::setInk(const QColor& c) {
    colorAction_->setData(c);
    colorAction_->setIcon(swatchIcon(c));
    if (auto* e = canvas_->editor())
        xn_editor_set_ink_color(e, (xn_rgba(c.red()) << 24) | (xn_rgba(c.green()) << 16) | (xn_rgba(c.blue()) << 8) | 0xFF);
}

void MainWindow::refresh() {
    auto* e = canvas_->editor();
    undo_->setEnabled(e && xn_editor_can_undo(e));
    redo_->setEnabled(e && xn_editor_can_redo(e));
    const bool canvas = note_ && xn_note_is_canvas(note_);
    const QString name = !path_.isEmpty() ? QFileInfo(path_).fileName() : canvas ? QStringLiteral("New canvas") : QStringLiteral("New note");
    const bool dirty = note_ && xn_note_is_dirty(note_);
    setWindowTitle((dirty ? QStringLiteral("• ") : QString()) + name + QStringLiteral(" — xnotes"));
    refreshView();
}

void MainWindow::syncScrollBars() {
    auto* e = canvas_->editor();
    double sc[6] = {0, 0, 0, 0, 0, 0};
    if (e) xn_editor_scroll_info(e, sc);
    syncingBars_ = true;
    vbar_->setRange(0, int(std::lround(sc[3])));
    vbar_->setPageStep(std::max(1, int(sc[5])));
    vbar_->setSingleStep(std::max(1, int(sc[5] / 20)));
    vbar_->setValue(int(std::lround(sc[1])));
    hbar_->setRange(0, int(std::lround(sc[2])));
    hbar_->setPageStep(std::max(1, int(sc[4])));
    hbar_->setSingleStep(std::max(1, int(sc[4] / 20)));
    hbar_->setValue(int(std::lround(sc[0])));
    hbar_->setVisible(sc[2] > 0);
    syncingBars_ = false;
}

void MainWindow::refreshView() {
    syncScrollBars();
    auto* e = canvas_->editor();
    if (!e || !note_) return;
    syncPresence();
    if (!xn_note_is_canvas(note_))
        pageLabel_->setText(QStringLiteral("%1 / %2").arg(xn_editor_current_page(e) + 1).arg(xn_note_page_count(note_)));
    zoomLabel_->setText(QStringLiteral("%1%").arg(int(xn_editor_zoom(e) * 100)));
}
