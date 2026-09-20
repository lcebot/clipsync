package io.github.lcebot.clipsync;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.transition.TransitionManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/**
 * The connection-details bottom sheet: what is connected, what is not, and why.
 *
 * <p>Everything about that one surface lives here: building it, keying its rows, diffing it against
 * each new snapshot, and shutting it down with the Activity. The boundary is a snapshot in and a
 * dialog on screen: this class never reads the status file, never decides when to refresh, and owns
 * nothing on the page behind it. Its owner reads the file (so that the chip and the sheet are always
 * built from the *same* read) and hands the result to {@link #show} and {@link #refresh}.
 *
 * <p>The one thing it needs from the page is a Snackbar, because a sheet has no room to confirm a
 * copy of its own; {@link Host} is that and nothing else.
 */
final class StatusSheet {
    /** What this sheet needs from the page it opens over. */
    interface Host {
        /** Confirm something to the user, because the sheet's own surface is too small to say it there. */
        void snack(int textRes);
    }

    private final Activity a;
    private final Host host;

    /**
     * The open sheet's list, or null. Held so every status refresh can keep it current.
     *
     * <p>Kept current rather than built once: someone opens this sheet *because* a peer is missing,
     * and then watches for it to come back, so every status refresh has to reach whatever is open.
     */
    private ViewGroup list;
    /** The transition's scene root, which is why it is a ViewGroup and not a View. */
    private ViewGroup root;
    /**
     * The open dialog, or null. Held for one reason: {@link #dismiss()}.
     *
     * <p>A Dialog is not part of the Activity's view tree and is not torn down with it. On a rotation
     * the Activity is destroyed and rebuilt while this carries on, which is a leaked window pointing
     * at a view tree that no longer exists.
     */
    private BottomSheetDialog dialog;

    StatusSheet(Activity a, Host host) {
        this.a = a;
        this.host = host;
    }

    /**
     * Every peer that is up, grouped by how it is reached, and every configured target that is not,
     * with its reason.
     *
     * <p><b>The second group is the point.</b> Three addresses of which one is failing is invisible
     * in {@code Connected (2)}, and finding out which one is precisely why someone opens this.
     *
     * <p>A BottomSheetDialog: supplementary content rather than a decision, and the only surface
     * here that Material gives predictive back to for free, since the gesture walks the sheet back down
     * instead of previewing an exit from the app.
     */
    void show(Status.Snapshot s) {
        BottomSheetDialog sheet = new BottomSheetDialog(a);
        sheet.setContentView(R.layout.sheet_status);
        TextView title = sheet.findViewById(R.id.sheet_title);
        ViewGroup body = sheet.findViewById(R.id.sheet_list);
        if (title == null || body == null) return;
        title.setText(R.string.sheet_title);
        list = body;
        // The scene root is the dialog's CoordinatorLayout, one level above the sheet frame, not the
        // sheet's own content, which is where this started and which is not enough.
        //
        // A bottom sheet is anchored to the bottom edge, so when a card goes the frame gets shorter
        // by moving its TOP edge down. Inside that frame nothing moves: the title is still at y=0 of
        // its parent, and ChangeBounds on the content therefore has nothing to animate for it. The
        // view whose bounds actually change is the frame, and to capture that the scene root has to
        // be its parent. With the coordinator as the root, one transition carries the whole thing:
        // the sheet's top edge, and the cards reflowing inside it, on the same clock.
        //
        // Falls back to the content root if the id ever moves: a sheet that animates its cards and
        // snaps its frame is worse than one that animates both, and better than one that crashes.
        root = sheet.findViewById(com.google.android.material.R.id.coordinator);
        if (root == null) root = sheet.findViewById(R.id.sheet_root);
        // Guarded on identity: a listener fires after its dialog is gone, and one that cleared the
        // fields unconditionally would tear down a *newer* sheet that had already claimed them.
        sheet.setOnDismissListener(d -> {
            if (list == body) { list = null; root = null; }
            if (dialog == d) dialog = null;
        });
        dialog = sheet;
        renderSheet(s, false);
        sheet.show();
    }

    /** Bring an open sheet to this snapshot. Nothing to do when it is not open. */
    void refresh(Status.Snapshot s) {
        renderSheet(s, true);
    }

    /** Close it, if it is open. Called from the Activity's onDestroy. */
    void dismiss() {
        BottomSheetDialog d = dialog;
        dialog = null;
        if (d != null && d.isShowing()) d.dismiss();
    }

    /** One entry the sheet shows: a group heading, a connected peer, or a target that is not. */
    private static final class Row {
        final String key;                 // identity across a refresh, not a label
        final int header;                 // a string resource, or 0
        final Status.Peer peer;
        final Status.IndirectPeer indirect;
        final Status.Target target;

        Row(String key, int header, Status.Peer peer, Status.IndirectPeer indirect, Status.Target target) {
            this.key = key; this.header = header; this.peer = peer; this.indirect = indirect; this.target = target;
        }
    }

    /**
     * What the sheet should contain, in order, for this snapshot.
     *
     * <p>Keys are the point of this list. A heading is keyed by its own string, a peer by its node
     * id, a target by its name, so a refresh can tell "this card is still the same device" from
     * "a different device now occupies that position", which is the difference between reflowing a
     * list and rebuilding it under the reader's eyes.
     */
    private List<Row> rowsFor(Status.Snapshot s) {
        List<Row> rows = new ArrayList<>();
        // Relay status: shown at the top when this device is currently forwarding files between two
        // peers that cannot reach each other, so it explains why the device is staying awake.
        if (s.relayCount > 0) {
            rows.add(new Row("h:relay", R.string.sheet_relaying, null, null, null));
        }
        List<Status.Peer> lan = new ArrayList<>(), wan = new ArrayList<>();
        for (Status.Peer p : s.peers) (p.lan ? lan : wan).add(p);
        group(rows, R.string.sheet_on_lan, lan);
        group(rows, R.string.sheet_over_internet, wan);
        if (!s.indirectPeers.isEmpty()) {
            rows.add(new Row("h:indirect", R.string.sheet_indirect, null, null, null));
            for (Status.IndirectPeer ip : s.indirectPeers)
                rows.add(new Row("i:" + ip.id, 0, null, ip, null));
        }
        if (!s.targets.isEmpty()) {
            rows.add(new Row("h:down", R.string.sheet_not_connected, null, null, null));
            for (Status.Target t : s.targets) rows.add(new Row("t:" + t.target, 0, null, null, t));
        }
        if (rows.isEmpty()) rows.add(new Row("none", 0, null, null, null));
        return rows;
    }

    private void group(List<Row> rows, int headerRes, List<Status.Peer> peers) {
        if (peers.isEmpty()) return;      // no members, no heading: see renderSheet
        rows.add(new Row("h:" + headerRes, headerRes, null, null, null));
        // The id, not the name or the address: a peer that moves from Wi-Fi to cellular keeps its
        // card and slides between the two sections instead of vanishing from one and appearing in
        // the other as a different device.
        for (Status.Peer p : peers) rows.add(new Row("p:" + p.id, 0, p, null, null));
    }

    /**
     * Bring the sheet to this snapshot, animating what changed.
     *
     * <p>A diff and not a rebuild. Rebuilding on every status change would cross-fade every card on the screen
     * whether or not anything about it moved, and would throw away the scroll position while it was
     * at it. So views are matched to rows by key: the ones whose key is gone fade out, the ones whose
     * key is new fade in, and every survivor is re-bound in place and slides to wherever the others
     * left it.
     *
     * <p>A section heading is an ordinary keyed row with no members of its own, which is what makes
     * "the heading leaves with its last card" fall out rather than need arranging: {@link #group}
     * emits no heading for an empty section, so the heading's key disappears in the same pass as the
     * card's and the two fade together.
     *
     * <p>The new views are created <b>before</b> the transition begins and while they are still
     * detached, because {@link Ui#visibilityMotion} needs to name the views that fade, and a view
     * that is not in the start scene is one that appears. The ones that are leaving are named from
     * the container as it stands.
     */
    private void renderSheet(Status.Snapshot s, boolean animate) {
        ViewGroup list = this.list;
        if (list == null) return;
        List<Row> rows = rowsFor(s);

        List<View> fading = new ArrayList<>();
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (Row r : rows) wanted.add(r.key);
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (!wanted.contains(String.valueOf(child.getTag()))) fading.add(child);
        }
        // Built here, still unattached: created after beginDelayedTransition they would be part of
        // neither scene, and named as fade targets they are exactly what MaterialFade animates in.
        java.util.Map<String, View> fresh = new java.util.LinkedHashMap<>();
        for (Row r : rows) {
            if (childWithKey(list, r.key) != null) continue;
            View v = r.header != 0 ? makeHeader(list, r) : makeCard(list, r);
            fresh.put(r.key, v);
            fading.add(v);
        }
        if (animate && !fading.isEmpty() && root != null) {
            TransitionManager.beginDelayedTransition(root, Ui.visibilityMotion(fading.toArray(new View[0])));
        }

        for (int i = list.getChildCount() - 1; i >= 0; i--) {
            if (!wanted.contains(String.valueOf(list.getChildAt(i).getTag()))) list.removeViewAt(i);
        }
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            View v = childWithKey(list, r.key);
            if (v == null) {
                list.addView(fresh.get(r.key), i);
            } else if (list.indexOfChild(v) != i) {
                // Detached and reinserted, not faded: it is the same device in a new place, so the
                // transition sees a bounds change and slides it there.
                list.removeView(v);
                list.addView(v, i);
            }
            if (r.header == 0) bindCard(list.getChildAt(i), r);
        }
    }

    private static View childWithKey(ViewGroup list, String key) {
        for (int i = 0; i < list.getChildCount(); i++) {
            if (key.equals(list.getChildAt(i).getTag())) return list.getChildAt(i);
        }
        return null;
    }

    private View makeHeader(ViewGroup list, Row r) {
        TextView h = (TextView) a.getLayoutInflater().inflate(R.layout.item_status_header, list, false);
        h.setTag(r.key);
        h.setText(r.header);
        // A heading, and said so: TalkBack can then jump between the groups instead of reading every
        // peer to find where the next one starts, which in a list like this is the whole navigation.
        h.setAccessibilityHeading(true);
        return h;
    }

    /**
     * An empty card with the fields its kind needs. The values arrive in {@link #bindCard}, which is
     * what lets a refresh change them without replacing the card.
     */
    private View makeCard(ViewGroup list, Row r) {
        View card = a.getLayoutInflater().inflate(R.layout.item_status_card, list, false);
        card.setTag(r.key);
        if (r.peer != null) {
            // Monospace for the two that are machine-readable strings: hex digits and dotted quads
            // are read character by character, and a proportional font makes 1/l and 0/O work for it.
            field(card, R.string.field_id).setTypeface(android.graphics.Typeface.MONOSPACE);
            field(card, R.string.field_type);
            field(card, R.string.field_address).setTypeface(android.graphics.Typeface.MONOSPACE);
        } else if (r.indirect != null) {
            field(card, R.string.field_id).setTypeface(android.graphics.Typeface.MONOSPACE);
            field(card, R.string.field_type);
            field(card, R.string.field_via);
        } else if (r.target != null) {
            field(card, R.string.field_reason);
        }
        if (r.peer != null || r.indirect != null || r.target != null) labelCopyAction(card);
        return card;
    }

    /**
     * Tell a screen reader what pressing the card does.
     *
     * <p>The whole card is one touch target that copies the device's details, and there is nothing in
     * its text saying so: the sheet's "tap to copy" subtitle is a separate node and is never read
     * while a card has focus, so TalkBack announced these as "double tap to activate" with no hint as
     * to what activating them would do. Labelling ACTION_CLICK is the same fix the own-addresses card
     * on the settings page already uses.
     */
    private void labelCopyAction(View card) {
        card.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View v, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(v, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLICK, a.getString(R.string.card_copy)));
            }
        });
    }

    private void bindCard(View card, Row r) {
        if (r.peer != null) {
            Status.Peer p = r.peer;
            String name = p.name == null || p.name.isEmpty() ? "?" : p.name;
            String addr = p.addr == null ? "?" : p.addr;
            Ui.setTextIfChanged(card.findViewById(R.id.card_name), name);
            // A connected peer's own facts are never a verdict on anything, so they take the plain
            // role, because the grading in `value` is for the reasons a target is *not* connected.
            value(card, 0, Node.shortId(p.id), Status.Why.WAITING);
            value(card, 1, p.type == null ? "?" : p.type, Status.Why.WAITING);
            value(card, 2, addr, Status.Why.WAITING);
            // The copied text is unchanged: name, then the FULL id, then the address, one per line.
            // The card shows the id's first 8 characters because 36 are unreadable at a glance, and
            // copying is how you get the rest, so the two must not be the same string.
            clickToCopy(card, name + "\n" + (p.id == null ? "" : p.id) + "\n" + addr);
        } else if (r.indirect != null) {
            Status.IndirectPeer ip = r.indirect;
            String name = ip.name == null || ip.name.isEmpty() ? "?" : ip.name;
            Ui.setTextIfChanged(card.findViewById(R.id.card_name), name);
            value(card, 0, Node.shortId(ip.id), Status.Why.WAITING);
            value(card, 1, ip.type == null ? "?" : ip.type, Status.Why.WAITING);
            value(card, 2, ip.via == null ? "?" : ip.via, Status.Why.WAITING);
            clickToCopy(card, name + "\n" + (ip.id == null ? "" : ip.id));
        } else if (r.target != null) {
            Ui.setTextIfChanged(card.findViewById(R.id.card_name), r.target.target);
            value(card, 0, r.target.reason, r.target.why);
            clickToCopy(card, r.target.target);
        } else {
            Ui.setTextIfChanged(card.findViewById(R.id.card_name), a.getString(R.string.sheet_none));
            // Not just unclickable: a card with clickable=true carries a ripple and takes focus, so
            // leaving those on gives a placeholder the feedback of a control that does nothing. The
            // card keeps its surface; only the affordance goes.
            card.setClickable(false);
            card.setFocusable(false);
        }
    }

    /**
     * Copy this text when the card is tapped, re-binding only when the text actually changed.
     *
     * <p>{@code bindCard} runs on every status refresh, per card, for as long as the sheet is open, and a fresh
     * listener each time is a fresh lambda holding a fresh string, garbage produced by a sheet that
     * is simply sitting there. The text doubles as the memo of what is already bound; the tag key is
     * an id from this layout, which is the usual way to keep a view's own bookkeeping on the view.
     */
    private void clickToCopy(View card, String text) {
        if (text.equals(card.getTag(R.id.card_name))) return;
        card.setTag(R.id.card_name, text);
        Haptics.onClick(card, () -> copy(text));
    }

    /** One labelled field, value still empty. Position in the card is the caller's order. */
    private TextView field(View card, int labelRes) {
        ViewGroup fields = card.findViewById(R.id.card_fields);
        // Revealed on the first field rather than always visible: its 8dp top margin would otherwise
        // hang off the bottom of a card that has no fields at all ("No peers connected").
        fields.setVisibility(View.VISIBLE);
        View row = a.getLayoutInflater().inflate(R.layout.item_status_field, fields, false);
        ((TextView) row.findViewById(R.id.field_label)).setText(labelRes);
        fields.addView(row);
        return row.findViewById(R.id.field_value);
    }

    /**
     * Set one field's value by position, in the colour its kind of reason calls for.
     *
     * <p>Four roles, loudest first, and each one answers "so what do I do?" differently: a colour
     * that does not change what the reader does next is decoration:
     *
     * <ul>
     *   <li>{@code FAULT} maps to <b>colorError</b>. Something to fix.
     *   <li>{@code ASLEEP} maps to <b>colorTertiary</b>. The peer said so itself, which makes this the one
     *       row carrying positive knowledge rather than the absence of it. Tertiary is already this
     *       app's colour for the chip's *Connecting...*, a state the system is passing through on
     *       purpose, so the vocabulary is the same in both places.
     *   <li>{@code WAITING} maps to <b>colorOnSurface</b>. The plain default: wait.
     *   <li>{@code NOTED} maps to <b>colorOnSurfaceVariant</b>. A footnote about the setup, quietest,
     *       because it explains why a row exists and asks for nothing.
     * </ul>
     *
     * <p>Applied on every call rather than only when it changes kind: a target can stop being a
     * fault, and a colour left behind outlives the condition that justified it.
     */
    private void value(View card, int index, String text, Status.Why why) {
        ViewGroup fields = card.findViewById(R.id.card_fields);
        if (index >= fields.getChildCount()) return;
        TextView v = fields.getChildAt(index).findViewById(R.id.field_value);
        Ui.setTextIfChanged(v, text == null || text.isEmpty() ? "?" : text);
        // Compared before it is set, like every other setter on the status-refresh path: setTextColor
        // invalidates whether or not the colour differs.
        int want = MaterialColors.getColor(v, switch (why) {
            case FAULT -> androidx.appcompat.R.attr.colorError;
            case ASLEEP -> com.google.android.material.R.attr.colorTertiary;
            case NOTED -> com.google.android.material.R.attr.colorOnSurfaceVariant;
            case WAITING -> com.google.android.material.R.attr.colorOnSurface;
        });
        if (v.getCurrentTextColor() != want) v.setTextColor(want);
    }

    private void copy(String text) {
        if (text == null || text.isEmpty()) return;
        a.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("clipsync", text));
        host.snack(R.string.snack_copied);
    }
}
