package com.xnotes.canvas

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer

/**
 * One frame of the paged canvas, host-independent: paper, page backgrounds, cached ink, the sharp
 * viewport past the resolution cap, and the live highlighter composite. Hosts paint their own
 * chrome (overlay, scrollbars, badges) on top, then call [finish].
 *
 * Page caches are built on the state's cache thread and blitted when ready, so a frame never waits
 * on rasterization.
 */
object CanvasFramePainter {

    /** Clip bound for room cuts: beyond any page, inside any transform. */
    private const val FAR = 1e9

    /** What the host must do after a frame. */
    class Frame internal constructor(
        internal val cachedPages: Set<Page>,
        /**
         * True when the sharp viewport should be re-rendered once the view has been still for a
         * moment: the host (re)arms its settle timer and then calls [CanvasState.requestSharpViewport].
         * False means cancel any pending settle.
         */
        val sharpSettleNeeded: Boolean,
    )

    /** Paint the document (everything below the interaction overlay) into [r], in viewport pixels. */
    fun paint(r: Renderer, st: CanvasState): Frame {
        // A room made whose page rebuilt to hold it draws as any other again.
        st.roomView?.let { v -> if (!v.live && st.roomSettled(v.page)) st.roomView = null }
        val room = st.roomView
        // Past the resolution cap a sharp render of the view may cover all of it: it is opaque
        // (background, paper, page backgrounds), so nothing under it needs drawing. Scaling the
        // soft page caches up only to hide them was most of a frame's cost on a large screen.
        // A live room drag draws its own shifted layers, so the sharp cover stands down for it.
        val pastCap = st.isPastResolutionCap() && room?.live != true
        val sharp = if (pastCap) st.sharpViewportBlit() else null
        val covered = sharp != null && sharp.dx <= 0.0 && sharp.dy <= 0.0 &&
            sharp.dx + sharp.base.width * sharp.scale >= st.viewportW &&
            sharp.dy + sharp.base.height * sharp.scale >= st.viewportH

        if (!covered) r.fillBackground(Rect(0.0, 0.0, st.viewportW.toDouble(), st.viewportH.toDouble()), st.palette.bg)

        val origin = st.origin()
        r.save()
        r.translate(origin.x, origin.y)
        r.scale(st.zoom, st.zoom)

        val visible = st.visibleContentRect()
        val border = Pen(st.palette.paperBorder, 1.0, cosmetic = true)
        val cachedPages = HashSet<Page>()
        // Paginated mode shows only the current row; neighbours never draw.
        val drawable = st.drawablePageRange()

        for (i in st.document.pages.indices) {
            if (i !in drawable) continue
            val pr = st.pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(visible)) continue
            val page = st.document.pages[i]
            cachedPages.add(page)

            if (room != null && room.page === page) {
                drawRoomPage(r, st, page, pr, room, visible)
                continue
            }
            if (covered) {
                // Hidden under the sharp render; still keep the caches coming for when it moves.
                st.backgroundForOrSchedule(page)
                st.cacheForOrSchedule(page)
                continue
            }
            r.fillRect(pr, st.paperColor(page))
            if (st.pageBorders) r.strokeRect(pr, border)
            st.backgroundForOrSchedule(page)?.let { blitPageSurface(r, st, page, pr, it.surface) }
            // A live caret session lifts the flow out of the ink cache; paint it
            // immediate-mode here (under the ink, over the background) so every
            // keystroke shows without waiting for a cache rebuild.
            if (st.flowLifted) {
                r.withSave {
                    r.clipRect(pr)
                    r.translate(pr.left, pr.top)
                    st.applyPageTransform(r, page)
                    st.paintFlow?.invoke(page, r, st.displayRectToPage(page, visible.translate(-pr.left, -pr.top)))
                }
            }
            st.cacheForOrSchedule(page)?.let { blitPageSurface(r, st, page, pr, it.surface) }
        }
        r.restore()

        // Prefetch the N pages before and after the visible band (N = visible count) into the
        // page cache so scrolling lands on already-rasterized pages. Scheduled after the on-screen
        // pages above so visible content always builds first on the single cache thread, nearest
        // pages first, and skipped during a pinch so the settle-rebuild isn't starved.
        if (!st.zoomingInProgress) {
            st.visiblePageRange()?.let { vis ->
                val n = vis.last - vis.first + 1
                for (d in 1..n) for (j in intArrayOf(vis.last + d, vis.first - d)) {
                    val page = st.document.pages.getOrNull(j) ?: continue
                    if (!cachedPages.add(page)) continue
                    st.backgroundForOrSchedule(page)
                    st.cacheForOrSchedule(page)
                }
            }
        }

        // Past the resolution cap, cover the (soft, capped) page caches with a razor-sharp,
        // full-resolution render of just the viewport. While panning we slide the previous sharp
        // render with the content (so short pans stay sharp) and let the soft cache show only in
        // the strip panning into view; once the view settles we re-render the sharp viewport for
        // the new area. A zoom change drops back to the soft caches until the settle re-render.
        val sharpSettle: Boolean
        if (pastCap) {
            val blit = sharp
            if (blit != null) {
                val dw = blit.base.width * blit.scale
                val dh = blit.base.height * blit.scale
                r.drawRaster(blit.base, Rect(blit.dx, blit.dy, dw, dh))
                // A lifted flow is absent from the sharp ink layer too: draw it live
                // between the sharp base and sharp ink so the stack order holds.
                // Clipped to the blit's own rect: outside it the page loop's live pass
                // already painted the flow, and a second (translucent) chip on top
                // reads as a lighter band while the slid sharp frame settles.
                if (st.flowLifted) {
                    r.withSave {
                        r.clipRect(Rect(blit.dx, blit.dy, dw, dh))
                        r.translate(origin.x, origin.y)
                        r.scale(st.zoom, st.zoom)
                        for (i in st.document.pages.indices) {
                            if (i !in drawable) continue
                            val pr = st.pageRects.getOrNull(i) ?: continue
                            if (!pr.intersects(visible)) continue
                            val page = st.document.pages[i]
                            r.withSave {
                                r.clipRect(pr)
                                r.translate(pr.left, pr.top)
                                st.applyPageTransform(r, page)
                                st.paintFlow?.invoke(page, r, st.displayRectToPage(page, visible.translate(-pr.left, -pr.top)))
                            }
                        }
                    }
                }
                r.drawRaster(blit.ink, Rect(blit.dx, blit.dy, dw, dh))
                // Off the exact rendered view (panned or zoomed): re-render for where we settle.
                sharpSettle = !(blit.scale == 1.0 && blit.dx == 0.0 && blit.dy == 0.0)
            } else {
                sharpSettle = true
            }
        } else {
            sharpSettle = false
            st.clearSharpViewport()
        }

        // Highlighters composite here, over the finished page (paper + background + ink), so
        // their MULTIPLY blend darkens against everything beneath instead of washing it out —
        // matching the live preview. They're few and drawn at screen resolution (so crisp at
        // any zoom); pen/calligraphy ink stays cached underneath.
        r.withSave {
            r.translate(origin.x, origin.y)
            r.scale(st.zoom, st.zoom)
            for (i in st.document.pages.indices) {
                if (i !in drawable) continue
                val pr = st.pageRects.getOrNull(i) ?: continue
                if (!pr.intersects(visible)) continue
                val page = st.document.pages[i]
                // Page-space visible rect, so off-band highlighters on a tall page skip the composite.
                val visLocal = st.displayRectToPage(page, visible.translate(-pr.left, -pr.top))
                if (room?.page === page) continue // cut at its room in drawRoomPage instead
                r.withSave {
                    r.clipRect(pr)
                    r.translate(pr.left, pr.top)
                    st.applyPageTransform(r, page)
                    for (item in page.items) {
                        if (item is Stroke && item.isHighlighterInk() && !st.isLiftedItem(item) &&
                            item.bounds().intersects(visLocal)
                        ) {
                            // Blit the pre-rendered opaque ribbon at the ink's alpha and blend,
                            // instead of re-tessellating the ribbon every frame.
                            val hc = st.highlighterCacheFor(item, page)
                            r.drawRasterBlended(hc.surface, hc.cover, item.renderColor.a / 255.0, item.blendMode)
                        }
                    }
                }
            }
        }
        return Frame(cachedPages, sharpSettle)
    }

    /** End the frame once the host's own layers are drawn: release caches of pages now out of view. */
    fun finish(st: CanvasState, frame: Frame) = st.dropCachesExcept(frame.cachedPages)

    /**
     * The page room is being drawn through (roomView): its old layers cut at the room, the near
     * part where it was and the far part moved on, with paper where room opens. What the cut
     * cannot split is drawn live where it stays. A layer rebuilt since is drawn as it is.
     */
    private fun drawRoomPage(r: Renderer, st: CanvasState, page: Page, pr: Rect, v: RoomView, visible: Rect) {
        r.fillRect(pr, st.paperColor(page))
        // Asked for so a settled room's changed footprint schedules the rebuilds that end it.
        st.backgroundForOrSchedule(page)
        st.cacheForOrSchedule(page)
        val bg = st.rawBackgroundCache(page)
        val ink = st.rawInkCache(page)
        r.withSave {
            r.clipRect(pr)
            r.translate(pr.left, pr.top)
            st.applyPageTransform(r, page)
            if (bg != null) {
                if (bg.cover != v.cover) {
                    r.drawRaster(bg.surface, bg.cover)
                } else if (page.pdfPage == null) {
                    // A plain page's ruling is laid for the page, not carried by what is on it.
                    r.drawRaster(bg.surface, bg.cover)
                } else {
                    drawCut(r, bg.surface, bg.cover, v)
                }
            }
            if (bg == null || bg.cover == v.cover) st.paintRoomPaper?.invoke(v, r)
        }
        val old = ink == null || ink.cover == v.cover
        // What stays put is out of the old ink layer, so it is drawn here until a layer that holds it lands.
        if (old) {
            r.withSave {
                r.clipRect(pr)
                r.translate(pr.left, pr.top)
                st.applyPageTransform(r, page)
                for (item in v.fills) item.paint(r)
            }
        }
        if (ink != null) {
            r.withSave {
                r.clipRect(pr)
                r.translate(pr.left, pr.top)
                st.applyPageTransform(r, page)
                if (ink.cover == v.cover) drawCut(r, ink.surface, ink.cover, v) else r.drawRaster(ink.surface, ink.cover)
            }
        }
        if (old) {
            r.withSave {
                r.clipRect(pr)
                r.translate(pr.left, pr.top)
                st.applyPageTransform(r, page)
                for (item in v.over) item.paint(r)
            }
        }
        // The room page's highlights, cut at the room like the layers under them.
        r.withSave {
            r.clipRect(pr)
            r.translate(pr.left, pr.top)
            st.applyPageTransform(r, page)
            for (item in page.items) {
                if (item !is Stroke || !item.isHighlighterInk() || st.isLiftedItem(item)) continue
                val hc = st.highlighterCacheFor(item, page)
                val alpha = item.renderColor.a / 255.0
                r.withSave {
                    r.clipRect(if (v.sideways) Rect.ltrb(-FAR, -FAR, v.split, FAR) else Rect.ltrb(-FAR, -FAR, FAR, v.split))
                    r.drawRasterBlended(hc.surface, hc.cover, alpha, item.blendMode)
                }
                r.withSave {
                    if (v.sideways) r.translate(v.shift, 0.0) else r.translate(0.0, v.shift)
                    r.clipRect(if (v.sideways) Rect.ltrb(v.resume, -FAR, FAR, FAR) else Rect.ltrb(-FAR, v.resume, FAR, FAR))
                    r.drawRasterBlended(hc.surface, hc.cover, alpha, item.blendMode)
                }
            }
        }
    }

    /** [surface], a page layer built for [cover], drawn cut at [v]'s room: what lay before it in place, what lay past it moved on. */
    private fun drawCut(r: Renderer, surface: RasterSurface, cover: Rect, v: RoomView) {        r.withSave {
            r.clipRect(if (v.sideways) Rect.ltrb(-FAR, -FAR, v.split, FAR) else Rect.ltrb(-FAR, -FAR, FAR, v.split))
            r.drawRaster(surface, cover)
        }
        r.withSave {
            if (v.sideways) r.translate(v.shift, 0.0) else r.translate(0.0, v.shift)
            r.clipRect(if (v.sideways) Rect.ltrb(v.resume, -FAR, FAR, FAR) else Rect.ltrb(-FAR, v.resume, FAR, FAR))
            r.drawRaster(surface, cover)
        }
    }

    /** Blit a page-space cache surface into the page's display rect, rotated per the view. The
     *  surface covers the page's whole footprint (margins included), so it blits at [CanvasState.footprint]. */
    private fun blitPageSurface(r: Renderer, st: CanvasState, page: Page, pr: Rect, surface: RasterSurface) {
        if (st.rotationDeg == 0) {
            r.drawRaster(surface, pr)
            return
        }
        r.withSave {
            r.translate(pr.left, pr.top)
            st.applyPageTransform(r, page)
            r.drawRaster(surface, st.footprint(page))
        }
    }
}
