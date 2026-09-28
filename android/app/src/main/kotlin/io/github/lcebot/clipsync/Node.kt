package io.github.lcebot.clipsync

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.util.UUID

/**
 * This device's identity on the network: an id, a type, and what it can promise about staying
 * connected. Every one of these is declared in HELLO and believed by peers; none of them is
 * inferred from an address or a name.
 *
 * **The id is generated once per process and never stored.** It identifies a *session*,
 * not an installation, and every use of it is within one session: link dedup, the self-connection
 * guard, the recipient list in an OFFER, the priority tie-break. All of those compare ids that
 * arrived in a HELLO on a connection that is currently open. Nothing compares an id against one from
 * last week.
 *
 * Persisting the id would only buy one thing, a restart being recognised as the same node, at
 * the cost of atomic writes, a half-written-id failure mode, and, worst, a value people could edit
 * into a collision. Two nodes sharing one id breaks dedup and relay selection in ways that are very
 * hard to trace back to their cause. A fresh UUID per start cannot collide at all.
 *
 * What that costs, accepted deliberately: after an *unclean* restart, a peer that has not
 * yet noticed the old TCP connection is dead sees the returning node as a new one, so for up to one
 * read timeout it holds a live link and a stale one and cannot collapse them by id. That is bounded,
 * self-healing, and costs duplicate delivery rather than lost delivery.
 */
object Node {
    /**
     * Generated when this class is first touched, which is once per process; the sync service and
     * the UI run in separate processes and therefore have separate ids, which is correct: only the
     * service opens connections, and only its id is ever declared.
     */
    private val ID: String = UUID.randomUUID().toString()

    /**
     * When true, [persistent] returns false regardless of the actual charging and
     * exemption state. Set from the config's `relay_opt_out` field by the service on startup
     * and on reload. A volatile boolean: two threads (the service main thread and the config
     * reload) can write it, and every connection thread reads it.
     */
    @Volatile
    private var relayOptOut: Boolean = false

    internal fun id(): String {
        return ID
    }

    internal fun setRelayOptOut(optOut: Boolean) {
        relayOptOut = optOut
    }

    /** The first 8 characters, which is what logs and the peer list show beside the friendly name. */
    fun shortId(id: String?): String {
        return if (id == null) "?" else id.substring(0, Math.min(8, id.length))
    }

    /** The first 8 hex characters of a key, for logs that must not print the whole thing. */
    internal fun shortKey(hex: String?): String {
        return if (hex == null || hex.isEmpty()) "(none)" else hex.substring(0, Math.min(8, hex.length)) + "…"
    }

    /** The name a peer shows for this device. */
    internal fun name(): String {
        return Build.MODEL
    }

    /**
     * `tablet` or `phone`, from the smallest screen width. The intent is for the user to
     * be able to override it with the heuristic as the default; until that control exists the
     * heuristic is the value.
     */
    internal fun type(ctx: Context): String {
        val c = ctx.resources.configuration
        return if (c.smallestScreenWidthDp >= 600) "tablet" else "phone"
    }

    /**
     * Can this device hold a connection while idle?
     *
     * **A declaration of capability, not an inference from type**. It is true only when the
     * device is charging *and* has the exemptions it needs to survive being idle, because a
     * non-rooted phone on charge still gets frozen, and electing it as the LAN's relay would elect a
     * node that silently stops relaying. Saying "yes" here when the answer is "no" is worse than
     * saying "no": the network would route through it and lose the traffic.
     */
    internal fun persistent(ctx: Context): Boolean {
        if (relayOptOut) return false
        return charging(ctx) && exempt(ctx)
    }

    /**
     * `mains` | `high` | `medium` | `low`: four buckets and not a
     * percentage, because this is compared between nodes to pick a relay and a total order over
     * four names is stable where a comparison of two battery readings is noise.
     */
    internal fun battery(ctx: Context): String {
        if (charging(ctx)) return "mains"
        val bm: BatteryManager? = ctx.getSystemService(BatteryManager::class.java)
        val pct = if (bm == null) -1 else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (pct < 0) return "medium"           // unknown: neither promise nor penalise
        if (pct >= 60) return "high"
        if (pct >= 25) return "medium"
        return "low"
    }

    private fun charging(ctx: Context): Boolean {
        val bm: BatteryManager? = ctx.getSystemService(BatteryManager::class.java)
        return bm != null && bm.isCharging
    }

    private fun exempt(ctx: Context): Boolean {
        val pm: PowerManager? = ctx.getSystemService(PowerManager::class.java)
        return pm != null && pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
}
