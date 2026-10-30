package com.xnotes.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.toArgb
import com.dk.kuiver.renderer.KuiverViewer
import com.dk.kuiver.renderer.KuiverViewerConfig
import com.dk.kuiver.SelectionMode
import com.dk.kuiver.model.buildKuiver
import com.dk.kuiver.model.KuiverEdge
import com.dk.kuiver.model.KuiverNode
import com.dk.kuiver.model.layout.LayoutConfig
import com.dk.kuiver.model.layout.LayoutDirection
import com.dk.kuiver.renderer.KuiverInteractionCallbacks
import com.dk.kuiver.rememberKuiverViewerState
import com.dk.kuiver.ui.EdgeContentWithLabel
import com.dk.kuiver.ui.EdgeLabelStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.links.GraphLayout
import com.xnotes.core.model.NoteLinkItem
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Graph: an overview of which files link to which. A root file is picked (or all
 * files shown), nodes open their note on tap, and the view switches between a
 * force-directed graph and a tree from the root — mirroring the reference app's
 * graph screen, over the SAF link index instead of plain paths.
 */
@Composable
internal fun GraphPane(
    editor: Editor,
    sidebarOpen: Boolean,
    onShowSidebar: () -> Unit,
    onBackToHome: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val root = editor.browseRoot
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableIntStateOf(0) }
    var notes by remember { mutableStateOf<List<Editor.GraphNote>?>(null) }
    var graph by remember { mutableStateOf<Editor.LinkGraph?>(null) }
    // Starts true so the very first frame already shows the working state instead of
    // flashing an error; there is no full-screen spinner anywhere on this screen.
    var scanning by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    /** (Re)build the graph: list fast, paint the cached index at once, then rescan. */
    fun reload() {
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            scanning = true
            error = null
            progress = null
            try {
                // Instant first paint straight from the index: no folder walk, no file reads.
                graph = withContext(Dispatchers.IO) { editor.cachedLinkGraph() }
                val listed = withContext(Dispatchers.IO) { editor.listGraphNotes() }
                if (listed == null) {
                    error = context.getString(R.string.graph_failed)
                    return@launch
                }
                notes = listed
                val fresh = withContext(Dispatchers.IO) {
                    editor.refreshLinkGraph(listed) { done, total -> progress = done to total }
                }
                graph = fresh
                if (fresh.partial) error = context.getString(R.string.graph_partial)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: context.getString(R.string.graph_failed)
                if (graph == null) graph = Editor.LinkGraph(emptyList(), emptyMap())
            } finally {
                scanning = false
                progress = null
            }
        }
    }
    LaunchedEffect(root, refreshTick) {
        if (root == null) {
            notes = null
            graph = null
            scanning = false
        } else {
            reload()
        }
    }

    var rootUri by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }
    var treeMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selectedUri by remember { mutableStateOf<String?>(null) }

    val files = remember(graph) { (graph?.files.orEmpty()).associate { it.uri to it.name } }
    // Identity is the document key (authority + id), not the raw URI string: the same
    // file picked through different URI forms (tree child vs open-document) still
    // resolves to one node, so no link is ever dropped by a URI mismatch.
    val keyToUri = remember(files) { files.keys.associateBy { editor.graphKey(it) } }
    fun resolveTarget(source: String, target: String): String? {
        if (target.isEmpty()) return source // a blank target is a self-link
        if (target in files) return target
        return keyToUri[editor.graphKey(target)]
    }
    val adjacency = remember(graph, files) {
        val adj = HashMap<String, List<String>>()
        for ((source, edges) in graph?.edges.orEmpty()) {
            if (source !in files) continue
            adj[source] = edges.mapNotNull { resolveTarget(source, it.targetUri) }.distinct()
        }
        adj
    }
    LaunchedEffect(files) {
        if (rootUri != null && rootUri !in files) {
            rootUri = null
            treeMode = false
        }
        if (selectedUri != null && selectedUri !in files) selectedUri = null
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!sidebarOpen) {
                Tooltip(stringResource(R.string.show_sidebar)) {
                    IconButton(onClick = onShowSidebar) {
                        Icon(XnotesIcons.menu, stringResource(R.string.show_sidebar), tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                    }
                }
            }
            Text(stringResource(R.string.graph), color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, modifier = Modifier.weight(1f))
            TextButton(onClick = onBackToHome) { Text(stringResource(R.string.home)) }
            IconButton(onClick = { refreshTick++ }, enabled = !scanning) {
                Icon(XnotesIcons.redo, stringResource(R.string.graph_refresh), tint = palette.text.toComposeColor(), modifier = Modifier.size(22.dp))
            }
        }
        if (root == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.graph_no_folder), color = palette.textDim.toComposeColor())
            }
            return@Column
        }
        // --- root search + view toggles: always visible, never gated on loading ---
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                ExplorerSearchField(query, { query = it }, expandedWidth = 320.dp)
                // Inline suggestions, not a popup menu: a popup steals window focus and
                // force-closes the keyboard while typing. This list never takes focus.
                val matches = remember(query, files) {
                    val q = query.trim().lowercase()
                    if (q.isEmpty()) emptyList()
                    else files.entries.filter { it.value.lowercase().contains(q) }.sortedBy { it.value }.take(8)
                }
                if (query.isNotBlank() && matches.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 4.dp)
                            .clip(RoundedCornerShape(8.dp)).background(palette.surface.toComposeColor())
                            .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(8.dp))
                            .padding(vertical = 4.dp),
                    ) {
                        for (m in matches) {
                            Text(
                                m.value, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = palette.text.toComposeColor(), fontSize = 14.sp,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    rootUri = m.key
                                    selectedUri = m.key
                                    showAll = false
                                    query = ""
                                }.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            FilterChip(
                selected = showAll && rootUri == null,
                onClick = {
                    if (showAll && rootUri == null) showAll = false
                    else {
                        showAll = true
                        rootUri = null
                        treeMode = false
                    }
                },
                label = { Text(stringResource(R.string.graph_all)) },
            )
            FilterChip(
                selected = treeMode,
                enabled = rootUri != null,
                onClick = { treeMode = !treeMode },
                label = { Text(if (treeMode) stringResource(R.string.graph_tree) else stringResource(R.string.graph_graph)) },
            )
        }
        rootUri?.let { r ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.graph_root, files[r] ?: r),
                    color = palette.textDim.toComposeColor(), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    rootUri = null
                    treeMode = false
                    showAll = true
                }) { Text(stringResource(R.string.clear)) }
            }
        }
        Spacer(Modifier.height(4.dp))
        // Content gates only affect the canvas area below: search and toggles above
        // stay visible and usable at all times.
        val data = graph
        if (data == null) {
            // No full-screen spinner anywhere: while the index loads, the search
            // and toggles above stay usable and only quiet text shows here.
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val (done, total) = progress ?: (0 to 0)
                    Text(
                        when {
                            error != null -> error!!
                            scanning && total > 0 -> stringResource(R.string.graph_scanning, done, total)
                            scanning -> stringResource(R.string.graph_rescanning)
                            else -> stringResource(R.string.graph_no_notes)
                        },
                        color = palette.textDim.toComposeColor(), fontSize = 14.sp,
                    )
                    if (!scanning) TextButton(onClick = { refreshTick++ }) { Text(stringResource(R.string.retry)) }
                }
            }
            return@Column
        }
        if (data.files.isEmpty() && !scanning) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.graph_no_notes), color = palette.textDim.toComposeColor())
            }
            return@Column
        }
        // Status: live scan progress, a partial-scan notice, or a rescan error.
        if (scanning || error != null || data.partial) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(8.dp)).background(palette.surface.toComposeColor())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                val (done, total) = progress ?: (0 to 0)
                Text(
                    when {
                        scanning && total > 0 -> stringResource(R.string.graph_scanning, done, total)
                        scanning -> stringResource(R.string.graph_rescanning)
                        else -> error ?: stringResource(R.string.graph_partial)
                    },
                    color = palette.textDim.toComposeColor(), fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (!scanning) TextButton(onClick = { refreshTick++ }) { Text(stringResource(R.string.retry)) }
            }
        }

        // --- canvas: rendered by the vendored kuiver graph package (hierarchical
        // tree or force-directed), which measures real node content and routes
        // arrowed, labeled edges. See app/src/main/java/com/dk/kuiver/README.md.
        val useTree = treeMode && rootUri != null && rootUri in files
        val visible = remember(rootUri, files, adjacency, showAll) {
            GraphLayout.visibleFrom(rootUri, files.keys, adjacency, showAll)
        }
        // Remembered: a fresh list every frame would restart the layout below forever.
        val cappedNodes: Pair<List<String>, Int> = remember(visible, rootUri, selectedUri) {
            if (visible.size > MAX_GRAPH_NODES) {
                // Keep the root (and the selection) while cutting the tail, like the reference cap.
                val keep = LinkedHashSet<String>()
                rootUri?.let { keep.add(it) }
                selectedUri?.let { keep.add(it) }
                for (u in visible) {
                    if (keep.size >= MAX_GRAPH_NODES) break
                    keep.add(u)
                }
                keep.toList() to (visible.size - keep.size)
            } else {
                visible to 0
            }
        }
        val capped = cappedNodes.first
        val truncated = cappedNodes.second
        val visibleSet = remember(capped) { capped.toSet() }
        // One entry per link; parallel links between the same pair fan out by id.
        val linkEdges = remember(capped, graph) {
            val out = ArrayList<GraphLinkEdge>()
            var k = 0
            for (from in capped) {
                for (e in graph?.edges?.get(from).orEmpty()) {
                    val to = resolveTarget(from, e.targetUri) ?: continue
                    if (to !in visibleSet) continue
                    out.add(GraphLinkEdge(from, to, "$from->$to#$k", NoteLinkItem.formatPages(e.targetPages), e.colorArgb))
                    k++
                }
            }
            out
        }
        val kGraph = remember(capped, linkEdges) {
            buildKuiver {
                for (u in capped) addNode(KuiverNode(u))
                for (e in linkEdges) addEdge(KuiverEdge(e.from, e.to, id = e.id))
            }
        }
        val layoutConfig = remember(useTree, linkEdges.size) {
            if (useTree) LayoutConfig.Hierarchical(
                direction = LayoutDirection.VERTICAL,
                levelSpacing = 130.dp,
                nodeSpacing = 80.dp,
            )
            else LayoutConfig.ForceDirected(
                iterations = (360 - linkEdges.size * 2).coerceIn(60, 320),
            )
        }
        val surface = palette.surface.toComposeColor()
        val surfaceHigh = palette.accent.toComposeColor().copy(alpha = 0.16f)
        val onSurface = palette.text.toComposeColor()
        val dim = palette.textDim.toComposeColor()
        val accent = palette.accent.toComposeColor()
        val border = palette.border.toComposeColor()
        val edgeMeta = remember(linkEdges) { linkEdges.associate { it.id to (it.label to it.colorArgb) } }
        key(useTree) {
            val viewerState = rememberKuiverViewerState(initialKuiver = kGraph, layoutConfig = layoutConfig)
            LaunchedEffect(kGraph) { viewerState.updateKuiver(kGraph) }
            LaunchedEffect(selectedUri) {
                val sel = selectedUri
                if (sel == null || sel !in visibleSet) viewerState.interaction.clearSelection()
                else runCatching { viewerState.interaction.select(sel) }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                KuiverViewer(
                    state = viewerState,
                    modifier = Modifier.fillMaxSize(),
                    config = KuiverViewerConfig(
                        fitToContent = true,
                        selectionMode = SelectionMode.SINGLE,
                        nodeDragEnabled = true,
                        minScale = 0.2f,
                        maxScale = 3f,
                    ),
                    callbacks = KuiverInteractionCallbacks(
                        onNodeClick = { selectedUri = it.id },
                        onCanvasClick = { selectedUri = null },
                    ),
                    nodeContent = { node ->
                        val name = files[node.id] ?: node.id
                        val isRoot = node.id == rootUri
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(14.dp))
                                .background(if (isRoot || isSelected) surfaceHigh else surface)
                                .border(
                                    if (isRoot || isSelected) 2.dp else 1.dp,
                                    if (isRoot) accent else if (isSelected) accent.copy(alpha = 0.7f) else border,
                                    RoundedCornerShape(14.dp),
                                )
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 150.dp),
                                color = if (isRoot) accent else onSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    },
                    edgeContent = { edge, from, to ->
                        val (label, colorArgb) = edgeMeta[edge.id] ?: ("" to dim.toArgb())
                        EdgeContentWithLabel(
                            from = from,
                            to = to,
                            label = label.ifEmpty { null },
                            labelStyle = EdgeLabelStyle(
                                textColor = onSurface,
                                backgroundColor = surface.copy(alpha = 0.92f),
                                fontSize = 12.sp,
                            ),
                            color = Color(colorArgb),
                            showArrow = true,
                            isSelfLoop = edge.fromId == edge.toId,
                            enableCurve = useTree,
                        )
                    },
                )
                if (truncated > 0) {
                    Text(
                        stringResource(R.string.graph_truncated, capped.size, visible.size),
                        color = palette.textDim.toComposeColor(), fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(12.dp)).background(surface).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                IconButton(
                    onClick = { scope.launch { viewerState.centerGraph() } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp)).background(surface),
                ) {
                    Icon(XnotesIcons.fit, stringResource(R.string.graph_fit), tint = dim, modifier = Modifier.size(20.dp))
                }
            }
        }
        // --- selection details: every file it links to ---
        selectedUri?.let { sel ->
            val name = files[sel] ?: sel
            val outgoing = remember(sel, graph) { graph?.edges?.get(sel).orEmpty() }
            Column(
                Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp))
                    .background(palette.surface.toComposeColor()).border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontWeight = FontWeight.SemiBold, color = palette.text.toComposeColor(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        rootUri = sel
                        showAll = false
                    }) { Text(stringResource(R.string.graph_from_here)) }
                    TextButton(onClick = { onOpenFile(sel) }) { Text(stringResource(R.string.open)) }
                    TextButton(onClick = { selectedUri = null }) { Text(stringResource(R.string.clear)) }
                }
                if (outgoing.isEmpty()) {
                    Text(stringResource(R.string.graph_no_outgoing), color = palette.textDim.toComposeColor(), fontSize = 13.sp)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().height((outgoing.size.coerceAtMost(4) * 40).dp)) {
                        items(outgoing, key = { "${it.targetUri}#${it.sourcePage}#${it.label}" }) { edge ->
                            // A blank target is a self-link: it points at this file.
                            val targetUri = resolveTarget(sel, edge.targetUri)
                            val targetKnown = targetUri != null
                            Row(
                                Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = targetKnown) {
                                        targetUri?.let {
                                            selectedUri = it
                                            if (it !in visibleSet) {
                                                rootUri = it
                                                showAll = false
                                            }
                                        }
                                    }.padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(edge.colorArgb)))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (targetKnown) files[targetUri!!]!! else edge.targetName.ifEmpty { edge.targetUri },
                                    color = palette.text.toComposeColor(), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    NoteLinkItem.formatPages(edge.targetPages),
                                    color = palette.textDim.toComposeColor(), fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_GRAPH_NODES = 250

/** One drawn edge between two files: its endpoints, unique id, label and color. */
private data class GraphLinkEdge(
    val from: String,
    val to: String,
    val id: String,
    val label: String,
    val colorArgb: Int,
)
