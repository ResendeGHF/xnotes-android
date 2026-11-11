package com.xnotes.settings

import com.xnotes.core.tools.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolbarLegacyPenAliasTest {
    @Test fun legacyGroupKeepsAVisiblePenWhenTheRegularPenWasHidden() {
        val layout = ToolbarLayout(listOf(ToolbarSection(listOf(ToolbarEntry(ToolbarItem.PEN, false)))))
        val profiles = ToolbarWorkspaces.fromLegacy(layout, ToolbarLayout.CANVAS_DEFAULT)
        val json = ToolbarWorkspaceJson.write(profiles)
        json.getJSONObject("note").getJSONArray("bars").getJSONObject(0).getJSONArray("sections")
            .getJSONObject(0).getJSONArray("entries").put(JSONObject().put("id", "unified_pen").put("visible", true))
        val entries = ToolbarWorkspaceJson.read(json)!!.note.bars.single().layout.sections.flatMap { it.entries }
        // Downstream preserves UNIFIED_PEN as its own item (not merged to PEN).
        assertTrue(entries.any { it.item == ToolbarItem.UNIFIED_PEN && it.visible })
        assertTrue(entries.any { it.item == ToolbarItem.PEN })
    }
}
