// The floating action bar over a settled selection (the Android SelectionMenu), and its
// "change style" popup.
#pragma once

#include "xnotes.h"

#include <QColor>
#include <QFrame>
#include <QList>

class CanvasWidget;
class QSlider;
class QLabel;

class SelectionBar : public QFrame {
    Q_OBJECT
public:
    explicit SelectionBar(CanvasWidget* canvas);

    /* Show over the selection's current rect, or hide when there is none. */
    void refresh();
    /* The core's selection_menu: shown follows the core, which may say so before it clears. */
    void onMenu(bool shown) { if (shown) refresh(); else hide(); }

    /* The swatches the style popup offers. */
    void setSwatches(const QList<QColor>& colors) { swatches_ = colors; }

private:
    CanvasWidget* canvas_;
    QList<QColor> swatches_;

    xn_editor* editor() const;
    void showStyle();
};

/* The bar over a settled PDF text selection: its marks and copy (the Android text selection menu). */
class PdfTextBar : public QFrame {
    Q_OBJECT
public:
    explicit PdfTextBar(CanvasWidget* canvas);

    /* Show over the text selection while the core has one, else hide. */
    void refresh();
    /* The core's selection_menu: a text selection shows, or clears, here. */
    void onMenu(bool shown) { if (shown) refresh(); else hide(); }

private:
    CanvasWidget* canvas_;

    xn_editor* editor() const;
};

/* Colour and thickness of the selection's strokes and shapes, applied live. */
class StylePopup : public QFrame {
    Q_OBJECT
public:
    StylePopup(xn_editor* editor, const QList<QColor>& swatches, xn_rgba color, double width, QWidget* parent);

protected:
    void hideEvent(QHideEvent* e) override;

private:
    xn_editor* editor_;
    QSlider* width_;
    QLabel* widthLabel_;
};

QColor fromRgba(xn_rgba c);
xn_rgba toRgba(const QColor& c);
QIcon swatchIcon(const QColor& c, int size = 18);
