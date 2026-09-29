package dev.reedd

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import dev.reedd.ui.theme.ReeddTheme

/**
 * The single activity.
 *
 * [AppCompatActivity], not ComponentActivity: Readium's `EpubNavigatorFragment`
 * is an AppCompat fragment, and the reader screen hosts it inside the Compose
 * tree via a fragment container.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        // Discards the incoming state rather than passing it through: a real,
        // confirmed crash, six separate reports, one stack trace --
        // Unable to start activity ... Fragment$InstantiationException:
        // Unable to instantiate fragment EpubNavigatorFragment: could not
        // find Fragment constructor -- Caused by: NoSuchMethodException:
        // EpubNavigatorFragment.<init> [].
        //
        // Readium's EpubNavigatorFragment has no plain no-arg constructor; it
        // is built with the open book's own Publication/config through a
        // custom FragmentFactory this app installs, but only once a book's
        // reader screen actually composes (ReaderScreen.kt's own
        // remember(state.publication) block) -- well after this onCreate has
        // already returned. Whenever Android recreates this Activity while an
        // EpubNavigatorFragment was previously attached (the process was
        // killed in the background and the user comes back -- routine, not
        // rare -- or any other activity relaunch, confirmed happening from
        // both), FragmentActivity's own onCreate tries to restore it *first*,
        // through the still-default factory, before this app's own code ever
        // runs -- and that restoration is what throws.
        //
        // This app never actually needs that restored fragment instance: the
        // real EpubNavigatorFragment a reader screen uses is always freshly
        // created moments later by Compose's own AndroidFragment, once the
        // book's Publication has loaded and the real factory is installed --
        // so there is nothing to lose by not attempting the restore. The one
        // real trade-off: Compose Navigation's own back stack lives in this
        // same saved state, so a relaunch after true process death now always
        // opens back to the library rather than wherever the reader last was
        // -- reading/playback position themselves are unaffected, since both
        // are persisted straight to the database independent of this.
        super.onCreate(null)
        setContent {
            ReeddTheme {
                ReeddNavHost()
            }
        }
    }
}
