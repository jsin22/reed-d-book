package dev.reedd.domain

/**
 * Decides when the page should move to keep up with the audio.
 *
 * Pulled out as a plain state machine with no Android or Readium dependency,
 * because the rules are where the annoying bugs live and this is the only part of
 * the follow behaviour that can be tested without a device.
 *
 * The problem it solves: auto-advancing the page fights anyone who scrolls back to
 * re-read something. So dragging the page disengages following, and the UI offers
 * an explicit way back. Disengaging must *not* happen when the app itself moved the
 * page, which is why [onNavigated] exists.
 */
class FollowController(following: Boolean = true) {

    var isFollowing: Boolean = following
        private set

    /** The sentence the page was last moved to, so it is not moved there twice. */
    private var navigatedTo: Int? = null

    /**
     * The sentence being spoken changed.
     *
     * @return true if the page should move to it.
     */
    fun onSentenceChanged(index: Int): Boolean {
        if (!isFollowing || index < 0) return false
        if (navigatedTo == index) return false
        navigatedTo = index
        return true
    }

    /**
     * The user dragged the page.
     *
     * Stops following, so the audio can keep playing while they look elsewhere.
     */
    fun onUserDragged() {
        isFollowing = false
    }

    /** Follow again, and move to the current sentence even if it was the last target. */
    fun resume() {
        isFollowing = true
        navigatedTo = null
    }

    fun stop() {
        isFollowing = false
    }

    /**
     * Called after the app moves the page itself.
     *
     * Records the target so the same sentence is not navigated to repeatedly, and
     * distinguishes "we moved the page" from "the user did".
     */
    fun onNavigated(index: Int) {
        navigatedTo = index
    }

    /**
     * Playback jumped somewhere far away (a chapter tap, a scrub).
     *
     * Following resumes: an explicit jump is a request to be taken there, unlike
     * audio simply rolling on while the reader looks at another page.
     */
    fun onSeekRequested() {
        resume()
    }
}
