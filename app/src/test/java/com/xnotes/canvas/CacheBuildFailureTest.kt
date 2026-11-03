package com.xnotes.canvas

import com.xnotes.core.FakeRasterSurface
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.SurfaceFactory
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A background cache build that dies must never wedge its page blank forever:
 * the pending flag is cleared and a repaint is requested, so the next frame
 * reschedules and the ink lands. Newly committed strokes vanish on release
 * exactly when a stuck pending build turns every later frame away.
 */
class CacheBuildFailureTest {

    private fun dot(x: Double, y: Double): Stroke =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(x, y, 1.0)))

    private fun state(factory: SurfaceFactory): CanvasState {
        val page = Page(1240.0, 1754.0, mutableListOf(dot(50.0, 50.0), dot(200.0, 200.0)))
        val doc = Document(mutableListOf(page))
        return CanvasState(doc, factory, Palette.DEFAULT).apply {
            viewportW = 1080
            viewportH = 2400
            relayout()
        }
    }

    /** Fails the first surface creation, then behaves. */
    private class FlakyFactory : SurfaceFactory {
        var failuresLeft = 1
        val created = mutableListOf<FakeRasterSurface>()
        override fun create(widthPx: Int, heightPx: Int, devicePixelRatio: Double): RasterSurface {
            if (failuresLeft > 0) {
                failuresLeft--
                throw OutOfMemoryError("simulated surface failure")
            }
            return FakeRasterSurface(widthPx, heightPx, devicePixelRatio).also { created += it }
        }
    }

    @Test fun dyingBuildClearsPendingAndLandsOnRetry() {
        val factory = FlakyFactory()
        val st = state(factory)
        val page = st.document.pages[0]
        val ex = Executors.newSingleThreadExecutor()
        try {
            val posted = LinkedBlockingQueue<Runnable>()
            st.runAsync = { work -> ex.execute(work) }
            st.postToMain = { work -> posted.add(work) }
            var renders = 0
            st.onCacheReady = { renders++ }

            // First frame: schedules a build that dies on the background thread.
            assertTrue(st.cacheForOrSchedule(page) == null)
            // Drain the dead build's callback like the main looper would.
            val dead = posted.poll(10, TimeUnit.SECONDS) ?: error("no callback posted")
            dead.run()
            // The page must not be stuck pending: the next frame reschedules.
            st.cacheForOrSchedule(page)
            // Drain until a good build lands.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var landed = false
            while (System.nanoTime() < deadline) {
                val work = posted.poll(500, TimeUnit.MILLISECONDS) ?: continue
                work.run()
                val cur = st.cacheForOrSchedule(page)
                if (cur != null) {
                    landed = true
                    break
                }
            }
            assertTrue("cache must land after a failed build, renders=$renders", landed)
            val entry = st.cacheForOrSchedule(page)!!
            val painter = (entry.surface as FakeRasterSurface).painter
            assertTrue(
                "landed cache must hold the page ink, runs=${painter.ribbonRuns.size}",
                painter.ribbonRuns.size >= 2,
            )
        } finally {
            ex.shutdownNow()
        }
    }

    @Test fun committedStrokeLandsAfterFailedBuild() {
        val factory = FlakyFactory()
        val st = state(factory)
        val page = st.document.pages[0]
        val ex = Executors.newSingleThreadExecutor()
        try {
            val posted = LinkedBlockingQueue<Runnable>()
            st.runAsync = { work -> ex.execute(work) }
            st.postToMain = { work -> posted.add(work) }
            st.onCacheReady = {}
            // Poison the first build, drain its (failed) callback.
            assertTrue(st.cacheForOrSchedule(page) == null)
            posted.poll(10, TimeUnit.SECONDS)?.run()
            // Commit a stroke the way pen-up does: appended into the cache if
            // usable, else the page is invalidated and its rebuild scheduled.
            val s = Stroke(
                Tool.PEN, ToolDefaults.configFor(Tool.PEN),
                mutableListOf(Sample(10.0, 10.0, 1.0), Sample(60.0, 60.0, 1.0)),
            ).also { it.finished = true }
            page.items.add(s)
            st.appendToCache(page, s)
            // Pump frames until the cache is usable again.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var usable: Boolean
            do {
                while (true) {
                    val work = posted.poll(100, TimeUnit.MILLISECONDS) ?: break
                    work.run()
                }
                usable = st.cacheForOrSchedule(page) != null
            } while (!usable && System.nanoTime() < deadline)
            assertTrue("cache must become usable again", usable)
            val painter = (st.cacheForOrSchedule(page)!!.surface as FakeRasterSurface).painter
            assertTrue(
                "committed stroke must be in the cache, runs=${painter.ribbonRuns.size}",
                painter.ribbonRuns.size >= 3,
            )
        } finally {
            ex.shutdownNow()
        }
    }
}
