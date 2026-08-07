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
        super.onCreate(savedInstanceState)
        setContent {
            ReeddTheme {
                ReeddNavHost()
            }
        }
    }
}
