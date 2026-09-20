package io.github.lcebot.clipsync;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.transition.Transition;
import androidx.transition.TransitionManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Properties;
import java.util.Set;

/**
 * The Settings page's form: the fields, what they mean in the config file, and whether they are
 * currently valid.
 *
 * <p>The boundary is the file format on one side and a boolean on the other. This class knows every
 * property name, every unit conversion, every check and every group that hides when a switch turns
 * off, and it knows nothing about the service, the app bar, the FABs or the other page. It never
 * saves: {@link #values()} hands out what the user typed and the Activity decides what to do with
 * it, which is also why a save that fails comes back through {@link #showSaveProblem} rather than
 * being caught here.
 *
 * <p>Motion belongs to the page, not to any one control, so every reflow on this page runs through
 * {@link Ui#visibilityMotion} against a single scene root, the settings column. The two address
 * lists are given that same root through {@link AddressList.Host}.
 */
final class SettingsForm {
    /** What the form needs from the Activity holding it. */
    interface Host {
        /** The form's validity changed, or was re-asserted. Gates Apply. */
        void onValidity(boolean valid);

        /** Say something to the user, because the form has no Snackbar anchor of its own. */
        void snack(int textRes);
    }

    private final Activity a;
    private final Host host;

    // fields
    private final TextInputLayout portL, pskL, textKbL, fileMbL, fileMbLocalL, pathL, keepHoursL, keepMbL;
    private final TextInputEditText port, psk, textKb, fileMb, fileMbLocal, path, keepHours, keepMb;
    private final MaterialSwitch discovery, direct, relayOptOut;
    private final CheckBox pskRotate;
    /**
     * The Direct connections list, and this device's own addresses: one class, twice. The second
     * list is the set of names and IPs by which other devices reach THIS one, which is how a
     * configured target is recognised as being this device rather than a peer.
     */
    private final AddressList peerList, ownList;
    private final MaterialButton pskRandom;
    /** The group each switch governs. Whole cards now, because the switches sit outside them. */
    private final View discoveryCard, directCard;
    /** Shown under the pair when both switches are off; see {@link #validate()}. */
    private final View pathsError;

    /** The collapsible own-addresses group: the card that is pressed, its body, and the chevron. */
    private final View ownCard, ownContent, ownChevron;
    /** Whether that group currently holds a bad address; see the card's click listener. */
    private boolean ownHasError;
    private final Slider browse, threads;
    private final TextView browseLabel, threadsLabel;
    private final ViewGroup settingsRoot;
    /**
     * The padding the layout starts with, read before any inset is added to it: the inset listener
     * runs repeatedly (rotation, a cutout coming into play) and has to add to the designed value
     * each time, not to whatever it left behind last time.
     */
    private final int basePad;

    SettingsForm(Activity a, Host host) {
        this.a = a;
        this.host = host;
        // the two pages live in their own layout files and are pulled in with <include>, so this
        // stays one flat findViewById pass over the whole tree
        discovery = a.findViewById(R.id.discovery);
        direct = a.findViewById(R.id.direct);
        peerList = new AddressList(a, a.findViewById(R.id.peers_box), a.findViewById(R.id.peer_add),
                R.string.hint_peer, false, listHost);
        ownList = new AddressList(a, a.findViewById(R.id.own_box), a.findViewById(R.id.own_add),
                R.string.hint_peer, true, listHost);
        pskRandom = a.findViewById(R.id.psk_random);
        pskRotate = a.findViewById(R.id.psk_rotate);
        portL = a.findViewById(R.id.port_layout);       port = a.findViewById(R.id.port);
        pskL = a.findViewById(R.id.psk_layout);         psk = a.findViewById(R.id.psk);
        textKbL = a.findViewById(R.id.text_kb_layout);  textKb = a.findViewById(R.id.text_kb);
        fileMbL = a.findViewById(R.id.file_mb_layout);  fileMb = a.findViewById(R.id.file_mb);
        fileMbLocalL = a.findViewById(R.id.file_mb_local_layout); fileMbLocal = a.findViewById(R.id.file_mb_local);
        pathL = a.findViewById(R.id.path_layout);       path = a.findViewById(R.id.path);
        keepHoursL = a.findViewById(R.id.keep_hours_layout); keepHours = a.findViewById(R.id.keep_hours);
        keepMbL = a.findViewById(R.id.keep_mb_layout);  keepMb = a.findViewById(R.id.keep_mb);
        discoveryCard = a.findViewById(R.id.discovery_card);
        directCard = a.findViewById(R.id.direct_card);
        relayOptOut = a.findViewById(R.id.relay_opt_out);
        pathsError = a.findViewById(R.id.paths_error);
        ownContent = a.findViewById(R.id.own_content);
        ownChevron = a.findViewById(R.id.own_chevron);
        ownCard = a.findViewById(R.id.own_card);
        // What a screen reader is told about the group. Two separate things, and it needs both: the
        // *state* ("collapsed") so it can say what it is looking at, and a *label for the click
        // action* ("Show this device's addresses") so it can say what pressing would do. Neither is
        // derivable from the card's text, and without them the control announces as an unlabelled
        // clickable panel whose contents appear and disappear for no stated reason.
        ownCard.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View v, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(v, info);
                boolean open = ownContent.getVisibility() == View.VISIBLE;
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLICK,
                        a.getString(open ? R.string.own_collapse : R.string.own_expand)));
            }
        });
        Haptics.onClick(ownCard, () -> {
            boolean open = ownContent.getVisibility() != View.VISIBLE;
            // Never close over an error. The message is inside the group, and hiding it would leave
            // Apply disabled with nothing on screen to say why. Doing nothing is not a dead end
            // either: the reason the tap was refused is the red line the user is looking at.
            if (!open && ownHasError) return;
            setOwnExpanded(open, true);
        });
        browse = a.findViewById(R.id.browse);
        browseLabel = a.findViewById(R.id.browse_label);
        threads = a.findViewById(R.id.threads);
        threadsLabel = a.findViewById(R.id.threads_label);
        settingsRoot = a.findViewById(R.id.settings_root);
        basePad = settingsRoot.getPaddingLeft();
    }

    // ------------------------------------------------------------------ wiring
    /** The listeners. Separate from the constructor so the fields can be filled before they fire. */
    void wire() {
        for (TextInputEditText e : new TextInputEditText[]{port, psk, textKb, fileMb, fileMbLocal, path, keepHours, keepMb})
            e.addTextChangedListener(revalidate);
        discovery.setOnCheckedChangeListener((b, checked) -> { applySwitches(true); validate(); });
        direct.setOnCheckedChangeListener((b, checked) -> { applySwitches(true); validate(); });
        pskRotate.setOnCheckedChangeListener((b, checked) -> {
            if (checked) new MaterialAlertDialogBuilder(a)
                    .setMessage(R.string.psk_rotate_help)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });
        Haptics.onClick(pskRandom, this::newPsk);
        // Both sliders run over an INDEX into a step table, not over the value they configure, so
        // the formatter is not decoration: it is the only place the number a user cares about is
        // produced. It is also what a screen reader speaks:
        // BaseSlider.onPopulateNodeForVirtualView composes the node's description as
        // "<type>, <value>" with the value coming from formatValue(), and formatValue() returns the
        // LabelFormatter's output whenever hasLabelFormatter() (BaseSlider.java @ 1.14.0). So
        // TalkBack says "4000 ms", never "3". The other half, which slider is being read out,
        // does not come from here at all; it comes from android:labelFor on the label above each
        // slider in page_settings.xml, which is the pairing Material's own docs prescribe.
        threads.setLabelFormatter(v -> String.valueOf(Ui.snap(Config.THREAD_STEPS, v)));
        Haptics.bind(threads, (s, v, u) -> threadsLabel.setText(a.getString(R.string.threads_label, threadsValue())));
        browse.setLabelFormatter(v -> Ui.snap(Config.BROWSE_STEPS_MS, v) + " ms");
        Haptics.bind(browse, (s, v, u) -> browseLabel.setText(a.getString(R.string.browse_label, browseValue())));
    }

    private final TextWatcher revalidate = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void afterTextChanged(Editable s) { validate(); }
    };

    // ------------------------------------------------------------------ converting between values and fields
    void loadFields() {
        Properties p = Config.raw(a);
        discovery.setChecked(bool(p.getProperty("discovery", "true")));
        direct.setChecked(bool(p.getProperty("direct", "false")));
        peerList.setValues(Config.peerList(p.getProperty("peers", "")));
        ownList.setValues(Config.peerList(p.getProperty("own_addresses", "")));
        // Collapsed by default, but never over content: a device that has an address of its own
        // should show it, and a group the user cannot see is worse than one that takes a tap.
        setOwnExpanded(!ownList.values().isEmpty(), false);
        port.setText(p.getProperty("port", "47521"));
        psk.setText(p.getProperty("psk", ""));
        pskRotate.setChecked(bool(p.getProperty("psk_rotate", "false")));
        relayOptOut.setChecked(bool(p.getProperty("relay_opt_out", "false")));
        textKb.setText(String.valueOf(longOf(p, "max_bytes", 1048576) / 1024));
        fileMb.setText(String.valueOf(longOf(p, "max_file_bytes", 10485760) / (1024 * 1024)));
        fileMbLocal.setText(String.valueOf(longOf(p, "max_file_bytes_local", 104857600) / (1024 * 1024)));
        path.setText(p.getProperty("files_dir", Config.DEFAULT_FILES_DIR));
        keepHours.setText(String.valueOf(longOf(p, "keep_hours", 2)));
        keepMb.setText(String.valueOf(longOf(p, "keep_max_mb", 256)));
        int t = Config.snapThreads((int) longOf(p, "threads", 8));
        threads.setValue(Ui.indexOf(Config.THREAD_STEPS, t));
        threadsLabel.setText(a.getString(R.string.threads_label, t));
        int b = Config.snapBrowse((int) longOf(p, "mdns_timeout_ms", 4000));
        browse.setValue(Ui.indexOf(Config.BROWSE_STEPS_MS, b));
        browseLabel.setText(a.getString(R.string.browse_label, b));
        applySwitches(false);
    }

    Properties values() {
        Properties v = new Properties();
        v.setProperty("discovery", String.valueOf(discovery.isChecked()));
        v.setProperty("direct", String.valueOf(direct.isChecked()));
        v.setProperty("peers", Config.storePeers(peerList.values()));
        v.setProperty("own_addresses", Config.storePeers(ownList.values()));
        v.setProperty("port", text(port));
        v.setProperty("psk", text(psk));
        v.setProperty("psk_rotate", String.valueOf(pskRotate.isChecked()));
        v.setProperty("relay_opt_out", String.valueOf(relayOptOut.isChecked()));
        // A key typed or generated here is a NEW key, so its clock starts now. Without this, Apply
        // would write a fresh key over an old activation time, and rotation would pre-retire it
        // within minutes, on the strength of how long the one it replaced had been in use.
        //
        // Only when it changed: pressing Apply after editing a limit must not keep resetting the age
        // of a key that has been in service for a day.
        if (!text(psk).equalsIgnoreCase(Config.raw(a).getProperty("psk", "").trim())) {
            v.setProperty("psk_since", String.valueOf(System.currentTimeMillis()));
            v.setProperty("psk_next", "");
            v.setProperty("psk_old", "");
            v.setProperty("psk_retire", "0");
            v.setProperty("psk_agreed", "0");
        }
        v.setProperty("mdns_timeout_ms", String.valueOf(browseValue()));
        v.setProperty("threads", String.valueOf(threadsValue()));
        v.setProperty("max_bytes", kb(text(textKb)));
        v.setProperty("max_file_bytes", mb(text(fileMb)));
        v.setProperty("max_file_bytes_local", mb(text(fileMbLocal)));
        v.setProperty("files_dir", text(path));
        v.setProperty("keep_hours", text(keepHours));
        v.setProperty("keep_max_mb", text(keepMb));
        return v;
    }

    // ------------------------------------------------------------------ the address lists
    /**
     * Both lists are {@link AddressList}, which owns the rows, the numbered hints, the blank-row rule
     * and the per-row errors. What this class still supplies is the motion and the scene root, since
     * those belong to the page rather than to either list.
     */
    private final AddressList.Host listHost = new AddressList.Host() {
        @Override public Transition motion(View changing) { return Ui.visibilityMotion(changing); }
        @Override public ViewGroup sceneRoot() { return settingsRoot; }
        @Override public void onChanged() { validate(); }
    };

    private int threadsValue() {
        return Ui.snap(Config.THREAD_STEPS, threads.getValue());
    }

    private int browseValue() {
        return Ui.snap(Config.BROWSE_STEPS_MS, browse.getValue());
    }

    /**
     * Show / hide the card each switch governs. The switches are rows at page level above their
     * cards, so the card is the whole hideable unit and the switch is never inside what it hides.
     * Both cards are named as the fading views, because either or both can turn over in one call.
     *
     * @see Ui#visibilityMotion(View...)
     */
    private void applySwitches(boolean animate) {
        int wantDiscovery = discovery.isChecked() ? View.VISIBLE : View.GONE;
        int wantDirect = direct.isChecked() ? View.VISIBLE : View.GONE;
        // validate() sets this one, just after this method returns and inside the same transition.
        int wantError = discovery.isChecked() || direct.isChecked() ? View.GONE : View.VISIBLE;

        if (animate)
            TransitionManager.beginDelayedTransition(settingsRoot, Ui.visibilityMotion(
                    Ui.turning(discoveryCard, wantDiscovery),
                    Ui.turning(directCard, wantDirect),
                    Ui.turning(pathsError, wantError)));

        discoveryCard.setVisibility(wantDiscovery);
        directCard.setVisibility(wantDirect);
        peerList.setEnabled(direct.isChecked());
    }

    /**
     * Open or close the own-addresses group.
     *
     * <p>Built from a card, a clickable header and the transition machinery that is already here,
     * because Material's View library has no expandable *container*; {@code ExpandableWidget} is
     * an interface the FAB and the Chip implement for themselves, not something a group of settings
     * can be. So the M3 parts are used and the assembly is local: a list-item-height header with the
     * platform ripple, a chevron that turns, and the same fade-and-reflow every other group on this
     * page uses when it appears.
     */
    private void setOwnExpanded(boolean open, boolean animate) {
        // Before the guard below, because the first call is usually a no-op: the group starts
        // collapsed in the layout and is asked to be collapsed, and a screen reader would then
        // never be told the state at all.
        ownCard.setStateDescription(a.getString(open ? R.string.own_state_expanded : R.string.own_state_collapsed));
        // Nothing to do is not the same as doing nothing cheaply: validate() runs on every keystroke
        // and calls this whenever the group holds an error, so without this guard every character
        // typed anywhere on the page would start a transition.
        if (open == (ownContent.getVisibility() == View.VISIBLE)) return;
        if (animate) TransitionManager.beginDelayedTransition(
                settingsRoot, Ui.visibilityMotion(Ui.turning(ownContent, open ? View.VISIBLE : View.GONE)));
        ownContent.setVisibility(open ? View.VISIBLE : View.GONE);
        // The chevron turns over exactly the span the group takes to reflow; Ui.REFLOW_MS is the one
        // ChangeBounds is pinned to. A rotation on its own timing would either finish early, over a
        // card that is still moving, or lag one that has already settled.
        float to = open ? 180f : 0f;
        if (animate) ownChevron.animate().rotation(to).setDuration(Ui.REFLOW_MS).start();
        else ownChevron.setRotation(to);
    }

    /**
     * A fresh key, straight into the field. Asked about first when one is already there: it
     * invalidates every other device at once, and a mis-tap that costs re-pairing the household is
     * not something to find out about afterwards.
     */
    private void newPsk() {
        if (Config.checkPsk(text(psk)) != null) {          // nothing usable there to lose
            psk.setText(Crypto.randomPskHex());
            return;
        }
        new MaterialAlertDialogBuilder(a)
                .setTitle(R.string.psk_replace_title)
                .setMessage(R.string.psk_replace_body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.psk_replace_ok, (d, w) -> psk.setText(Crypto.randomPskHex()))
                .show();
    }

    // ------------------------------------------------------------------ live validation
    /** Runs every field check, shows errors, and gates Apply. Returns true when everything is valid. */
    boolean validate() {
        // Config.from() refuses a config with neither path enabled, so Apply has to refuse it first.
        // This check has no field of its own: with both switches off, both group cards are hidden,
        // which is exactly why it needs its own line rather than an error on one of the fields.
        boolean anyPath = discovery.isChecked() || direct.isChecked();
        pathsError.setVisibility(anyPath ? View.GONE : View.VISIBLE);

        boolean ok = anyPath;
        // The own list first: the peer list is checked against it, so it has to be current.
        boolean ownOk = ownList.validate(Set.of(), null, null);
        ownHasError = !ownOk;
        // An error inside a collapsed group is an error nobody can act on, and Apply is disabled
        // with no visible reason. Opening it is the only honest thing to do. The card's click
        // listener keeps it open from there.
        if (!ownOk) setOwnExpanded(true, true);
        ok &= ownOk;
        ok &= peerList.validate(ownList.normalised(),
                a.getString(R.string.peer_empty_last, a.getString(R.string.switch_direct)),
                a.getString(R.string.peer_empty));
        ok &= Ui.showError(portL, Config.checkPort(text(port)));
        ok &= Ui.showError(pskL, Config.checkPsk(text(psk)));
        ok &= Ui.showError(textKbL, Config.checkRange(text(textKb), 1, 65536, "KB"));
        ok &= Ui.showError(fileMbL, Config.checkRange(text(fileMb), 1, 4096, "MB"));
        ok &= Ui.showError(fileMbLocalL, Config.checkRange(text(fileMbLocal), 1, 4096, "MB"));
        ok &= Ui.showError(pathL, Config.checkPath(text(path)));
        ok &= Ui.showError(keepHoursL, Config.checkRange(text(keepHours), 0, 8760, "h"));
        ok &= Ui.showError(keepMbL, Config.checkRange(text(keepMb), 0, 1024 * 1024, "MB"));
        host.onValidity(ok);
        return ok;
    }

    /**
     * Map a rejected save back onto the field that caused it.
     *
     * <p>Should not happen, since live validation runs the same checks on every keystroke, but
     * {@link Config#save} is the authority and an exception from it has to land somewhere the user
     * can act on rather than in a Snackbar that names a property key.
     */
    void showSaveProblem(IllegalArgumentException e) {
        String msg = e.getMessage() == null ? "invalid value" : e.getMessage();
        String key = msg.contains(":") ? msg.substring(0, msg.indexOf(':')) : "";
        TextInputLayout target = switch (key) {
            case "peers", "discovery" -> peerList.firstRow() instanceof TextInputLayout t ? t : portL;
            case "own_addresses" -> ownList.firstRow() instanceof TextInputLayout t ? t : portL;
            case "port" -> portL; case "psk" -> pskL;
            case "max_bytes" -> textKbL; case "max_file_bytes" -> fileMbL; case "max_file_bytes_local" -> fileMbLocalL;
            case "files_dir" -> pathL; case "keep_hours" -> keepHoursL; case "keep_max_mb" -> keepMbL;
            default -> portL;
        };
        target.setError(msg.substring(key.length() + 1).trim());
        target.requestFocus();
    }

    // ------------------------------------------------------------------ what the page asks of it
    /**
     * Pairing is mDNS at both ends, so it cannot run with local discovery off; turn it on.
     *
     * <p>Rather than refusing. The switch is a preference about finding peers; pairing is a thing
     * the user has just asked for explicitly, and the only reading of "Pair" with discovery off is
     * that they want both. Said out loud, because a control quietly changing another one is worse
     * than either refusing or asking.
     *
     * <p>Applied to the form, not the file: Apply is what writes, everywhere on this page, and
     * pairing itself does not need the setting saved to work; {@link PairProvider} advertises on
     * its own. What this buys is the state after pairing being the one the user can see.
     *
     * @return true always, so callers read as "if we may, go"; the false case would be a refusal,
     *         and there is no case in which this refuses
     */
    boolean ensureDiscovery() {
        if (!discovery.isChecked()) {
            discovery.setChecked(true);              // its listener reflows the page and validates
            host.snack(R.string.pair_turned_discovery_on);
        }
        return true;
    }

    /**
     * The horizontal display-cutout insets, added to the padding the layout designed.
     *
     * <p>The column of cards is readable, touchable content, so it steps aside from a cutout; the
     * surfaces behind it do not.
     */
    void applyInsets(int left, int right) {
        settingsRoot.setPadding(basePad + left, settingsRoot.getPaddingTop(),
                basePad + right, settingsRoot.getPaddingBottom());
    }

    // ------------------------------------------------------------------ converting between text and stored units
    private static boolean bool(String s) {
        String t = s.trim().toLowerCase();
        return t.equals("true") || t.equals("1") || t.equals("yes") || t.equals("on");
    }

    private static String kb(String s) { try { return String.valueOf(Long.parseLong(s.trim()) * 1024); } catch (Exception e) { return s; } }
    private static String mb(String s) { try { return String.valueOf(Long.parseLong(s.trim()) * 1024 * 1024); } catch (Exception e) { return s; } }

    private static long longOf(Properties p, String key, long dflt) {
        try {
            return Long.parseLong(p.getProperty(key, String.valueOf(dflt)).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }
}
