package com.custom.astrion.tvhelper

/**
 * The latest [NowPlaying] and cover, shared between the media listener (writer) and the web
 * server (readers). [version] goes up on every change, so the remote can wait for the next one
 * ([await], a long poll) instead of asking every second.
 */
class NowPlayingStore {
    private val lock = Object()

    @Volatile var current: NowPlaying = NowPlaying()
        private set

    @Volatile var version: Long = 1
        private set

    /** The cover as JPEG, and the version it changed at (the remote caches by it). */
    @Volatile var artJpeg: ByteArray? = null
        private set

    @Volatile var artVersion: Long = 0
        private set

    /** Records [next]; [art] replaces the cover only when [artChanged]. Wakes the waiting requests if anything changed. */
    fun update(next: NowPlaying, art: ByteArray? = null, artChanged: Boolean = false) {
        synchronized(lock) {
            val coverChanged = artChanged && !art.contentEqualsNullable(artJpeg)
            if (next == current && !coverChanged) return
            version++
            current = next
            if (coverChanged) {
                artJpeg = art
                artVersion = version
            }
            lock.notifyAll()
        }
    }

    /** The current state, as soon as its version differs from [since], or after [timeoutMs] anyway. */
    fun await(since: Long, timeoutMs: Long): Long {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (version == since) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                lock.wait(left)
            }
            return version
        }
    }

    /** The `/now-playing` answer for the current state. */
    fun json(): String = synchronized(lock) { current.toJson(version, artJpeg?.let { artVersion }) }

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?) =
        if (this == null) other == null else other != null && contentEquals(other)
}
