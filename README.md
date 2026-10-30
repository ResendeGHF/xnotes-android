<p align="center">
  <img src="docs/readme-banner.png" width="100%" alt="xnotes: a handwriting-first notebook for Android" />
</p>

<p align="center">
  <a href="https://github.com/shardulvs/xnotes-android/releases/latest"><img src="https://img.shields.io/github/v/release/shardulvs/xnotes-android?style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="https://f-droid.org/en/packages/com.xnotes"><img src="https://img.shields.io/f-droid/v/com.xnotes?style=flat-square" alt="F-Droid" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-orange?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
  <a href="https://github.com/sponsors/shardulvs"><img src="https://img.shields.io/badge/sponsor-%E2%9D%A4-ec6cb9?style=flat-square&logo=githubsponsors&logoColor=white" alt="Sponsor" /></a>
</p>

<p align="center">
  <a href="https://f-droid.org/en/packages/com.xnotes"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" height="70" alt="Get it on F-Droid" /></a>
  <a href="https://play.google.com/store/apps/details?id=com.xnotes"><img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" height="70" alt="Get it on Google Play" /></a>
</p>

<p align="center">
  <img src="docs/screenshots/editor.png" width="90%" alt="The xnotes editor with a PDF open in dark mode, annotated by hand with a note, an arrow, a highlight and an underline, and page thumbnails down the left" />
  <br />
  <em>Annotating a PDF in dark mode, with the pages inverted</em>
</p>

<p align="center">
  <img src="docs/screenshots/explorer.png" width="90%" alt="The xnotes file explorer in gallery view, showing a grid of imported book covers with page counts" />
  <br />
  <em>Browsing imported PDFs in the gallery view</em>
</p>

---

## Features

- **Ink that feels like a pen**: pressure-sensitive and low latency, with pens and highlighters.
- **Neon glow**: make any pen glow.
- **Disappearing ink**: ink that fades away on its own, great for teaching and presenting.
- **Tape**: cover answers with tape and tap to peel it back, for testing yourself.
- **Shape snapping**: draw a rough shape, hold, and it snaps clean.
- **Sharp at any zoom**: ink and PDFs stay crisp however far you zoom in.
- **Notebooks and an infinite canvas**: pages when you want structure, endless space when you don't.
- **Book layouts**: single, double or cover pages, scrolling or page flips.
- **PDF import**: bring in any PDF and write all over it.
- **Room for side notes**: widen a PDF's margins on any edge and take notes on the side.
- **PDF text markups**: highlight, underline and strike out text, add comments, search it.
- **PDF colour filters**: invert, sepia, contrast, brightness, multiply and screen.
- **Images stay untouched**: filter a PDF and its photos and images keep their true colours.
- **Dark and OLED modes**: easy on the eyes at night, true black on OLED screens.
- **Real PDF export**: sharp vector output with selectable text, links and bookmarks.
- **Markdown**: write in markdown and it formats as you type.
- **Typed notes**: tables, LaTeX equations and code highlighting, right on the page.
- **Audio recording**: record a lecture as you write, and your ink replays in sync with it.
- **20+ page templates**: Cornell, planners, music staves, isometric and more.
- **Endless themes**: any accent colour, eight styles for each, dual tone and Material You.
- **Make it yours**: rearrange the toolbar, float it or dock it on any edge, and pick its size.
- **Split view**: two notes side by side.
- **A real file explorer**: grid, gallery, list, column and timeline views, with plenty to customise.
- **Your notes, your folders**: notes are saved as files in a folder you choose, not locked away in an app's database, so you stay in full control of them.
- **Works with stylus pens**: S Pen and many others.
- **Always editable**: nothing gets flattened, so every stroke can be moved, restyled or erased later.
- **Private**: open source, no account, no ads, no tracking. The only network use is the one-time download of on-device recognition models (handwriting, optional maths).

## Install

| Channel | |
|---|---|
| [GitHub Releases](https://github.com/shardulvs/xnotes-android/releases/latest) | Signed APK |
| [F-Droid](https://f-droid.org/en/packages/com.xnotes) | Built reproducibly from source |
| [Google Play](https://play.google.com/store/apps/details?id=com.xnotes) | Automatic updates |

GitHub and F-Droid ship the same signed APK, so you can switch between them without reinstalling.

## Build from source

Needs JDK 17, NDK 27.0.12077973 and CMake 3.22.1.

```bash
git clone https://github.com/shardulvs/xnotes-android.git
cd xnotes-android
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
```


## Additions in this fork

- **Browser-style tabs**: keep several notes open at once, each with its own history and editor state.
- **Settings backup**: export settings, fonts, templates and stickers to one file and import them on another device.
- **Handwriting to text**: turn selected handwriting (Portuguese (Brazil), English) into an editable text box.
- **Handwriting to math**: turn selected handwritten formulas — including complex expressions with fractions, integrals and derivatives — into editable LaTeX, offline after a one-time model download.
- **Plain text to equation**: turn typed maths like `int_0^1 x^2 dx` into a formula.
- **Maths keyboard**: a dedicated keyboard for equations and the formula editor.
- **Math Studio**: a scientific calculator, 2D and 3D plots, scalar and vector fields, numerical and symbolic calculus, and unit conversion.
- **File import**: create notes from PDFs, images, txt, md, rtf, html, epub, docx, xlsx, csv and Samsung Notes (.sdocx) — including files shared from other apps.
- **Samsung Notes exchange**: save notes as .sdocx files (pages, ink, flattened shapes and tables, text, images, PDFs) and open Samsung notes back (ink, shapes, lines, text, images, voice memos, PDFs). A .sdocx sitting in the notes folder converts with one tap.
- **Automatic PDF import**: PDFs found in the notes folders and their subfolders become notes on their own, silently.
- **Import and merge**: import a file into an existing note, merge notes, and add images or photos as pages.
- **Read-only reader**: open md, docx, pptx, xlsx, csv and pdf files as they are, or convert them to notes.
- **Reader mode**: a toolbar button that locks a note for reading, with only navigation, search and zoom left in the bar.
- **Find**: search text in the reader and Preferences.
- **Shape snapping**: held strokes also snap to true circles, squares, upright triangles, waves, parabolas, cubics, exponentials and logarithms; toggle it in the pen popup.
- **Tables**: a table shape with row and column bars for selecting, resizing, adding and removing.
- **Function curves**: a Functions page in the shape picker with x^n, roots, logs, exponentials, trig and inverse trig graphs and an editable formula and range.
- **Smooth curve**: a curve shape next to the arrow that runs smoothly through its points.
- **Circles from the centre**: circles grow out from where the drag starts.
- **Mixed page orientation**: mix portrait and landscape pages in one note, kept in PDF export.
- **Default page style**: pick any template and its settings for new notes, in portrait or landscape.
- **Hyperlinks**: add tappable links to canvas items and text.
- **Image cropping**: crop selected images.
- **Eyedropper**: pick a colour from anywhere on screen.
- **Whole selection**: a switch to select only items lying fully inside the band or lasso.
- **Finger tap to select**: a finger tap selects, a finger drag still pans.
- **Move grip**: drag small selections without hitting the resize handles.
- **Readable selection**: the selection colour darkens on light pages and lightens on dark ones.
- **Rotation snapping**: optionally snap rotation to 90 degrees.
- **Sidebar layout**: pinned notes and optional sections in a reorganised sidebar.
- **Home swipes**: swipe right on Home to open the sidebar.
- **Hide dot files**: hide .obsidian, .git and other dot files from the explorer.
- **Hide shapes**: hide single shapes or whole pages of the shape picker in Preferences.
- **Port old notes**: rewrite notes that still hold the fork's old axes, number line, curve and function shapes in today's shapes, with a backup of each file.
