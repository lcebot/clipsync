package io.github.lcebot.clipsync;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.TextView;

import androidx.transition.TransitionManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The two ends of pairing, on one surface (docs/p2p-plan.md §12).
 *
 * <p><b>Offering</b> — the device that has the key shows a six-digit code and waits.
 * <b>Joining</b> — the device that wants it browses, picks, and types the code it is being shown.
 * They are the same conversation from opposite sides, which is why they are one sheet and one file;
 * what differs is which pieces are visible and what the button does.
 *
 * <p>Everything the mechanism does blocks — a browse takes seconds, and the key derivation is slow
 * on purpose — so all of it runs on {@link #worker} and comes back through {@link #ui}. The rule in
 * here is that no method touches a view except on the main thread, and none of the pairing calls
 * happen on it.
 */
final class PairSheet {
    private final Activity a;
    private final BottomSheetDialog sheet;
    private final TextView title, text, code, progressText;
    private final View progress;
    private final ViewGroup list, root;
    private final TextInputLayout codeLayout;
    private final MaterialButton action;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /**
     * One thread, not a pool: pairing is strictly sequential — browse, then connect, then write —
     * and a second one could only be doing something the user has already moved on from.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clipsync-pair-ui");
        t.setDaemon(true);
        return t;
    });

    private PairProvider provider;
    private Runnable countdown;
    private volatile boolean closed;
    /** False until the first state has been applied; see {@link #state}. */
    private boolean settled;
    /** How many devices took the key in this window. Decides what its ending is called. */
    private int paired;

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    /** The sheet's one horizontal measurement, matching every view in its layout. */
    private int gutter() {
        return dp(24);
    }

    /** How long a browse runs before reporting what it has. */
    private static final long BROWSE_MS = 4_000;

    /**
     * What the screen behind the sheet wants to know.
     *
     * <p>The sheet runs over the settings page and over the welcome screen, and those want different
     * things from it — one has fields showing the old key, the other has nothing to update and a
     * reason to close itself. An interface rather than an Activity type is what lets the same sheet
     * serve both; before this it took a {@code MainActivity} and could only ever appear there, which
     * is why the welcome screen used to have to finish first and hand the job back.
     */
    interface Host {
        /** The key in the configuration has just changed. */
        default void keyChanged() { }

        /**
         * The sheet has closed, however it ended.
         *
         * <p>Reported unconditionally, and the host decides what it means — which is the division
         * that matters here. The sheet cannot know: generating a key and then pairing nobody is a
         * *finished* setup on the welcome screen (there is a key now) and nothing at all on the
         * settings page. A sheet that only reported successful pairings left the welcome screen
         * stranded in exactly that case.
         */
        default void closed() { }
    }

    private final Host host;

    // ------------------------------------------------------------------ entry points
    /** The device that holds the key offers it. Reached from *Pair new devices*. */
    static void offer(Activity a, Host host) {
        Properties p = Config.raw(a);
        String psk = p.getProperty("psk", "");
        PairSheet sheet = new PairSheet(a, host);
        if (Config.checkPsk(psk) != null) {
            // Nothing to give away yet. Said in the sheet rather than as a snackbar: the user pressed
            // a button and a surface opening to explain itself is a better answer than a surface not
            // opening at all.
            sheet.finish(a.getString(R.string.pair_no_key));
            return;
        }
        // From the saved configuration, not the form: this is the port the service is actually
        // listening on, and handing a joiner a number that has only been typed would point it at a
        // port nothing answers until somebody presses Apply.
        int port = 0;
        try {
            port = Integer.parseInt(p.getProperty("port", "").trim());
        } catch (NumberFormatException ignored) {
        }
        sheet.startOffering(psk, port);
    }

    /** The device that wants the key goes looking. */
    static void join(Activity a, Host host) {
        new PairSheet(a, host).startJoining();
    }

    private PairSheet(Activity a, Host host) {
        this.host = host;
        this.a = a;
        sheet = new BottomSheetDialog(a);
        sheet.setContentView(R.layout.sheet_pair);
        // The dialog's CoordinatorLayout, one level above the sheet frame — the same scene root the
        // peer sheet uses, and for the same reason: a bottom sheet is anchored to the bottom edge,
        // so it changes height by moving its TOP edge, and inside the frame nothing moves at all.
        // Rooting the transition at the frame's parent is what lets that edge be animated instead of
        // snapping between states.
        root = sheet.findViewById(com.google.android.material.R.id.coordinator);
        title = sheet.findViewById(R.id.pair_title);
        text = sheet.findViewById(R.id.pair_text);
        code = sheet.findViewById(R.id.pair_code);
        progress = sheet.findViewById(R.id.pair_progress);
        progressText = sheet.findViewById(R.id.pair_progress_text);
        list = sheet.findViewById(R.id.pair_list);
        codeLayout = sheet.findViewById(R.id.pair_code_layout);
        action = sheet.findViewById(R.id.pair_action);
        sheet.setOnDismissListener(d -> shut());
        followKeyboard(sheet.findViewById(R.id.pair_root));
        sheet.show();
    }

    /**
     * Move the sheet's contents with the keyboard, in step with it.
     *
     * <p>Two faults, and they look like one. The <b>gap</b> is a double inset: the sheet already
     * pads itself clear of the navigation bar, and the keyboard covers the navigation bar, so adding
     * both left exactly a navigation bar of empty space under the field. It is
     * {@code max(ime, navigation)} that is wanted, never a sum — only one of the two is ever in
     * front of the sheet.
     *
     * <p>The <b>jump</b> is a timing fault. Insets are dispatched once, up front, with the value the
     * keyboard will have when it has finished arriving — so padding applied there is applied whole,
     * a frame before the keyboard has moved at all. {@code DISPATCH_MODE_STOP} holds that dispatch
     * back until the animation is over and hands us {@code onProgress} instead, which is the
     * keyboard's real position on every frame. Applying it there is what makes the sheet travel with
     * the keyboard rather than beat it to the top.
     */
    private void followKeyboard(View content) {
        if (content == null) return;
        final int base = content.getPaddingBottom();
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            padBelow(v, insets, base);
            return insets;
        });
        content.setWindowInsetsAnimationCallback(
                new android.view.WindowInsetsAnimation.Callback(
                        android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP) {
                    @Override
                    public WindowInsets onProgress(WindowInsets insets,
                                                   java.util.List<android.view.WindowInsetsAnimation> running) {
                        padBelow(content, insets, base);
                        return insets;
                    }
                });
    }

    private static void padBelow(View v, WindowInsets insets, int base) {
        int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
        int nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
        v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), base + Math.max(ime, nav));
    }

    /**
     * Everything that has to stop when the sheet goes.
     *
     * <p>The window most of all: an advertisement outliving the sheet that opened it would keep this
     * device offering its key to the network with nothing on screen to say so.
     */
    private void shut() {
        closed = true;
        if (countdown != null) ui.removeCallbacks(countdown);
        PairProvider p = provider;
        provider = null;
        if (p != null) p.close();
        worker.shutdownNow();
        host.closed();
    }

    /** Post to the main thread, unless the sheet has already gone. */
    private void post(Runnable r) {
        ui.post(() -> {
            if (!closed) r.run();
        });
    }

    // ------------------------------------------------------------------ offering
    private void startOffering(String pskHex, int port) {
        // Everything that will ever be on this sheet is on it from the first frame, at its final
        // size: the code as a greyed placeholder of the same six digits, and the instruction in its
        // final wording rather than a short "Opening…" that is replaced by three lines a moment
        // later. That swap was most of the height jump — the code line was only ever one of two.
        state(true, true, false, false, false);
        title.setText(R.string.pair_offer_title);
        text.setText(R.string.pair_offer_body);
        code.setText(R.string.pair_code_placeholder);
        code.setTextColor(MaterialColors.getColor(code, com.google.android.material.R.attr.colorOutline));
        progressText.setText(R.string.pair_opening);
        worker.execute(() -> {
            try {
                PairProvider p = new PairProvider(a, pskHex, port, new PairProvider.Listener() {
                    @Override public void onPaired(String device, String type) {
                        post(() -> gave(device));
                    }

                    @Override public void onClosed(boolean burned) {
                        post(() -> finish(a.getString(burned ? R.string.pair_burned
                                : paired == 0 ? R.string.pair_expired
                                : R.string.pair_offer_over)));
                    }
                });
                post(() -> offering(p));
            } catch (Exception e) {
                Logger.w("pairing: cannot open a window: " + e);
                post(() -> finish(a.getString(R.string.pair_cannot_open, String.valueOf(e.getMessage()))));
            }
        });
    }

    /**
     * One more device has the key, and the window stays open for the next.
     *
     * <p>Appended rather than replacing the screen, because the code is still valid and still on
     * display: closing after the first device would mean a new window and a new code read out for
     * every other one, which is two minutes of work to save nothing. The list grows under the
     * countdown and is the record of what the window achieved.
     */
    private void gave(String device) {
        paired++;
        TextView line = new TextView(a);
        line.setText(a.getString(R.string.pair_offer_gave, device));
        line.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        line.setTextColor(MaterialColors.getColor(line, androidx.appcompat.R.attr.colorPrimary));
        line.setPadding(gutter(), 0, gutter(), dp(4));

        // Its own transition, not state(): state() only starts one when a VISIBILITY changes, so the
        // first device animated (the list appeared) and every one after it did not — the list was
        // already visible, nothing it watches had changed, and the sheet grew in a single frame.
        // What is actually arriving is the line, so the line is what is named.
        //
        // Built before the transition begins and while still detached, like every other appearing
        // view here: a view that is not in the start scene is one that enters.
        View[] fading = list.getVisibility() == View.VISIBLE ? new View[]{line} : new View[]{line, list};
        if (root != null) {
            TransitionManager.beginDelayedTransition(root, MainActivity.visibilityMotion(fading));
        }
        list.addView(line);
        list.setVisibility(View.VISIBLE);
        settled = true;
    }

    private void offering(PairProvider p) {
        provider = p;
        // Only the six characters and their colour change: same view, same font, same length as the
        // placeholder, so nothing reflows.
        code.setText(p.code);
        code.setTextColor(MaterialColors.getColor(code, androidx.appcompat.R.attr.colorPrimary));
        countdown = new Runnable() {
            @Override public void run() {
                long left = Math.max(0, p.closesAt - System.currentTimeMillis());
                // Rounded UP, and this is the whole of the reported bug. Truncating showed 120 at
                // t=0 and then 118 one tick later, because postDelayed(1000) is a minimum: the
                // second tick lands at 1000+ε, leaving 118999 ms, which divides to 118. Ceiling
                // makes the number mean "seconds remaining, at most", so the same instant reads 119
                // and no value is ever skipped.
                long secs = (left + 999) / 1000;
                progressText.setText(a.getString(R.string.pair_waiting_for, secs));
                if (secs <= 0) return;
                // Scheduled to the moment the displayed number changes, not a flat second later.
                // A fixed interval drifts by the scheduling delay every tick and the error
                // accumulates; landing on the boundary keeps every tick honest and makes the last
                // one arrive exactly as the window closes.
                ui.postDelayed(this, left - (secs - 1) * 1000);
            }
        };
        countdown.run();
    }

    // ------------------------------------------------------------------ joining
    private void startJoining() {
        title.setText(R.string.pair_join_title);
        text.setText(R.string.pair_join_body);
        browse();
    }

    private void browse() {
        state(false, true, false, false, false);
        list.removeAllViews();
        progressText.setText(R.string.pair_searching);
        worker.execute(() -> {
            List<Mdns.Instance> found = PairJoiner.find(a, BROWSE_MS);
            post(() -> {
                if (found.isEmpty()) {
                    state(false, false, false, false, true);
                    text.setText(R.string.pair_none_found);
                    button(R.string.pair_search_again, v -> browse());
                    return;
                }
                // Filled before the list is shown, so the transition measures the height it is
                // actually going to be rather than animating to an empty box and jumping after.
                for (Mdns.Instance i : found) addDevice(i);
                state(false, false, true, false, false);
                text.setText(R.string.pair_pick);
            });
        });
    }

    private void addDevice(Mdns.Instance device) {
        View card = a.getLayoutInflater().inflate(R.layout.item_status_card, list, false);
        ((TextView) card.findViewById(R.id.card_name)).setText(device.name);
        Haptics.onClick(card, () -> askCode(device));
        list.addView(card);
    }

    private void askCode(Mdns.Instance device) {
        state(false, false, false, true, true);
        text.setText(a.getString(R.string.pair_enter_code, device.name));
        codeLayout.setError(null);
        button(R.string.pair_connect, v -> connect(device));
        typeCode();
    }

    /**
     * Put the cursor in the code field and raise the keyboard.
     *
     * <p>There is exactly one thing to do at this point and it needs six keystrokes, so making the
     * user tap the field first is a tap that carries no decision. Posted rather than called inline:
     * the field has only just been made visible, and a view that has not been laid out cannot take
     * focus — the request would be dropped and the keyboard would never come.
     */
    private void typeCode() {
        EditText field = codeLayout.getEditText();
        if (field == null) return;
        field.post(() -> {
            if (closed || !field.requestFocus()) return;
            // The platform controller rather than InputMethodManager.showSoftInput: it is the API
            // that actually knows about the window this sheet lives in, and needs no guesses about
            // which flags mean "show it because the user is about to type".
            android.view.WindowInsetsController ime = field.getWindowInsetsController();
            if (ime != null) ime.show(WindowInsets.Type.ime());
        });
    }

    private void connect(Mdns.Instance device) {
        String typed = codeLayout.getEditText() == null ? "" : codeLayout.getEditText().getText().toString();
        if (typed.length() != 6) {
            codeLayout.setError(a.getString(R.string.pair_code_length));
            return;
        }
        codeLayout.setError(null);
        state(false, true, false, true, false);
        progressText.setText(R.string.pair_connecting);
        worker.execute(() -> {
            try {
                PairJoiner.Result r = PairJoiner.join(a, device, typed);
                PairJoiner.apply(a, r);
                post(() -> {
                    paired++;
                    // Whatever is behind the sheet is showing the key that was there a moment ago.
                    host.keyChanged();
                    // The key is in place, so the service should be running on it rather than on
                    // whatever it started the day holding.
                    SyncService.startOrReload(a);
                    finish(a.getString(R.string.pair_join_done, r.device));
                });
            } catch (Exception e) {
                Logger.i("pairing: " + e);
                post(() -> {
                    // Back to the code field rather than to the start: the overwhelmingly likely
                    // cause is a mistyped digit, and making the user find the device again would
                    // spend another of the provider's five attempts on the way.
                    state(false, false, false, true, true);
                    codeLayout.setError(String.valueOf(e.getMessage()));
                    button(R.string.pair_connect, v -> connect(device));
                });
            }
        });
    }

    // ------------------------------------------------------------------ endings
    private void finish(String message) {
        if (countdown != null) ui.removeCallbacks(countdown);
        provider = null;
        state(false, false, false, false, true);
        text.setText(message);
        button(R.string.pair_close, v -> sheet.dismiss());
    }

    // ------------------------------------------------------------------ small helpers
    /** Label and action for the one button. Whether it is <em>shown</em> is {@link #state}'s job. */
    private void button(int labelRes, View.OnClickListener onClick) {
        action.setText(labelRes);
        Haptics.onClick(action, () -> onClick.onClick(action));
    }

    /**
     * The five pieces that come and go, set in one call so the sheet can animate between states.
     *
     * <p>One call rather than five {@code setVisibility}s, because a transition has to be started
     * <b>before</b> anything changes and exactly once: toggling views one at a time either starts
     * five overlapping transitions or, worse, starts one and then changes things it has already
     * captured. Collecting the differences first also means a state that changes nothing animates
     * nothing, which is what keeps the once-a-second countdown from re-running the motion.
     *
     * <p>Call this first in a state, then set the text. {@code beginDelayedTransition} captures the
     * start values as it is called, so text set beforehand is text the transition thinks was always
     * there — and the height change it causes would snap while everything around it slid.
     */
    private void state(boolean codeShown, boolean progressShown, boolean listShown,
                       boolean fieldShown, boolean actionShown) {
        View[] views = {code, progress, list, codeLayout, action};
        boolean[] want = {codeShown, progressShown, listShown, fieldShown, actionShown};
        java.util.List<View> changing = new java.util.ArrayList<>();
        for (int i = 0; i < views.length; i++) {
            if ((views[i].getVisibility() == View.VISIBLE) != want[i]) changing.add(views[i]);
        }
        // Not on the first state, which is applied as the sheet is still sliding up: the entrance is
        // the animation at that moment, and a second one running inside it reads as a stutter rather
        // than as a change.
        if (!changing.isEmpty() && root != null && settled) {
            TransitionManager.beginDelayedTransition(root, MainActivity.visibilityMotion(changing.toArray(new View[0])));
        }
        settled = true;
        for (int i = 0; i < views.length; i++) {
            views[i].setVisibility(want[i] ? View.VISIBLE : View.GONE);
        }
    }

    // ------------------------------------------------------------------ first run
    /**
     * Make a key, then hand it out — which is what "this is my first device" means: there is nothing
     * to pair with yet, so this device becomes the one the others join.
     *
     * <p>Discovery is turned on with it, because the next thing this device does is advertise.
     */
    static void generateAndOffer(Activity a, Host host) {
        try {
            Properties v = new Properties();
            v.setProperty("psk", Crypto.randomPskHex());
            v.setProperty("discovery", "true");
            Config.save(a, v);
            host.keyChanged();
            SyncService.startOrReload(a);
            offer(a, host);
        } catch (Exception e) {
            Logger.w("pairing: cannot generate a key: " + e);
            new PairSheet(a, host).finish(a.getString(R.string.pair_cannot_generate));
        }
    }
}
