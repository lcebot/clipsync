package io.github.lcebot.clipsync;

import android.content.Context;
import android.content.res.Configuration;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import java.util.UUID;

/**
 * This device's identity on the network: an id, a type, and what it can promise about staying
 * connected. See docs/p2p-plan.md §1 and §2.
 *
 * <p><b>The id is generated once per process and never stored.</b> It identifies a <em>session</em>,
 * not an installation, and every use of it is within one session: link dedup, the self-connection
 * guard, the recipient list in an OFFER, the priority tie-break. All of those compare ids that
 * arrived in a HELLO on a connection that is currently open. Nothing compares an id against one from
 * last week.
 *
 * <p>So the file that used to hold it bought one thing — a restart being recognised as the same node
 * — and charged for it in atomic writes, a half-written-id failure mode, and, worst, a value people
 * could edit into a collision. Two nodes sharing one id breaks dedup and relay selection in ways
 * that are very hard to trace back to their cause. A fresh UUID per start cannot collide at all.
 *
 * <p>What that costs is written down in §1: after an <em>unclean</em> restart, a peer that has not
 * yet noticed the old TCP connection is dead sees the returning node as a new one, so for up to one
 * read timeout it holds a live link and a stale one and cannot collapse them by id. That is bounded,
 * self-healing, and costs duplicate delivery rather than lost delivery.
 */
final class Node {
    /**
     * Generated when this class is first touched, which is once per process — the sync service and
     * the UI run in separate processes and therefore have separate ids, which is correct: only the
     * service opens connections, and only its id is ever declared.
     */
    private static final String ID = UUID.randomUUID().toString();

    private Node() { }

    static String id() {
        return ID;
    }

    /** The first 8 characters, which is what logs and the peer list show beside the friendly name. */
    static String shortId(String id) {
        return id == null ? "?" : id.substring(0, Math.min(8, id.length()));
    }

    /** The first 8 hex characters of a key, for logs that must not print the whole thing. */
    static String shortKey(String hex) {
        return hex == null || hex.isEmpty() ? "(none)" : hex.substring(0, Math.min(8, hex.length())) + "…";
    }

    /** The name a peer shows for this device. */
    static String name() {
        return Build.MODEL;
    }

    /**
     * {@code tablet} or {@code phone}, from the smallest screen width. §2 makes this user-settable
     * with the heuristic as the default; until that control exists the heuristic is the value.
     */
    static String type(Context ctx) {
        Configuration c = ctx.getResources().getConfiguration();
        return c.smallestScreenWidthDp >= 600 ? "tablet" : "phone";
    }

    /**
     * Can this device hold a connection while idle?
     *
     * <p><b>A declaration of capability, not an inference from type</b> (§2). It is true only when the
     * device is charging <em>and</em> has the exemptions it needs to survive being idle, because a
     * non-rooted phone on charge still gets frozen — and electing it as the LAN's relay would elect a
     * node that silently stops relaying. Saying "yes" here when the answer is "no" is worse than
     * saying "no": the network would route through it and lose the traffic.
     */
    static boolean persistent(Context ctx) {
        return charging(ctx) && exempt(ctx);
    }

    /** {@code mains} | {@code high} | {@code medium} | {@code low} — the buckets §3 compares. */
    static String battery(Context ctx) {
        if (charging(ctx)) return "mains";
        BatteryManager bm = ctx.getSystemService(BatteryManager.class);
        int pct = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (pct < 0) return "medium";           // unknown: neither promise nor penalise
        if (pct >= 60) return "high";
        if (pct >= 25) return "medium";
        return "low";
    }

    private static boolean charging(Context ctx) {
        BatteryManager bm = ctx.getSystemService(BatteryManager.class);
        return bm != null && bm.isCharging();
    }

    private static boolean exempt(Context ctx) {
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }
}
