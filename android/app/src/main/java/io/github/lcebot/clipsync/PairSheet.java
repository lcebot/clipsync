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
 * The two ends of pairing, on one surface: a device that already has the pre-shared key hands it to
 * one that does not, over mDNS, authorised by a short code the user carries across by eye.
 *
 * <p><b>Offering</b>: the device that has the key shows a nine-digit code, in groups of three, and
 * waits.
 * <b>Joining</b>: the device that wants it browses, picks, and types the code it is being shown.
 * They are the same conversation from opposite sides, which is why they are one sheet and one file;
 * what differs is which pieces are visible and what the button does.
 *
 * <p>Everything the mechanism does blocks for <em>seconds</em>: a browse waits out its four-second
 * window, the key derivation is slow on purpose ({@link Pairing#channelKey}), and the provider's
 * user has twenty seconds to approve this device, so all of it runs on {@link #worker} and comes
 * back through {@link #ui}. Off the main thread is only half of what that costs: a bottom sheet that
 * sits still for seconds is a bottom sheet the user believes has died, so every state that starts
 * such a call shows the progress row and says which of them it is in before handing the work over. The
 * rule in here is that no method touches a view except on the main thread, and none of the pairing calls
 * happen on it. {@link #askUser} is the one place that crosses in the other direction as well:
 * the question goes to the main thread and the answer is waited for on the caller's.
 *
 * <p><b>The caller must hold this and {@link #dismiss()} it when its Activity is destroyed.</b> A
 * rotation destroys and recreates the Activity while this object, its dialog and, the part that
 * matters, the {@link PairProvider} behind it carry on: an advertisement offering the key to the
 * network with nothing on screen to say so, and a code nobody can read any more.
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
     * One thread, not a pool: pairing is strictly sequential, browse, then connect, then write,
     * and a second one could only be doing something the user has already moved on from.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clipsync-pair-ui");
        t.setDaemon(true);
        return t;
    });

    private PairProvider provider;
    /** The open "may it have the key?" dialog, or null. Main thread only. */
    private androidx.appcompat.app.AlertDialog ask;
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
     * things from it: one has fields showing the old key, the other has nothing to update and a
     * reason to close itself. An interface rather than an Activity type is what lets the same sheet
     * serve both, appearing over whichever screen opened it.
     */
    interface Host {
        /** The key in the configuration has just changed. */
        default void keyChanged() { }

        /**
         * The sheet has closed, however it ended.
         *
         * <p>Reported unconditionally, and the host decides what it means, which is the division
         * that matters here. The sheet cannot know: generating a key and then pairing nobody is a
         * *finished* setup on the welcome screen (there is a key now) and nothing at all on the
         * settings page. A sheet that only reported successful pairings left the welcome screen
         * stranded in exactly that case.
         */
        default void closed() { }
    }

    private final Host host;

    // ------------------------------------------------------------------ entry points
    /**
     * The device that holds the key offers it. Reached from *Pair new devices*.
     *
     * @return the sheet, for the caller to {@link #dismiss()} in {@code onDestroy}
     */
    static PairSheet offer(Activity a, Host host) {
        Properties p = Config.raw(a);
        String psk = p.getProperty("psk", "");
        PairSheet sheet = new PairSheet(a, host);
        if (Config.checkPsk(psk) != null) {
            // Nothing to give away yet. Said in the sheet rather than as a snackbar: the user pressed
            // a button and a surface opening to explain itself is a better answer than a surface not
            // opening at all.
            sheet.finish(a.getString(R.string.pair_no_key));
            return sheet;
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
        return sheet;
    }

    /** The device that wants the key goes looking. @return the sheet, to be dismissed with its host */
    static PairSheet join(Activity a, Host host) {
        PairSheet sheet = new PairSheet(a, host);
        sheet.startJoining();
        return sheet;
    }

    private PairSheet(Activity a, Host host) {
        this.host = host;
        this.a = a;
        sheet = new BottomSheetDialog(a);
        sheet.setContentView(R.layout.sheet_pair);
        // The dialog's CoordinatorLayout, one level above the sheet frame, the same scene root the
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
        followKeyboard(sheet.getWindow());
        sheet.show();
    }

    /**
     * Keep the keyboard from covering the field by asking the window to resize, and by doing
     * nothing else whatsoever.
     *
     * <p>This dialog's window already resizes when the keyboard appears, so the space the sheet has
     * to live in has already shrunk by the keyboard's height before anything here runs. Padding the
     * sheet's content by the keyboard height on top of that would double-count it: a sheet sized for
     * {@code content + keyboard} inside a window already sized to {@code screen − keyboard} overflows
     * its window and loses whatever does not fit off the bottom, in this layout, the Connect button.
     *
     * <p>So: state the resize rather than rely on it, and add nothing else. ADJUST_RESIZE is
     * deprecated for activity windows under edge-to-edge, where the decor no longer fits system
     * windows and the app is expected to consume insets itself, but a dialog window is not that
     * window, and BottomSheetDialog leaves this one fitting them. Asking for it explicitly is how the
     * behaviour this depends on stops being an accident.
     */
    private void followKeyboard(android.view.Window w) {
        if (w == null) return;
        w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    /**
     * Everything that has to stop when the sheet goes.
     *
     * <p>The window most of all: an advertisement outliving the sheet that opened it would keep this
     * device offering its key to the network with nothing on screen to say so.
     */
    private void shut() {
        if (closed) return;                          // dismiss() and the dismiss listener both land here
        closed = true;
        if (countdown != null) ui.removeCallbacks(countdown);
        // Before the provider: the dialog's dismiss listener releases whichever provider thread is
        // blocked in askUser(), and it releases it as a refusal, which is the right answer for a
        // window that is being torn down.
        closeAsk();
        PairProvider p = provider;
        provider = null;
        if (p != null) p.close();
        worker.shutdownNow();
        host.closed();
    }

    /**
     * Close the sheet because its Activity is going.
     *
     * <p>The one thing an Activity must do with the object these entry points return. Dismissing the
     * dialog runs {@link #shut()} through the dismiss listener, which is what stops the advertisement
     * and the accept loop; a bottom sheet is not a child of the Activity's view tree and is not torn
     * down with it, so without this a rotation leaves a live pairing window with no UI attached.
     */
    void dismiss() {
        if (sheet.isShowing()) sheet.dismiss();
        else shut();                                 // never shown, or already gone: stop it anyway
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
        // size: the code as a greyed placeholder in the same grouped nine digits, and the instruction in its
        // final wording rather than a short "Opening…" that is replaced by three lines a moment
        // later. That swap was most of the height jump; the code line was only ever one of two.
        // The way out is on screen from the first frame, dimmed and reading "Cancel". A window that
        // stays open for two minutes waiting for somebody to walk over with a phone needs a way to
        // say "never mind" that is not the back gesture, and putting it here from the start rather
        // than growing it in later is the same argument as the code placeholder above: the sheet
        // measures once, at the size it is going to be.
        state(SHOW, SHOW, HIDE, HIDE, SHOW);
        button(R.string.pair_cancel, false, v -> sheet.dismiss());
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

                    @Override public boolean onAskUser(String device, String type) {
                        return askUser(device, type);
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
        // first device animated (the list appeared) and every one after it did not, because the list was
        // already visible, nothing it watches had changed, and the sheet grew in a single frame.
        // What is actually arriving is the line, so the line is what is named.
        //
        // Built before the transition begins and while still detached, like every other appearing
        // view here: a view that is not in the start scene is one that enters.
        View[] fading = list.getVisibility() == View.VISIBLE ? new View[]{line} : new View[]{line, list};
        if (root != null) {
            TransitionManager.beginDelayedTransition(root, Ui.visibilityMotion(fading));
        }
        list.addView(line);
        list.setVisibility(View.VISIBLE);
        // Inside the transition begun above, so the button's change of width slides with the line
        // arriving rather than snapping beside it. The window is still open and the code is still
        // valid; nothing here ends anything, but what leaving MEANS has changed: until now it
        // abandoned an attempt, and from now on it finishes one. Lighting the button up is how the
        // user is told that the thing they came to do is done, without taking the window away from
        // them while a second device might still be on its way over.
        button(R.string.pair_done, true, v -> sheet.dismiss());
        settled = true;
    }

    /**
     * "<i>Galaxy Tab wants the key. Give it?</i>", asked on the main thread, answered on the
     * provider's.
     *
     * <p>Called from {@link PairProvider}'s accept loop, which blocks until this returns. That is
     * the whole design: the key does not leave the device until somebody has looked at a name and
     * said yes, and the only way to hold the handshake open while a person decides is to block the
     * thread that would otherwise be sending the key.
     *
     * <p>Three ways to reach "no", and they are deliberately the same answer: the button, dismissing
     * the dialog (Back, or the sheet going away under it), and the timeout. A refusal costs the
     * caller nothing but its connection; see {@link PairProvider.Listener#onAskUser}, so erring
     * towards no has no cost the user can feel; erring towards yes gives away the key.
     */
    private boolean askUser(String device, String type) {
        java.util.concurrent.CountDownLatch answered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean yes = new java.util.concurrent.atomic.AtomicBoolean();
        ui.post(() -> {
            if (closed) {                                    // the sheet went while we were posting
                answered.countDown();
                return;
            }
            androidx.appcompat.app.AlertDialog d = new com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
                    .setTitle(R.string.pair_ask_title)
                    .setMessage(a.getString(R.string.pair_ask_body, device,
                            type == null || type.isEmpty() ? "?" : type))
                    .setNegativeButton(R.string.pair_ask_no, null)
                    .setPositiveButton(R.string.pair_ask_yes, (x, w) -> yes.set(true))
                    // One listener rather than one per button: dismissal is the event that always
                    // happens, however it ended, so the latch cannot be left uncounted by a path
                    // nobody thought of (Back, or shut() tearing the dialog down).
                    //
                    // The chain survives this call. MaterialAlertDialogBuilder overrides every
                    // setter it inherits purely to narrow the return type, setOnDismissListener
                    // included, declared as returning MaterialAlertDialogBuilder and not
                    // AlertDialog.Builder (its source at 1.14.0, under the comment "The following
                    // methods are all pass-through methods used to specify the return type for the
                    // builder chain"). So create() below is Material's own, which is what applies
                    // the M3 shape and background; nothing here silently falls back to AppCompat.
                    .setOnDismissListener(x -> {
                        ask = null;
                        answered.countDown();
                    })
                    .create();
            ask = d;
            d.show();
        });
        boolean inTime;
        try {
            inTime = answered.await(PairProvider.ASK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            inTime = false;
        }
        if (!inTime) {
            // Nobody is watching. Take the question off the screen as well as answering it, or the
            // next caller would arrive behind a stale dialog naming a device that has long gone.
            ui.post(this::closeAsk);
            return false;
        }
        return yes.get();
    }

    private void closeAsk() {
        androidx.appcompat.app.AlertDialog d = ask;
        ask = null;
        if (d != null && d.isShowing()) d.dismiss();
    }

    private void offering(PairProvider p) {
        provider = p;
        // Grouped for the eye and only here: Pairing.grouped inserts two spaces into a string that
        // is nine digits everywhere else in this app. p.code, the ungrouped digits, is what
        // PairProvider derived its channel key from, and nothing on this side ever feeds the
        // display string back into anything.
        //
        // Only the eleven characters and their colour change: same view, same font, same length as
        // the placeholder (which is grouped too, for exactly this reason), so nothing reflows.
        code.setText(Pairing.grouped(p.code));
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
        state(HIDE, SHOW, HIDE, HIDE, HIDE);
        list.removeAllViews();
        progressText.setText(R.string.pair_searching);
        worker.execute(() -> {
            List<Mdns.Instance> found = PairJoiner.find(a, BROWSE_MS);
            post(() -> {
                if (found.isEmpty()) {
                    state(HIDE, HIDE, HIDE, HIDE, SHOW);
                    text.setText(R.string.pair_none_found);
                    button(R.string.pair_search_again, v -> browse());
                    return;
                }
                // Filled before the list is shown, so the transition measures the height it is
                // actually going to be rather than animating to an empty box and jumping after.
                for (Mdns.Instance i : found) addDevice(i);
                state(HIDE, HIDE, SHOW, HIDE, HIDE);
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
        // The progress row is gone, not reserved. Holding it open through the whole code-entry
        // screen, on top of two reserved lines of body text and a permanent error line, pushed
        // the field down and squeezed it, which is a worse thing to look at than an edge that
        // slides. Ui.visibilityMotion animates the row in when it is wanted.
        state(HIDE, HIDE, HIDE, SHOW, SHOW);
        text.setText(a.getString(R.string.pair_enter_code, device.name));
        codeLayout.setError(null);
        button(R.string.pair_connect, v -> connect(device));
        typeCode();
    }

    /**
     * Put the cursor in the code field and raise the keyboard.
     *
     * <p>There is exactly one thing to do at this point and it needs nine keystrokes, so making the
     * user tap the field first is a tap that carries no decision. Posted rather than called inline:
     * the field has only just been made visible, and a view that has not been laid out cannot take
     * focus, so the request would be dropped and the keyboard would never come.
     *
     * <p>The field is set VISIBLE synchronously inside {@link #state}, before this posts, so the
     * transition animating the sheet's height is beside the point: it moves an edge, it does not
     * delay the layout pass this waits for. The retry below is there anyway, because "focus failed"
     * and "keyboard never came" look identical to a user and cost one frame to rule out.
     */
    private void typeCode() {
        EditText field = codeLayout.getEditText();
        if (field == null) return;
        field.post(new Runnable() {
            private boolean retried;

            @Override public void run() {
                if (closed) return;
                if (!field.requestFocus()) {
                    // One more frame, once. A view that is visible but not yet attached refuses
                    // focus and says so by returning false; a view that will never take it refuses
                    // twice, and a second post is cheaper than a keyboard that does not appear.
                    if (!retried) {
                        retried = true;
                        field.post(this);
                    }
                    return;
                }
                raiseIme(field);
            }
        });
    }

    /** The keyboard, once the field has the focus that makes asking for it meaningful. */
    private void raiseIme(EditText field) {
        // The platform controller rather than InputMethodManager.showSoftInput: it is the API that
        // actually knows about the window this sheet lives in, and needs no guesses about which
        // flags mean "show it because the user is about to type".
        android.view.WindowInsetsController ime = field.getWindowInsetsController();
        if (ime != null) ime.show(WindowInsets.Type.ime());
    }

    private void connect(Mdns.Instance device) {
        // Stripped first, and this is the one place it may be: the code is SHOWN in groups of three
        // on the other device, so it is copied down and typed with the spaces in it, and a user who
        // types what they were shown has made no mistake. The field's inputType="number" already
        // drops them for most IMEs, which is exactly why this cannot be left to the field: "most"
        // is not a thing to derive a key from, and a paste or an IME that lets one through would
        // otherwise be measured as ten characters and stretched as ten.
        //
        // What is below this line is nine digits. Pairing.channelKey takes the digits, never the
        // display form; Pairing.grouped is the only place a space is ever added, and it is the last
        // thing that happens before a TextView.
        String raw = codeLayout.getEditText() == null ? "" : codeLayout.getEditText().getText().toString();
        String typed = Pairing.digitsOnly(raw);
        if (typed.length() != Pairing.CODE_DIGITS) {
            // No state change and no transition: the error line is reserved in the layout
            // (errorEnabled), so this writes a message into a row that is already there.
            codeLayout.setError(a.getString(R.string.pair_code_length));
            return;
        }
        // The button goes and the row arrives in its place. The sheet grows or shrinks by the
        // difference and Ui.visibilityMotion slides it there.
        state(HIDE, SHOW, HIDE, SHOW, HIDE);
        // After state(), like every other text on this sheet, beginDelayedTransition has captured
        // the start scene by now, so whatever this does to the height is animated rather than
        // snapped. It should do nothing to it, the line being reserved, but the order is the rule.
        codeLayout.setError(null);
        // Two labels rather than one, because the wait has two halves and no socket exists during
        // the first: stretching the nine digits into a channel key (Pairing.SCRYPT_N), and only
        // afterwards the network. A single "Connecting" label would describe something that has not
        // started yet, over the exact stretch of time in which a silent sheet looks like a hung one,
        // right after the user has finished typing and is watching for a reaction. The derivation is
        // usually brief (see Pairing.SCRYPT_P, chosen so its pessimistic case is about one second),
        // but it is the honest label for a slow or loaded device where that second becomes two, and
        // the sheet would otherwise be claiming to be on the network while it is not.
        progressText.setText(R.string.pair_checking);
        worker.execute(() -> {
            try {
                PairJoiner.Result r = PairJoiner.join(a, device, typed,
                        () -> post(() -> progressText.setText(R.string.pair_connecting)));
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
                    state(HIDE, HIDE, HIDE, SHOW, SHOW);
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
        // The one place everything really does go, rather than being held: an ending keeps nothing
        // for a next state, because there is no next state. What is left is a sentence and a Close.
        state(HIDE, HIDE, HIDE, HIDE, SHOW);
        text.setText(message);
        button(R.string.pair_close, v -> sheet.dismiss());
    }

    // ------------------------------------------------------------------ small helpers
    /** Label and action for the one button. Whether it is <em>shown</em> is {@link #state}'s job. */
    private void button(int labelRes, View.OnClickListener onClick) {
        button(labelRes, true, onClick);
    }

    /**
     * The same, choosing how loudly it asks to be pressed.
     *
     * <p>Only the offering window uses anything but {@code true}. Everywhere else the button is the
     * one thing left to do, so it is filled; there it sits on screen for two minutes next to a code
     * the user is reading out, and a filled button beside the thing you are meant to be looking at
     * is a button that gets pressed by mistake.
     */
    private void button(int labelRes, boolean bright, View.OnClickListener onClick) {
        action.setText(labelRes);
        // Tinted rather than restyled: a MaterialButton's style is fixed when it is inflated, so
        // "make it a tonal button now and a filled one later" is not a thing that can be asked for
        // at runtime. Tint plus text colour is, and it lands on the same two M3 roles the two
        // styles would have used.
        int bg = bright
                ? MaterialColors.getColor(action, androidx.appcompat.R.attr.colorPrimary)
                : MaterialColors.getColor(action, com.google.android.material.R.attr.colorSurfaceContainerHighest);
        int fg = bright
                ? MaterialColors.getColor(action, com.google.android.material.R.attr.colorOnPrimary)
                : MaterialColors.getColor(action, com.google.android.material.R.attr.colorOnSurfaceVariant);
        action.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bg));
        action.setTextColor(fg);
        Haptics.onClick(action, () -> onClick.onClick(action));
    }

    /** On screen. */
    private static final int SHOW = View.VISIBLE;
    /** Off screen, and its space with it: the sheet is shorter by exactly this view. */
    private static final int HIDE = View.GONE;
    // Only these two values are used, never INVISIBLE: reserving space for a view that is not
    // currently shown stacks up fast on this sheet, which already reserves two lines of body text
    // and an error line under the code field. Ui.visibilityMotion animates the resulting height
    // change instead, trading a taller sheet for a short slide.

    /**
     * The five pieces that come and go, set in one call so the sheet can animate between states.
     *
     * <p>One call rather than five {@code setVisibility}s, because a transition has to be started
     * <b>before</b> anything changes and exactly once: toggling views one at a time either starts
     * five overlapping transitions or, worse, starts one and then changes things it has already
     * captured. Collecting the differences first also means a state that changes nothing animates
     * nothing, which is what keeps the once-a-second countdown from re-running the motion.
     *
     * <p>Each piece is {@link #SHOW} or {@link #HIDE}.
     *
     * <p>Call this first in a state, then set the text. {@code beginDelayedTransition} captures the
     * start values as it is called, so text set beforehand is text the transition thinks was always
     * there, and the height change it causes would snap while everything around it slid.
     */
    private void state(int codeShown, int progressShown, int listShown,
                       int fieldShown, int actionShown) {
        View[] views = {code, progress, list, codeLayout, action};
        int[] want = {codeShown, progressShown, listShown, fieldShown, actionShown};
        java.util.List<View> changing = new java.util.ArrayList<>();
        for (int i = 0; i < views.length; i++) {
            // Compared by value rather than by "is it VISIBLE", so that a state which changes
            // nothing animates nothing, which is what keeps the once-a-second countdown from
            // restarting the motion on every tick.
            if (views[i].getVisibility() != want[i]) changing.add(views[i]);
        }
        // Not on the first state, which is applied as the sheet is still sliding up: the entrance is
        // the animation at that moment, and a second one running inside it reads as a stutter rather
        // than as a change.
        if (!changing.isEmpty() && root != null && settled) {
            TransitionManager.beginDelayedTransition(root, Ui.visibilityMotion(changing.toArray(new View[0])));
        }
        settled = true;
        for (int i = 0; i < views.length; i++) {
            views[i].setVisibility(want[i]);
        }
    }

    // ------------------------------------------------------------------ first run
    /**
     * Make a key, then hand it out, which is what "this is my first device" means: there is nothing
     * to pair with yet, so this device becomes the one the others join.
     *
     * <p>Discovery is turned on with it, because the next thing this device does is advertise.
     */
    static PairSheet generateAndOffer(Activity a, Host host) {
        try {
            Properties v = new Properties();
            Config.freshKey(v, Crypto.randomPskHex());
            v.setProperty("discovery", "true");
            Config.save(a, v);
            host.keyChanged();
            SyncService.startOrReload(a);
            return offer(a, host);
        } catch (Exception e) {
            Logger.w("pairing: cannot generate a key: " + e);
            PairSheet sheet = new PairSheet(a, host);
            sheet.finish(a.getString(R.string.pair_cannot_generate));
            return sheet;
        }
    }
}
