package dev.reedd

/**
 * Loads a canned server response from `src/test/resources/fixtures`.
 *
 * These files are not hand-written: they are produced by `server/app/store.py`
 * itself (see `app/README.md`), so a change to the manifest shape shows up as a
 * failing test here rather than as a field that silently reads null on device.
 */
object Fixtures {
    fun read(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing fixture: fixtures/$name"
        }.use { it.readBytes().decodeToString() }
}
