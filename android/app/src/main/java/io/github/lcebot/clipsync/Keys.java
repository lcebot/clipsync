package io.github.lcebot.clipsync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The PSK's life: when it is replaced, what replaces it, and what is still accepted meanwhile.
 *
 * <p>Pure arithmetic and string handling — no I/O, no sockets, no Android. Everything about rotation
 * that can be reasoned about by reading lives here, so that the parts which cannot be (the handshake
 * accepting two keys at once, the frame that carries a schedule between devices) have nothing to
 * decide for themselves.
 *
 * <h2>The schedule</h2>
 *
 * <pre>
 *   0h        a key becomes active
 *   48h       PRE-RETIRED: a successor is generated, announced to every peer, and accepted
 *   72h       RETIRED: the successor becomes the key -- but only if some peer has agreed
 *             +24h at a time, if none has
 * </pre>
 *
 * <p>Both ends of a link accept the successor as soon as either has generated one, which is what
 * makes the changeover invisible: there is a full day in which a device may be using either key and
 * every peer will still talk to it.
 *
 * <h2>Two things the specification did not say, and why they are these</h2>
 *
 * <p><b>Which successor wins</b> when two devices were offline from each other, both passed 48h, and
 * both generated one. The rule is <b>the larger key, compared as text</b>. Not the earlier or the
 * later: that needs the two clocks to agree, and they do not — the offset exchange that would make
 * them agree is §6, which is not built. Comparing the key material needs no clock, no extra state
 * and no round trip, gives a total order, and has every device reach the same verdict from what it
 * already holds. The loser's key is discarded unused, which costs nothing: neither had been adopted.
 *
 * <p><b>A keyring, not one previous key.</b> With a 48-hour cycle, a tablet left in a drawer for a
 * week comes back several keys behind, and accepting only the immediately previous one would lock it
 * out after a single missed rotation. {@link #KEYRING} past keys stay acceptable, so a device may
 * miss that many rotations and still be let in — at which point its peer teaches it the current
 * schedule and it catches up. Being locked out is recoverable (pairing exists, §12) but it is a
 * thing the user has to notice and act on, and a week in a drawer should not require it.
 *
 * <h2>What this cannot do</h2>
 *
 * <p>There is <b>no clock agreement between devices</b>. §6's offset exchange would provide it and
 * is not built, so every deadline here is measured against the device's own clock. The consequence
 * is bounded on purpose: a device is never told "retire at this instant", only "here is a successor",
 * and it retires on its own schedule while still accepting the old key for {@link #KEYRING}
 * rotations. Two devices whose clocks differ by hours therefore rotate hours apart and never notice.
 */
final class Keys {
    /** Age at which a successor is generated and announced. */
    static final long PRE_RETIRE_MS = 48 * 3600_000L;
    /** Age at which the successor takes over, if any peer has agreed to it. */
    static final long RETIRE_MS = 72 * 3600_000L;
    /** Added to the deadline each time it arrives with no peer having agreed. */
    static final long EXTEND_MS = 24 * 3600_000L;
    /**
     * How many superseded keys stay acceptable.
     *
     * <p>Three, which is six days of missed rotations — longer than a weekend away, shorter than
     * "this key is never really gone". Each one is an extra decryption attempt on the first frame of
     * an unauthenticated connection and nothing more, so the cost of the ring is a few microseconds
     * per connection and the benefit is a device that can be away for most of a week.
     */
    static final int KEYRING = 3;

    /** What a device should be doing about its key right now. */
    enum Phase {
        /** Young enough to leave alone. */
        ACTIVE,
        /** Past 48h: a successor exists, both keys are accepted, peers are being told. */
        PRE_RETIRED,
        /** Past the deadline with a peer's agreement: the successor becomes the key. */
        DUE,
        /**
         * Past the deadline with nobody having agreed.
         *
         * <p>Not a promotion: swapping keys while no peer has ever acknowledged the successor is how
         * a device rotates itself out of its own network. The deadline moves instead — see
         * {@link #EXTEND_MS} — and keeps moving until something connects, however long that is.
         */
        STRANDED,
    }

    /** One device's view of its own key schedule. Immutable; {@link #rotated} builds the next one. */
    static final class Schedule {
        final String psk;
        /** The successor, or "" until one is generated. */
        final String next;
        /** Superseded keys still accepted, newest first. */
        final List<String> old;
        /** When {@link #psk} became active, by this device's clock. */
        final long since;
        /** When {@link #next} is due to take over; 0 while none is scheduled. */
        final long retireAt;
        /** When a peer last agreed to {@link #next}; 0 if none has. */
        final long agreedAt;

        Schedule(String psk, String next, List<String> old, long since, long retireAt, long agreedAt) {
            this.psk = psk == null ? "" : psk;
            this.next = next == null ? "" : next;
            this.old = old == null ? List.of() : List.copyOf(old);
            this.since = since;
            this.retireAt = retireAt;
            this.agreedAt = agreedAt;
        }

        /**
         * Every key that may authenticate an inbound connection, in the order worth trying.
         *
         * <p>Current first, because almost every connection uses it; then the successor, because a
         * peer that has already promoted will be using it; then the ring, oldest last.
         */
        List<String> accepted() {
            List<String> all = new ArrayList<>();
            if (!psk.isEmpty()) all.add(psk);
            if (!next.isEmpty()) all.add(next);
            all.addAll(old);
            return Collections.unmodifiableList(all);
        }

        Phase phase(long now) {
            if (psk.isEmpty()) return Phase.ACTIVE;             // nothing to rotate yet
            if (now - since < PRE_RETIRE_MS) return Phase.ACTIVE;
            if (next.isEmpty()) return Phase.PRE_RETIRED;        // caller generates one
            if (retireAt == 0 || now < retireAt) return Phase.PRE_RETIRED;
            return agreedAt > since ? Phase.DUE : Phase.STRANDED;
        }

        /** Enter pre-retirement with this successor, due {@link #RETIRE_MS} after activation. */
        Schedule withNext(String successor) {
            return new Schedule(psk, successor, old, since, since + RETIRE_MS, agreedAt);
        }

        /** A peer has acknowledged the successor, so the deadline may be honoured. */
        Schedule agreed(long now) {
            return new Schedule(psk, next, old, since, retireAt, now);
        }

        /** Nobody agreed in time: wait another {@link #EXTEND_MS} rather than leave the network. */
        Schedule extended() {
            return new Schedule(psk, next, old, since, retireAt + EXTEND_MS, agreedAt);
        }

        /** The successor becomes the key; the old one joins the ring. */
        Schedule promoted(long now) {
            List<String> ring = new ArrayList<>();
            ring.add(psk);
            ring.addAll(old);
            while (ring.size() > KEYRING) ring.remove(ring.size() - 1);
            return new Schedule(next, "", ring, now, 0, 0);
        }

        /**
         * Adopt a key learned from a peer as the active one, keeping ours in the ring.
         *
         * <p>For the device that was behind: a peer authenticated with something we had only as a
         * successor or had not seen at all, which means it has already rotated and we have not.
         * Following it is the only way back into the network — and the peer holds the PSK, so it is
         * already as trusted as this device is (§15).
         */
        Schedule adopt(String key, long now) {
            if (key.equals(psk)) return this;
            List<String> ring = new ArrayList<>();
            if (!psk.isEmpty()) ring.add(psk);
            for (String k : old) if (!k.equals(key)) ring.add(k);
            while (ring.size() > KEYRING) ring.remove(ring.size() - 1);
            return new Schedule(key, "", ring, now, 0, 0);
        }

        /**
         * Reconcile this schedule with a peer's reported state.
         *
         * <p>Called when a T_KEYS frame arrives. The peer says "my current key is {@code theirPsk}
         * and my successor is {@code theirNext}"; this returns the schedule we should hold after
         * hearing that. Pure — nothing is persisted here.
         *
         * <p>Two things can happen:
         *
         * <ol>
         *   <li><b>The current key changes.</b> The peer is on our successor (it promoted before we
         *       did) or on a key we have never seen (it rotated past us). Either way we follow.
         *   <li><b>The successor is aligned.</b> Both ends generated one independently; the one that
         *       compares larger wins ({@link Keys#betterNext}). A peer acknowledging ours counts as
         *       an agreement, which is what unlocks promotion at the deadline.
         * </ol>
         *
         * @return the reconciled schedule (may be {@code this} when nothing changed)
         */
        Schedule reconcile(String theirPsk, String theirNext, long now) {
            Schedule s = this;

            // Step 1: align the current key.
            if (!next.isEmpty() && theirPsk.equals(next)) {
                // The peer has promoted to our successor. Agreement in hand — and if the deadline has
                // passed, this is the trigger to promote ourselves.
                s = s.agreed(now);
                if (s.phase(now) == Phase.DUE) s = s.promoted(now);
            } else if (!theirPsk.equals(psk) && !old.contains(theirPsk)) {
                // Completely unknown key — the peer rotated past us. Adopt it.
                s = s.adopt(theirPsk, now);
            }
            // If theirPsk is in old: the peer is behind; our T_KEYS will teach it.

            // Step 2: align successors (only meaningful when we agree on the current key).
            if (theirPsk.equals(s.psk) && theirNext != null && !theirNext.isEmpty()) {
                if (s.next.isEmpty()) {
                    // We have no successor, peer does. Accept it.
                    s = s.withNext(theirNext);
                } else if (!s.next.equals(theirNext)) {
                    // Both generated independently. The larger one wins (no clock needed).
                    String winner = betterNext(s.next, theirNext);
                    if (!winner.equals(s.next)) s = s.withNext(winner);
                }
                // Peer acknowledges the (now-agreed) successor.
                if (s.next.equals(theirNext)) s = s.agreed(now);
            }

            return s;
        }
    }

    /**
     * Reconcile our successor with a peer's.
     *
     * <p>The tie-break is the whole of the negotiation, and it is deliberately not a conversation:
     * both ends run this on the same two strings and reach the same answer, so no round trip is
     * needed and no state has to survive one.
     *
     * @return the successor both devices should keep
     */
    static String betterNext(String ours, String theirs) {
        if (ours == null || ours.isEmpty()) return theirs == null ? "" : theirs;
        if (theirs == null || theirs.isEmpty()) return ours;
        return ours.compareTo(theirs) >= 0 ? ours : theirs;
    }

    private Keys() { }
}
