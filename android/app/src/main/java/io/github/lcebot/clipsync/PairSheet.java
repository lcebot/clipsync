package io.github.lcebot.clipsync;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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
    private final ViewGroup list;
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
        title.setText(R.string.pair_offer_title);
        text.setText(R.string.pair_offer_opening);
        show(progress, true);
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
        text.setText(R.string.pair_offer_body);
        code.setText(p.code);
        show(code, true);
        progressText.setText(R.string.pair_waiting);
        long until = System.currentTimeMillis() + Pairing.WINDOW_MS;
        countdown = new Runnable() {
            @Override public void run() {
                long left = Math.max(0, until - System.currentTimeMillis());
                progressText.setText(a.getString(R.string.pair_waiting_for, left / 1000));
                if (left > 0) ui.postDelayed(this, 1000);
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
        list.removeAllViews();
        show(list, false);
        show(codeLayout, false);
        show(action, false);
        show(progress, true);
        progressText.setText(R.string.pair_searching);
        worker.execute(() -> {
            List<Mdns.Instance> found = PairJoiner.find(a, BROWSE_MS);
            post(() -> {
                show(progress, false);
                if (found.isEmpty()) {
                    text.setText(R.string.pair_none_found);
                    button(R.string.pair_search_again, v -> browse());
                    return;
                }
                text.setText(R.string.pair_pick);
                for (Mdns.Instance i : found) addDevice(i);
                show(list, true);
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
        show(list, false);
        text.setText(a.getString(R.string.pair_enter_code, device.name));
        show(codeLayout, true);
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
        show(action, false);
        show(progress, true);
        progressText.setText(R.string.pair_connecting);
        worker.execute(() -> {
            try {
                PairJoiner.Result r = PairJoiner.join(a, device, typed);
                PairJoiner.apply(a, r.pskHex);
                post(() -> {
                    show(codeLayout, false);
                    // The form is showing the key that was there a moment ago, which is now wrong.
                    a.reloadAfterPairing();
                    done(a.getString(R.string.pair_join_done, r.device));
                });
            } catch (Exception e) {
                Logger.i("pairing: " + e);
                post(() -> {
                    show(progress, false);
                    // Back to the code field rather than to the start: the overwhelmingly likely
                    // cause is a mistyped digit, and making the user find the device again would
                    // spend another of the provider's five attempts on the way.
                    codeLayout.setError(String.valueOf(e.getMessage()));
                    show(codeLayout, true);
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
        show(progress, false);
        show(code, false);
        show(list, false);
        show(codeLayout, false);
        text.setText(message);
        button(R.string.pair_close, v -> sheet.dismiss());
    }

    // ------------------------------------------------------------------ small helpers
    private void button(int labelRes, View.OnClickListener onClick) {
        action.setText(labelRes);
        Haptics.onClick(action, () -> onClick.onClick(action));
        show(action, true);
    }

    private static void show(View v, boolean visible) {
        v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ first run
    /**
     * The three ways in, offered once, to a device that has no key at all.
     *
     * <p>Two real choices and an escape hatch, and the escape hatch is a text button rather than a
     * third peer of the other two: typing 64 hex characters is what pairing exists to avoid, so
     * offering it as an equal would be advertising the worst path.
     *
     * <p>Not dismissible by tapping outside. A device with no key does nothing at all, so a dialog
     * that can be waved away leaves the user in front of a settings page with no indication that
     * anything is required — which is the state this exists to get them out of.
     */
    static void firstRun(MainActivity a) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
                .setTitle(R.string.first_run_title)
                .setMessage(R.string.first_run_body)
                .setCancelable(false)
                .setPositiveButton(R.string.first_run_join, (d, w) -> join(a))
                .setNegativeButton(R.string.first_run_generate, (d, w) -> generateThenOffer(a))
                .setNeutralButton(R.string.first_run_manual, (d, w) -> { })
                .show();
    }

    /**
     * Make a key, then hand it out — which is what "this is my first device" means: there is nothing
     * to pair with yet, so this device becomes the one the others join.
     */
    private static void generateThenOffer(MainActivity a) {
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
