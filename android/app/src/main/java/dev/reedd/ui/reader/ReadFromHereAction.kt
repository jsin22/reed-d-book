package dev.reedd.ui.reader

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem

/**
 * Adds **Read from here** to the text-selection toolbar.
 *
 * Long-press a word, adjust the handles if you like, then start the audiobook from
 * that sentence. This is the reliable way to seek by *place in the book* rather than
 * by position on a scrub bar, and it works independently of the tappable-decoration
 * layer, which depends on Readium rendering an invisible decoration that can still
 * receive a touch.
 *
 * The other menu items (Copy, Share, and anything the system adds) are left alone,
 * so ordinary text selection still behaves normally.
 */
class ReadFromHereAction(private val onSelected: () -> Unit) : ActionMode.Callback {

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.add(Menu.NONE, ITEM_ID, Menu.FIRST, "Read from here")
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.itemId != ITEM_ID) return false
        onSelected()
        // Dismiss the toolbar: the reader has said what they wanted, and leaving the
        // selection highlighted would fight the read-along highlight.
        mode.finish()
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) = Unit

    private companion object {
        /** Arbitrary but distinct, so the item can be told apart from Copy or Share. */
        const val ITEM_ID = 0x52454144 // "READ"
    }
}
