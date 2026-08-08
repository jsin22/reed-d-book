package dev.reedd.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that stop auto-advance fighting the reader.
 */
class FollowControllerTest {

    @Test
    fun `following moves the page when the sentence changes`() {
        val follower = FollowController()
        assertTrue(follower.onSentenceChanged(0))
        assertTrue(follower.onSentenceChanged(1))
    }

    @Test
    fun `the same sentence does not move the page twice`() {
        // The poll loop reports the current sentence repeatedly; only a change
        // should navigate, or the page would be re-scrolled several times a second.
        val follower = FollowController()
        assertTrue(follower.onSentenceChanged(3))
        assertFalse(follower.onSentenceChanged(3))
        assertFalse(follower.onSentenceChanged(3))
    }

    @Test
    fun `before the first sentence nothing moves`() {
        assertFalse(FollowController().onSentenceChanged(-1))
    }

    @Test
    fun `dragging the page stops the audio dragging it back`() {
        val follower = FollowController()
        follower.onSentenceChanged(1)

        follower.onUserDragged()

        assertFalse(follower.isFollowing)
        // The audio keeps playing and the sentence keeps changing, but the page
        // stays where the reader put it.
        assertFalse(follower.onSentenceChanged(2))
        assertFalse(follower.onSentenceChanged(3))
    }

    @Test
    fun `resuming follows again and moves to the current sentence`() {
        val follower = FollowController()
        follower.onSentenceChanged(5)
        follower.onUserDragged()

        follower.resume()

        assertTrue(follower.isFollowing)
        // Even though 5 was the last target, resuming has to move there again --
        // the reader has scrolled somewhere else since.
        assertTrue(follower.onSentenceChanged(5))
    }

    @Test
    fun `an explicit seek resumes following`() {
        // Tapping a sentence or scrubbing is a request to be taken there, unlike
        // audio simply rolling on while the reader looks at another page.
        val follower = FollowController()
        follower.onUserDragged()
        assertFalse(follower.isFollowing)

        follower.onSeekRequested()

        assertTrue(follower.isFollowing)
        assertTrue(follower.onSentenceChanged(9))
    }

    @Test
    fun `stopping is not the same as never having followed`() {
        val follower = FollowController()
        follower.stop()
        assertFalse(follower.isFollowing)
        assertFalse(follower.onSentenceChanged(1))

        follower.resume()
        assertTrue(follower.onSentenceChanged(1))
    }

    @Test
    fun `a controller can start detached`() {
        val follower = FollowController(following = false)
        assertFalse(follower.isFollowing)
        assertFalse(follower.onSentenceChanged(0))
    }

    @Test
    fun `recording a navigation prevents an immediate repeat`() {
        // Called after the app moves the page itself, which is what distinguishes
        // "we navigated" from "the user did".
        val follower = FollowController()
        follower.onNavigated(4)
        assertFalse(follower.onSentenceChanged(4))
        assertTrue(follower.onSentenceChanged(5))
    }

    @Test
    fun `going back to an earlier sentence still moves the page`() {
        // A backwards scrub, or "previous sentence".
        val follower = FollowController()
        assertTrue(follower.onSentenceChanged(10))
        assertTrue(follower.onSentenceChanged(9))
        assertTrue(follower.onSentenceChanged(10))
    }
}
