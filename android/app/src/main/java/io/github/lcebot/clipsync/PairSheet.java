package io.github.lcebot.clipsync;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.transition.TransitionManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
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
    private final MainActivity a;
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

    /** How long a browse runs before reporting what it has. */
    private static final long BROWSE_MS = 4_000;

    // ------------------------------------------------------------------ entry points
    /** The device that holds the key offers it. Reached from *Pair new device*. */
    static void offer(MainActivity a) {
        String psk = Config.raw(a).getProperty("psk", "");
        if (Config.checkPsk(psk) != null) {
            // Nothing to give away yet. Said rather than silently disabled, because the button is in
            // a card the user has just opened deliberately and a control that does nothing when
            // pressed teaches less than one that explains itself.
            a.snack(R.string.pair_no_key);
            return;
        }
        new PairSheet(a).startOffering(psk);
    }

    /** The device that wants the key goes looking. Reached from first run. */
    static void join(MainActivity a) {
        new PairSheet(a).startJoining();
    }

    private PairSheet(MainActivity a) {
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
        sheet.show();
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
    }

    /** Post to the main thread, unless the sheet has already gone. */
    private void post(Runnable r) {
        ui.post(() -> {
            if (!closed) r.run();
        });
    }

    // ------------------------------------------------------------------ offering
    private void startOffering(String pskHex) {
        state(false, true, false, false, false);
        title.setText(R.string.pair_offer_title);
        text.setText(R.string.pair_offer_opening);
        progressText.setText(R.string.pair_opening);
        worker.execute(() -> {
            try {
                PairProvider p = new PairProvider(a, pskHex, new PairProvider.Listener() {
                    @Override public void onPaired(String device, String type) {
                        post(() -> done(a.getString(R.string.pair_offer_done, device)));
                    }

                    @Override public void onClosed(boolean burned) {
                        post(() -> failed(a.getString(burned ? R.string.pair_burned : R.string.pair_expired)));
                    }
                });
                post(() -> offering(p));
            } catch (Exception e) {
                Logger.w("pairing: cannot open a window: " + e);
                post(() -> failed(a.getString(R.string.pair_cannot_open, String.valueOf(e.getMessage()))));
            }
        });
    }

    private void offering(PairProvider p) {
        provider = p;
        state(true, true, false, false, false);
        text.setText(R.string.pair_offer_body);
        code.setText(p.code);
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
                PairJoiner.apply(a, r.pskHex);
                post(() -> {
                    // The form is showing the key that was there a moment ago, which is now wrong.
                    a.reloadAfterPairing();
                    done(a.getString(R.string.pair_join_done, r.device));
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
    private void done(String message) {
        finish(message);
        // Only on success, and only here: the key is in place, so the service should be running with
        // it rather than with whatever it started the day holding.
        a.restartServiceAfterPairing();
    }

    private void failed(String message) {
        finish(message);
    }

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
            TransitionManager.beginDelayedTransition(root, a.visibilityMotion(changing.toArray(new View[0])));
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
     * <p>Called by {@link MainActivity} with the welcome screen's answer. The choice is presented
     * there and acted on here, because the sheets belong to the page whose fields they rewrite.
     */
    static void generateAndOffer(MainActivity a) {
        try {
            Properties v = new Properties();
            v.setProperty("psk", Crypto.randomPskHex());
            v.setProperty("discovery", "true");
            Config.save(a, v);
            a.reloadAfterPairing();
            a.restartServiceAfterPairing();
            offer(a);
        } catch (Exception e) {
            Logger.w("pairing: cannot generate a key: " + e);
            a.snack(R.string.pair_cannot_generate);
        }
    }
}
