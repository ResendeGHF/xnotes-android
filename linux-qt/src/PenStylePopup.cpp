#include "PenStylePopup.h"

#include <QLabel>
#include <QSlider>
#include <QVBoxLayout>

#include <algorithm>

#include <cmath>

PenStylePopup::PenStylePopup(const QByteArray& tool, const xn_pen_style& style, std::function<void(const xn_pen_style&)> changed,
                             QWidget* parent)
    : QFrame(parent, Qt::Popup), style_(style), changed_(std::move(changed)) {
    setFrameShape(QFrame::StyledPanel);
    auto* col = new QVBoxLayout(this);
    const bool pressureKind = tool == "pen" || tool == "calligraphy" || tool == "speed" || tool == "taper";
    if (pressureKind) {
        addSlider(QStringLiteral("Pressure sensitivity"), 0, 100, &style_.sensitivity, QStringLiteral("%"), style_.pressure != 0);
        if (!style_.pressure) {
            auto* off = new QLabel(QStringLiteral("Pressure is off in the toolbar"), this);
            off->setEnabled(false);
            col->addWidget(off);
        }
    }
    if (tool == "calligraphy") addSlider(QStringLiteral("Nib contrast (thick to thin)"), 1, 5, &style_.multiplier, QStringLiteral("×"));
    if (tool == "speed") addSlider(QStringLiteral("Speed taper"), 0, 100, &style_.speed, QStringLiteral("%"));
    if (tool == "taper") addSlider(QStringLiteral("Tip width"), 0, 100, &style_.tip, QStringLiteral("%"));
    if (tool == "pencil") {
        addSlider(QStringLiteral("Pressure response"), 0.0, 2.0, &style_.pencil_pressure, QStringLiteral(""));
        addSlider(QStringLiteral("Graphite density"), 0.2, 1.3, &style_.pencil_density, QStringLiteral(""));
        addSlider(QStringLiteral("Grain size"), 0.1, 4.0, &style_.pencil_grain, QStringLiteral(""));
        addSlider(QStringLiteral("Shade response"), 0.0, 1.5, &style_.pencil_shade, QStringLiteral(""));
    }
    if (tool == "dashed") {
        addSlider(QStringLiteral("Dash"), 2, 40, &style_.dash, QStringLiteral(" px"));
        addSlider(QStringLiteral("Gap"), 2, 40, &style_.gap, QStringLiteral(" px"));
    }
    if (col->count() == 0) col->addWidget(new QLabel(QStringLiteral("This pen has no settings"), this));
}

void PenStylePopup::addSlider(const QString& label, double lo, double hi, double* field, const QString& unit, bool enabled) {
    auto* col = static_cast<QVBoxLayout*>(layout());
    auto* caption = new QLabel(this);
    // Tenths for the multiplier's short range, whole steps for the rest.
    const double step = hi - lo <= 10 ? 0.1 : 1.0;
    auto* slider = new QSlider(Qt::Horizontal, this);
    slider->setMinimumWidth(240);
    slider->setRange(int(std::lround(lo / step)), int(std::lround(hi / step)));
    slider->setValue(int(std::lround(std::clamp(*field, lo, hi) / step)));
    slider->setEnabled(enabled);
    caption->setEnabled(enabled);
    auto show = [caption, label, unit, step](double v) {
        caption->setText(QStringLiteral("%1: %2%3").arg(label, QString::number(v, 'f', step < 1 ? 1 : 0), unit));
    };
    show(slider->value() * step);
    connect(slider, &QSlider::valueChanged, this, [this, field, step, show](int v) {
        *field = v * step;
        show(*field);
        changed_(style_);
    });
    col->addWidget(caption);
    col->addWidget(slider);
}
