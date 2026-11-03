package com.xnotes.canvas

import com.xnotes.core.FakeRasterSurface
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Device-like churn: background builds racing UI-thread duplication, selection
 *  and commits. The page must never wedge blank: every build either lands or
 *  is superseded, and the cache eventually holds the model. */
class DenseAsyncChurnTest {

    private fun stroke(x: Double, y: Double): Stroke {
        val s = Stroke(
            Tool.PEN, ToolDefaults.configFor(Tool.PEN),
            mutableListOf(Sample(x, y, 1.0), Sample(x + 8.0, y + 3.0, 0.8), Sample(x + 16.0, y, 0.6)),
        )
        s.finished = true
        return s
    }

    @Test fun churnNeverWedgesTheCache() {
        val page = Page(1240.0, 1754.0)
        var x = 20.0
        var y = 20.0
        repeat(200) {
            page.items.add(stroke(x, y))
            x += 37.0
            if (x > 1180.0) {
                x = 20.0
                y += 41.0
                if (y > 1700.0) y = 20.0
            }
        }
        val doc = Document(mutableListOf(page))
        val st = CanvasState(doc, FakeSurfaceFactory(), Palette.DEFAULT).apply {
            viewportW = 1080
            viewportH = 2400
            relayout()
        }
        val history = History()
        val ctrl = InteractionController(st, history, FakeTextMeasurer(), requestRender = {})
        val ex = Executors.newSingleThreadExecutor()
        val posted = LinkedBlockingQueue<Runnable>()
        st.runAsync = { work -> ex.execute(work) }
        st.postToMain = { work -> posted.add(work); }
        st.onCacheReady = {}
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Throwable>()
        // Pump the main queue like the looper would, until told to stop.
        val pumper = Thread {
            try {
                while (!stop.get()) {
                    val work = posted.poll(50, TimeUnit.MILLISECONDS) ?: continue
                    work.run()
                }
                // Drain the rest.
                while (true) posted.poll()?.run() ?: break
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }.also { it.isDaemon = true; it.start() }

        fun pumpCache(timeoutMs: Long): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (System.nanoTime() < deadline) {
                if (st.cacheForOrSchedule(page) != null) return true
                Thread.sleep(5)
            }
            return st.cacheForOrSchedule(page) != null
        }

        try {
            assertTrue("warm cache lands", pumpCache(10000))
            // Duplicate-all several times while frames keep coming, like the report.
            repeat(4) {
                ctrl.selectAll()
                ctrl.duplicateSelection()
                st.cacheForOrSchedule(page)
                Thread.sleep(20)
            }
            println("items=${page.items.size}")
            // Commit fresh strokes the way pen-up does.
            repeat(5) {
                val s = stroke(30.0 + it * 40.0, 30.0)
                page.items.add(s)
                st.appendToCache(page, s)
            }
            // Close + reopen over the same model.
            ctrl.clearSelection()
            st.invalidateAllCaches()
            st.relayout()
            assertTrue("cache lands after churned edits", pumpCache(15000))
            val entry = st.cacheForOrSchedule(page)
            assertTrue(entry != null)
            val painter = (entry!!.surface as FakeRasterSurface).painter
            println("runs=${painter.ribbonRuns.size} items=${page.items.size}")
            assertTrue(
                "cache must hold every stroke after churn, runs=${painter.ribbonRuns.size} items=${page.items.size}",
                painter.ribbonRuns.size >= page.items.size,
            )
            failure.get()?.let { throw it }
        } finally {
            stop.set(true)
            ex.shutdownNow()
        }
    }
}
