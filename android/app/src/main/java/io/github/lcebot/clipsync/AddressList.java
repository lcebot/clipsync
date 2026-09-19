package io.github.lcebot.clipsync;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.transition.Transition;
import androidx.transition.TransitionManager;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An editable list of addresses: a column of {@code item_peer} rows, each with its own trailing
 * remove icon and its own error, plus the "Add address" button that grows it.
 *
 * <p>Two of these exist on the Settings page — the Direct connections list and this device's own
 * addresses, the names and IPs by which other devices reach this one, which is how a configured
 * target is recognised as being this device rather than a peer — and they are one class used twice
 * rather than two copies of the same ninety lines. They accept exactly the same things and must not
 * drift into accepting different ones; that is the same reason {@link Config#checkAddresses} takes
 * its differences as parameters.
 *
 * <p>What actually differs between the two is small and is passed in:
 * <ul>
 *   <li><b>{@code allowEmpty}</b> — the peer list needs an entry while its switch is on; the own
 *       list never does.</li>
 *   <li><b>{@code enabled}</b> — only the peer list has a switch above it, and a disabled list still
 *       shows its contents rather than forgetting them.</li>
 *   <li>the set of addresses an entry may not be, which is how "that is this device" is reported on
 *       the row that is wrong rather than on the list as a whole.</li>
 * </ul>
 *
 * <p><b>One row always remains</b>, even when the list is logically empty. Zero rows is a list that
 * looks broken, and the remaining row is also the only place a "this is required" error can be hung.
 * Its remove icon is hidden rather than greyed out, because {@code TextInputLayout} offers no public
 * way to disable only the trailing icon.
 */
final class AddressList {
    /** How the owner animates a row appearing or disappearing, and revalidates after a change. */
    interface Host {
        /** A transition that fades {@code changing} while its siblings reflow around it. */
        Transition motion(View changing);

        /** The scene root the transition runs in. */
        ViewGroup sceneRoot();

        /** Something in the list changed. */
        void onChanged();
    }

    private final Context ctx;
    private final ViewGroup box;
    private final View addButton;
    private final Host host;
    private final boolean allowEmpty;
    private final int hintRes;
    private final List<TextInputLayout> rows = new ArrayList<>();
    private boolean enabled = true;

    AddressList(Context ctx, ViewGroup box, View addButton, int hintRes, boolean allowEmpty, Host host) {
        this.ctx = ctx;
        this.box = box;
        this.addButton = addButton;
        this.hintRes = hintRes;
        this.allowEmpty = allowEmpty;
        this.host = host;
        Haptics.onClick(addButton, () -> { add("", true); host.onChanged(); });
    }

    // ------------------------------------------------------------------ contents
    /** Replaces every row. A list with nothing in it still gets one empty row. */
    void setValues(List<String> values) {
        box.removeAllViews();
        rows.clear();
        if (values.isEmpty()) values = List.of("");
        for (String s : values) add(s, false);
    }

    /**
     * The rows exactly as they read, minus the blanks. A blank is either an error the user is
     * looking at or the lone row standing in for an empty list, and neither belongs in the file.
     */
    List<String> values() {
        List<String> out = new ArrayList<>();
        for (TextInputLayout row : rows) {
            String v = text(row);
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /** The values normalised, for the membership tests the other list runs against this one. */
    Set<String> normalised() {
        Set<String> out = new LinkedHashSet<>();
        for (String s : values()) out.add(Config.normalisePeer(s));
        return out;
    }

    int size() {
        return rows.size();
    }

    // ------------------------------------------------------------------ enabled state
    void setEnabled(boolean on) {
        enabled = on;
        if (!on) dropBlankRows();
        refresh();
    }

    /**
     * Drops the blank rows when the list goes out of use, keeping one.
     *
     * <p>While the list is showing, a blank row is an error the user can see and fix; once hidden it
     * could neither be seen nor fixed, so refusing to save on it would be a dead end. Removing it
     * instead keeps the stored value clean. Filled rows stay — preserving them is the entire point of
     * having a switch rather than deleting the list.
     */
    private void dropBlankRows() {
        for (int i = rows.size() - 1; i >= 0 && rows.size() > 1; i--) {
            if (text(rows.get(i)).isEmpty()) {
                box.removeView(rows.get(i));
                rows.remove(i);
            }
        }
    }

    // ------------------------------------------------------------------ rows
    private void add(String value, boolean animate) {
        TextInputLayout row = (TextInputLayout) LayoutInflater.from(ctx)
                .inflate(R.layout.item_peer, box, false);
        TextInputEditText field = row.findViewById(R.id.peer);
        field.setText(value);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { host.onChanged(); }
        });
        // The trailing icon is a button, whatever it is attached to: "no haptics on text fields"
        // means the field, not the control sitting in its corner.
        row.setEndIconOnClickListener(v -> { Haptics.tick(v); remove(row); });
        // Inflated before the transition begins so the new row can be named as the thing that fades.
        if (animate) TransitionManager.beginDelayedTransition(host.sceneRoot(), host.motion(row));
        box.addView(row);
        rows.add(row);
        refresh();
    }

    private void remove(TextInputLayout row) {
        if (rows.size() <= 1) return;               // the icon is hidden, but be certain
        TransitionManager.beginDelayedTransition(host.sceneRoot(), host.motion(row));
        box.removeView(row);
        rows.remove(row);
        refresh();
        host.onChanged();
    }

    /**
     * Hints are numbered here rather than in the layout: several rows carrying one identical hint
     * give a screen reader nothing to tell them apart by, and an error names a row by its hint.
     */
    private void refresh() {
        boolean removable = rows.size() > 1;
        for (int i = 0; i < rows.size(); i++) {
            TextInputLayout row = rows.get(i);
            row.setEndIconVisible(enabled && removable);
            row.setEnabled(enabled);
            row.setHint(ctx.getString(hintRes, i + 1));
        }
        addButton.setEnabled(enabled);
    }

    // ------------------------------------------------------------------ validation
    /**
     * Per row, with a repeat reported on the second one — the first is not wrong, and marking both
     * would leave the user with no clue which to change.
     *
     * @param forbidden addresses an entry may not be (this device's own), reported on the row
     * @param emptyLast what a blank lone row says when the list may not be empty
     * @param emptyMore what a blank row says when there are others it could be removed in favour of
     */
    boolean validate(Set<String> forbidden, String emptyLast, String emptyMore) {
        if (!enabled) {
            for (TextInputLayout row : rows) Ui.showError(row, null);
            return true;
        }
        boolean ok = true;
        List<String> seen = new ArrayList<>();
        for (TextInputLayout row : rows) {
            String raw = text(row);
            String problem;
            if (raw.isEmpty()) {
                problem = allowEmpty ? null : (rows.size() > 1 ? emptyMore : emptyLast);
            } else {
                problem = Config.checkPeer(raw);
                String normal = Config.normalisePeer(raw);
                if (problem == null && seen.contains(normal)) problem = ctx.getString(R.string.peer_duplicate);
                else if (problem == null && forbidden.contains(normal)) problem = ctx.getString(R.string.peer_is_self);
                if (problem == null) seen.add(normal);
            }
            ok &= Ui.showError(row, problem);
        }
        return ok;
    }

    /** The row that should take focus when the whole list is reported as the problem. */
    View firstRow() {
        return rows.isEmpty() ? box : rows.get(0);
    }

    private static String text(TextInputLayout row) {
        TextInputEditText field = row.findViewById(R.id.peer);
        return field.getText() == null ? "" : field.getText().toString().trim();
    }
}
