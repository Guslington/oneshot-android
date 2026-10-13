package com.oneshotchat.android.crypto

/**
 * Tripcodes: show a display name with the shortest fingerprint prefix needed to tell
 * apart the people in view who share that name (like git short hashes).
 *
 * "In view" = everyone present in the room plus the authors of messages on screen.
 * A tripcode never proves identity; it only disambiguates names locally.
 */
object Tripcode {

    /** A person in view: their display name and full fingerprint. */
    data class Person(val name: String, val fingerprint: String)

    /**
     * Compute the display label for [target] among everyone in [inView].
     * Returns just the name when unique, else "name#<shortest-distinguishing-prefix>".
     */
    fun label(target: Person, inView: Collection<Person>): String {
        val sameName = inView.filter { it.name == target.name }
            .associateBy { it.fingerprint } // de-dupe identical fingerprints
            .values
        if (sameName.size <= 1) return target.name

        val others = sameName.filter { it.fingerprint != target.fingerprint }
        var len = 1
        val max = target.fingerprint.length
        while (len <= max) {
            val prefix = target.fingerprint.take(len)
            val collides = others.any { it.fingerprint.startsWith(prefix) }
            if (!collides) break
            len++
        }
        return "${target.name}#${target.fingerprint.take(len)}"
    }

    /** Precompute labels for every person in view, in one pass. */
    fun labels(inView: Collection<Person>): Map<String, String> {
        val distinct = inView.associateBy { it.fingerprint }.values
        return distinct.associate { it.fingerprint to label(it, distinct) }
    }
}
