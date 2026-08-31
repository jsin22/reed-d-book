package dev.reedd.ui.reader

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.readium.r2.navigator.Selection

/**
 * Adds Read from here / Definition / Notes to the native text-selection
 * toolbar, driven by the same [WordMenuTarget.Selection] state a tap's
 * Compose [WordMenu] renders from -- so a long-press offers the same
 * actions, with the same conditional rules (Definition omitted for a
 * multi-word selection). Copy/Share stay the system's own.
 *
 * A long-press selection deliberately does **not** get [WordMenu] itself,
 * unlike a tap -- four things learned the hard way, on a real device,
 * across as many attempts to unify the two:
 *
 * 1. Returning `false` from [onCreateActionMode] to suppress the native
 *    toolbar outright (in favor of [WordMenu]) killed the underlying
 *    selection entirely -- no highlight, no drag handles, nothing. This
 *    app's Readium/WebView combination ties selection to a *live*
 *    `ActionMode`, not the independent systems plain Android docs suggest.
 * 2. [Menu.clear] before adding items fights Chromium's own menu population
 *    instead of coexisting with it -- the result was the *default* system
 *    selection menu (Select All / Share / Web Search / …) showing on the
 *    first long-press instead of anything this class added.
 * 3. Reacting to [onDestroyActionMode] by clearing the selection broke it:
 *    Chromium appears to destroy and recreate the `ActionMode` as part of
 *    settling a drag, indistinguishable here from a genuine dismissal --
 *    the highlight vanished the moment a drag was released.
 * 4. [ActionMode.hide] -- an attempt at keeping the mode alive but hiding
 *    just its toolbar, so [WordMenu] could stand in for it -- took the drag
 *    handles down with it too. Selection, its handles, and the toolbar are
 *    evidently one bundled system here, not independently controllable
 *    pieces, at least through any hook found so far.
 *
 * Given that, this class only ever adds/removes *its own* item ids in
 * [rebuildOwnItems], by number, and never touches anything else the native
 * toolbar shows -- the one design that has actually worked end to end
 * (highlight, drag, release, and a menu, all at once) on a real device.
 */
class SelectionMenuAction(
    private val scope: CoroutineScope,
    private val getSelection: suspend () -> Selection?,
    private val onSelectionMade: (Selection) -> Unit,
    private val currentTarget: () -> WordMenuTarget.Selection?,
    private val onReadFromHere: () -> Unit,
    private val onDefine: () -> Unit,
    private val onNotes: () -> Unit,
) : ActionMode.Callback {

    /** A refresh that resolves to the same (href, text) as last time must
     *  not invalidate() again, or onPrepareActionMode -> refresh ->
     *  invalidate would recurse forever. */
    private var lastKey: Pair<String, String>? = null

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        refresh(mode)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        rebuildOwnItems(menu)
        refresh(mode)
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        when (item.itemId) {
            ID_READ_FROM_HERE -> {
                onReadFromHere()
                mode.finish()
            }
            ID_DEFINE -> onDefine()
            ID_NOTES -> onNotes()
            else -> return false
        }
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) = Unit

    private fun rebuildOwnItems(menu: Menu) {
        // Remove-then-conditionally-re-add rather than clear(): touches only
        // this class's own item ids, leaving Copy/Share/Select All/whatever
        // else Chromium puts there completely alone. removeItem is a no-op
        // if the id is not present, so this is safe to call every time.
        menu.removeItem(ID_READ_FROM_HERE)
        menu.removeItem(ID_DEFINE)
        menu.removeItem(ID_NOTES)
        val target = currentTarget() ?: return
        if (target.canReadFromHere) menu.add(Menu.NONE, ID_READ_FROM_HERE, Menu.FIRST, "Read from here")
        if (target.canDefine) menu.add(Menu.NONE, ID_DEFINE, Menu.FIRST + 1, "Definition")
        menu.add(Menu.NONE, ID_NOTES, Menu.FIRST + 2, "Notes")
    }

    private fun refresh(mode: ActionMode) {
        scope.launch {
            val selection = getSelection() ?: return@launch
            val key = selection.locator.href.toString() to selection.locator.text.highlight.orEmpty()
            if (key == lastKey) return@launch
            lastKey = key
            onSelectionMade(selection)
            mode.invalidate()
        }
    }

    private companion object {
        const val ID_READ_FROM_HERE = 1
        const val ID_DEFINE = 2
        const val ID_NOTES = 3
    }
}
