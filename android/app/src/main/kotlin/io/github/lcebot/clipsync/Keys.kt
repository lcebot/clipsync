package io.github.lcebot.clipsync

import java.util.ArrayList
import java.util.Collections

/**
 * The PSK's life: when it is replaced, what replaces it, and what is still accepted meanwhile.
 *
 * Pure arithmetic and string handling, with no I/O, no sockets, no Android. Everything about rotation
 * that can be reasoned about by reading lives here, so that the parts which cannot be (the handshake
 * accepting two keys at once, the frame that carries a schedule between devices) have nothing to
 * decide for themselves.
 *
 * ## The schedule
 *
 * ```
 *   0h        a key becomes active
 *   48h       PRE-RETIRED: a successor is generated, announced to every peer, and accepted
 *   72h       RETIRED: the successor becomes the key, but only if some peer has agreed
 *             +24h at a time, if none has
 * ```
 *
 * Both ends of a link accept the successor as soon as either has generated one, which is what
 * makes the changeover invisible: there is a full day in which a device may be using either key and
 * every peer will still talk to it.
 *
 * ## Two things the specification does not say, and why they are these
 *
 * **Which successor wins** when two devices were offline from each other, both passed 48h, and
 * both generated one. The rule is **the larger key, compared as text**. Not the earlier or the
 * later: that needs the two clocks to agree, and they do not, because the offset exchange that would
 * make them agree is a clock-offset exchange between peers, which is designed but not built. Comparing
 * the key material needs no clock, no extra state
 * and no round trip, gives a total order, and has every device reach the same verdict from what it
 * already holds. The loser's key is discarded unused, which costs nothing: neither had been adopted.
 *
 * **A keyring, not one superseded key.** With a 48-hour cycle, a tablet left in a drawer for a
 * week comes back several keys behind, and accepting only the immediately preceding one would lock it
 * out after a single missed rotation. [KEYRING] past keys stay acceptable, so a device may
 * miss that many rotations and still be let in, at which point its peer teaches it the current
 * schedule and it catches up. Being locked out is recoverable, since the device can be paired again
 * from scratch (see [Pairing]), but it is a
 * thing the user has to notice and act on, and a week in a drawer should not require it.
 *
 * ## What this cannot do
 *
 * There is **no clock agreement between devices**. The clock-offset exchange that would
 * provide it is designed and not built, so every deadline here is measured against the device's own
 * clock. The consequence
 * is bounded on purpose: a device is never told "retire at this instant", only "here is a successor",
 * and it retires on its own schedule while still accepting the old key for [KEYRING]
 * rotations. Two devices whose clocks differ by hours therefore rotate hours apart and never notice.
 */
object Keys {
    /** Age at which a successor is generated and announced. */
    internal const val PRE_RETIRE_MS = 48 * 3600_000L

    /** Age at which the successor takes over, if any peer has agreed to it. */
    internal const val RETIRE_MS = 72 * 3600_000L

    /** Added to the deadline each time it arrives with no peer having agreed. */
    internal const val EXTEND_MS = 24 * 3600_000L

    /**
     * How many superseded keys stay acceptable.
     *
     * Three, which is six days of missed rotations: longer than a weekend away, shorter than
     * "this key is never really gone". Each one is an extra decryption attempt on the first frame of
     * an unauthenticated connection and nothing more, so the cost of the ring is a few microseconds
     * per connection and the benefit is a device that can be away for most of a week.
     */
    internal const val KEYRING = 3

    /** What a device should be doing about its key right now. */
    enum class Phase {
        /** Young enough to leave alone. */
        ACTIVE,

        /** Past 48h: a successor exists, both keys are accepted, peers are being told. */
        PRE_RETIRED,

        /** Past the deadline with a peer's agreement: the successor becomes the key. */
        DUE,

        /**
         * Past the deadline with nobody having agreed.
         *
         * Not a promotion: swapping keys while no peer has ever acknowledged the successor is how
         * a device rotates itself out of its own network. The deadline moves instead, as described at
         * [EXTEND_MS], and keeps moving until something connects, however long that is.
         */
        STRANDED,
    }

    /**
     * One device's view of its own key schedule. Immutable; [promoted] and the other builders make
     * the next one.
     *
     * Deliberately not a `data class`: it holds the PSK, and a generated `toString` would print it.
     */
    class Schedule internal constructor(
        psk: String?,
        next: String?,
        old: List<String>?,
        since: Long,
        retireAt: Long,
        agreedAt: Long,
    ) {
        internal val psk: String

        /** The successor, or "" until one is generated. */
        internal val next: String

        /** Superseded keys still accepted, newest first. */
        internal val old: List<String>

        /** When [psk] became active, by this device's clock. */
        internal val since: Long

        /** When [next] is due to take over; 0 while none is scheduled. */
        internal val retireAt: Long

        /** When a peer last agreed to [next]; 0 if none has. */
        internal val agreedAt: Long

        init {
            this.psk = psk ?: ""
            this.next = next ?: ""
            this.old = if (old == null) java.util.List.of() else java.util.List.copyOf(old)
            this.since = since
            this.retireAt = retireAt
            this.agreedAt = agreedAt
        }

        /**
         * Every key that may authenticate an inbound connection, in the order worth trying.
         *
         * Current first, because almost every connection uses it; then the successor, because a
         * peer that has already promoted will be using it; then the ring, oldest last.
         */
        internal fun accepted(): List<String> {
            val all: MutableList<String> = ArrayList()
            if (!psk.isEmpty()) all.add(psk)
            if (!next.isEmpty()) all.add(next)
            all.addAll(old)
            return Collections.unmodifiableList(all)
        }

        internal fun phase(now: Long): Phase {
            if (psk.isEmpty()) return Phase.ACTIVE             // nothing to rotate yet
            if (now - since < PRE_RETIRE_MS) return Phase.ACTIVE
            if (next.isEmpty()) return Phase.PRE_RETIRED        // caller generates one
            if (retireAt == 0L || now < retireAt) return Phase.PRE_RETIRED
            return if (agreedAt > since) Phase.DUE else Phase.STRANDED
        }

        /** Enter pre-retirement with this successor, due [RETIRE_MS] after activation. */
        internal fun withNext(successor: String): Schedule {
            return Schedule(psk, successor, old, since, since + RETIRE_MS, agreedAt)
        }

        /**
         * A peer has acknowledged the successor, so the deadline may be honoured.
         *
         * Idempotent, and that is not a micro-optimisation: without it, every T_KEYS frame would
         * produce a schedule differing from the last only in `agreedAt`, which the caller reads
         * as "something changed", which means persisting the whole configuration and announcing it to every peer,
         * which would do the same back, so two devices whose successors already match would rewrite
         * the one file that carries the PSK once per round trip for as long as they stayed connected.
         * Recording a second agreement says nothing the first did not.
         */
        internal fun agreed(now: Long): Schedule {
            if (agreedAt > since) return this
            return Schedule(psk, next, old, since, retireAt, now)
        }

        /** Nobody agreed in time: wait another [EXTEND_MS] rather than leave the network. */
        internal fun extended(): Schedule {
            return Schedule(psk, next, old, since, retireAt + EXTEND_MS, agreedAt)
        }

        /** The successor becomes the key; the old one joins the ring. */
        internal fun promoted(now: Long): Schedule {
            val ring = ArrayList<String>()
            ring.add(psk)
            ring.addAll(old)
            while (ring.size > KEYRING) ring.removeAt(ring.size - 1)
            return Schedule(next, "", ring, now, 0, 0)
        }

        /**
         * Adopt a key learned from a peer as the active one, keeping ours in the ring.
         *
         * For the device that is behind: a peer authenticated with something we had only as a
         * successor or had not seen at all, which means it has already rotated and we have not.
         * Following it is the only way back into the network.
         *
         * Only ever reached for a peer that authenticated with the **current key or the
         * successor**; see [reconcile]'s `trusted`. "The peer holds a key, so it is
         * as trusted as we are" is true of the current key and false of a superseded one: rotation
         * exists precisely because an old key may have leaked, and letting a leaked key nominate the
         * network's next key turns a temporary compromise into a permanent takeover.
         */
        internal fun adopt(key: String, now: Long): Schedule {
            if (key == psk) return this
            val ring = ArrayList<String>()
            if (!psk.isEmpty()) ring.add(psk)
            for (k in old) if (k != key) ring.add(k)
            while (ring.size > KEYRING) ring.removeAt(ring.size - 1)
            return Schedule(key, "", ring, now, 0, 0)
        }

        /**
         * Reconcile this schedule with a peer's reported state.
         *
         * Called when a T_KEYS frame arrives. The peer says "my current key is `theirPsk`
         * and my successor is `theirNext`"; this returns the schedule we should hold after
         * hearing that. Pure, since nothing is persisted here.
         *
         * Two things can happen:
         *
         * - **The current key changes.** The peer is on our successor (it promoted before we
         *   did) or on a key we have never seen (it rotated past us). Either way we follow.
         * - **The successor is aligned.** Both ends generated one independently; the one that
         *   compares larger wins ([Keys.betterNext]). A peer acknowledging ours counts as
         *   an agreement, which is what unlocks promotion at the deadline.
         *
         * @param trusted whether the connection this report arrived on authenticated with the key
         *                this device currently holds, or with its successor. Only such a peer may
         *                move us onto a key we have never seen; see [adopt]. A peer let in on
         *                a superseded ring key is still a peer; it is only its right to *lead*
         *                that is withheld, and the "peer is behind" branch below teaches it instead.
         * @return the reconciled schedule (may be `this` when nothing changed)
         */
        internal fun reconcile(theirPsk: String, theirNext: String?, now: Long, trusted: Boolean): Schedule {
            var s = this

            // Step 1: align the current key.
            if (!next.isEmpty() && theirPsk == next) {
                // The peer has promoted to our successor. Agreement is in hand, and if the deadline has
                // passed, this is the trigger to promote ourselves.
                s = s.agreed(now)
                if (s.phase(now) == Phase.DUE) s = s.promoted(now)
            } else if (trusted && wouldAdopt(theirPsk)) {
                // Completely unknown key: the peer rotated past us. Adopt it.
                s = s.adopt(theirPsk, now)
            }
            // If theirPsk is in old: the peer is behind; our T_KEYS will teach it.

            // Step 2: align successors (only meaningful when we agree on the current key).
            if (theirPsk == s.psk && theirNext != null && !theirNext.isEmpty()) {
                if (s.next.isEmpty()) {
                    // We have no successor, peer does. Accept it.
                    s = s.withNext(theirNext)
                } else if (s.next != theirNext) {
                    // Both generated independently. The larger one wins (no clock needed).
                    val winner = betterNext(s.next, theirNext)
                    if (winner != s.next) s = s.withNext(winner)
                }
                // Peer acknowledges the (now-agreed) successor.
                if (s.next == theirNext) s = s.agreed(now)
            }

            return s
        }

        /**
         * Would a report of `theirPsk` move this device onto a key it has never seen?
         *
         * Exists so that [reconcile] and the caller that logs a refusal ask the same
         * question of the same code rather than each spelling the condition out.
         */
        internal fun wouldAdopt(theirPsk: String): Boolean {
            if (!next.isEmpty() && theirPsk == next) return false   // that is a promotion, not an adoption
            return theirPsk != psk && !old.contains(theirPsk)
        }

        /**
         * Equal when the **keys and deadlines** are, ignoring [agreedAt].
         *
         * Ignored because "a peer said yes again" is not a change worth persisting or announcing;
         * see [agreed]. Note that the first agreement still is one, because it moves
         * `agreedAt` from 0 and therefore moves [phase] from STRANDED to DUE; callers
         * that must not miss it compare phases rather than relying on this.
         */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Schedule) return false
            val s = other
            return since == s.since && retireAt == s.retireAt &&
                psk == s.psk && next == s.next && old == s.old
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(psk, next, old, since, retireAt)
        }
    }

    /**
     * Reconcile our successor with a peer's.
     *
     * The tie-break is the whole of the negotiation, and it is deliberately not a conversation:
     * both ends run this on the same two strings and reach the same answer, so no round trip is
     * needed and no state has to survive one.
     *
     * @return the successor both devices should keep
     */
    internal fun betterNext(ours: String?, theirs: String?): String {
        if (ours == null || ours.isEmpty()) return theirs ?: ""
        if (theirs == null || theirs.isEmpty()) return ours
        return if (ours.compareTo(theirs) >= 0) ours else theirs
    }
}
