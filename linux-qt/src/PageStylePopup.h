// "Page background": paper colour, ruling (lines, dots, grid) and any installed page template
// (.xtemplate) for all pages or the current one, applied live (the Android Styles popup).
#pragma once

#include "xnotes.h"

#include <QDialog>

class QAbstractButton;
class QButtonGroup;
class QCheckBox;
class QComboBox;
class QLabel;
class QSlider;
class QToolButton;

class PageStylePopup : public QDialog {
    Q_OBJECT
public:
    /* canvas: an infinite canvas's background, one for the whole canvas (patterns only). */
    PageStylePopup(xn_editor* editor, xn_note* note, bool canvas, QWidget* parent);

private:
    xn_editor* editor_;
    xn_note* note_;
    bool allPages_ = true;
    bool syncing_ = false;
    xn_page_style style_{};

    QButtonGroup* scope_;
    QButtonGroup* paper_;
    QButtonGroup* pattern_;
    QComboBox* template_ = nullptr;
    QToolButton* customPaper_;
    QLabel* spacingLabel_;
    QSlider* spacing_;
    QCheckBox* defaultSpacing_;
    QToolButton* lineColor_;
    QCheckBox* defaultLineColor_;
    QLabel* opacityLabel_;
    QSlider* opacity_;
    QCheckBox* invert_;

    void load();
    void apply();
    xn_rgba effectiveLineColor() const;
};
