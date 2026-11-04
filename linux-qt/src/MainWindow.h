// The editor window: one note on a canvas, a toolbar, and file commands.
#pragma once

#include "xnotes.h"

#include <QActionGroup>
#include <QHash>
#include <QMainWindow>
#include <QPointer>
#include <QTemporaryDir>

#include <memory>

class CanvasWidget;
class QCheckBox;
class QStackedWidget;

namespace xn {
class BrowsePage;
class CompanionServer;
class HomeScreen;
class SharedPage;
}
class QComboBox;
class QLineEdit;
class QSlider;
class QSpinBox;
class QLabel;
class QScrollBar;
class PageStylePopup;
class PagesPanel;
class QDockWidget;
class PdfTextBar;
class SelectionBar;

class MainWindow : public QMainWindow {
    Q_OBJECT
public:
    MainWindow();
    ~MainWindow() override;

    /* Open path; on failure shows why and keeps the current note. */
    bool open(const QString& path);
    void newNote();
    void newCanvas();
    void showHome();
    void refreshHome();
    void refreshHomeFiles();
    void refreshHomeDevices();
    void approvePairing(const QString& device, const QString& fp);
    void openSharedFile(const QString& id);
    void openSharedReady(const QString& id);
    void reopenSharedFile(const QString& id);
    void syncPresence();
    void setupCompanion();
    xn_note* note() const { return note_; }

protected:
    void closeEvent(QCloseEvent* e) override;

private:
    void showPage(int index);
    void refreshSharedPage();
    QString syncFilePath(const QString& id) const;

    QStackedWidget* stack_ = nullptr;
    xn::HomeScreen* home_ = nullptr;
    xn::BrowsePage* browse_ = nullptr;
    xn::SharedPage* shared_ = nullptr;
    QAction* navHome_ = nullptr;
    QAction* navBrowse_ = nullptr;
    QAction* navShared_ = nullptr;
    QList<QToolBar*> editorBars_;
    xn::CompanionServer* companion_ = nullptr;
    QString pendingOpenId_;
    QString boundFileId_;
    int lastPresencePage_ = -2;

    CanvasWidget* canvas_;
    xn_note* note_ = nullptr;
    std::unique_ptr<QTemporaryDir> workDir_;
    QString path_;
    QString suggestedName_;  // where "Save" proposes to put a note made from a PDF
    QAction* undo_;
    QAction* redo_;
    QAction* colorAction_;
    QAction* exportAction_;
    QAction* fitAction_;
    QList<QAction*> pageActions_;
    PagesPanel* pages_;
    QDockWidget* pagesDock_;
    QAction* pagesAction_;
    QActionGroup* tools_;
    QLabel* pageLabel_;
    QLabel* zoomLabel_;
    QLabel* inputLabel_;
    bool dark_ = true;
    SelectionBar* selectionBar_;
    PdfTextBar* textBar_;
    QPointer<PageStylePopup> pageStyle_;
    QSlider* width_;
    QLabel* widthValue_;
    QComboBox* face_;
    QSpinBox* textSize_;
    QList<QAction*> widthActions_;
    QList<QAction*> textActions_;
    QList<QAction*> eraserActions_;
    QLabel* widthLabel_;
    QComboBox* eraseMode_;
    bool syncingWidth_ = false;
    QHash<QByteArray, double> widths_;
    QByteArray markupMode_ = "select";  // select first, then mark from its menu
    QHash<QByteArray, QAction*> markupModes_;
    QByteArray shapeKind_ = "rectangle";
    QAction* penAction_ = nullptr;   // the pen button: draws with the kind of pen picked on it
    QHash<QByteArray, QAction*> penKinds_;
    QCheckBox* pressure_;
    QList<QAction*> pressureActions_;
    QHash<QByteArray, xn_pen_style> penStyles_;  // set here, handed to every new editor
    bool installing_ = false;  // a new editor reports the tool it starts with: the toolbar's wins
    bool shapeFill_ = false;
    bool shapeDashed_ = false;
    bool clipboardIsNewer_ = false;
    QScrollBar* vbar_;
    QScrollBar* hbar_;
    QToolBar* findBar_ = nullptr;
    QLineEdit* findField_ = nullptr;
    QLabel* findCount_ = nullptr;
    QAction* findCase_ = nullptr;
    QAction* findWords_ = nullptr;
    QTimer* findPoll_ = nullptr;
    bool syncingBars_ = false;  // the system clipboard changed after the last in-app copy

    void install(xn_note* note, std::unique_ptr<QTemporaryDir> workDir, const QString& path);
    void buildUi();
    void applyKind();
    void applyMarkupMode();
    /* Paper for pages without a colour of their own: the canvas's, in the panel and in exports. */
    xn_rgba paper() const { return dark_ ? 0x161616FFu : 0xFFFFFFFFu; }
    void refresh();
    void refreshView();
    void syncScrollBars();
    void chooseOpen();
    bool save(bool askPath);
    bool confirmDiscard();
    void chooseColor();
    void setInk(const QColor& c);
    QByteArray currentTool() const;
    void syncWidth();
    void applyShape();
    void setPenKind(const QByteArray& kind);
    xn_pen_style penStyle(const QByteArray& kind) const;
    void setPenStyle(const QByteArray& kind, xn_pen_style style);
    void setPressure(bool on);
    void showPenStyle();
    void applyToolSettings();
    void pasteAt(QPointF widget);
    void selectAll();
    void showContextMenu(QPointF at, bool onLocked);
    void toggleFind(bool on);
    void runFind();
    void refreshFind();
    void editMarkupNote(xn_editor* e, const QString& current);
    void editStickyText(xn_editor* e);
    QString workFile(const QString& suffix);
    void chooseImage();
    bool insertImageFile(const QString& path, QPointF at, bool atPoint);
    bool insertImage(const QString& file, QPointF at, bool atPoint);
    bool importPdf(const QString& path, std::unique_ptr<QTemporaryDir> workDir);
    QString chooseExistingNote(const QString& pdf, const QStringList& notes, bool* cancelled);
    void exportPdf();
};
