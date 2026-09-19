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
 * <p><b>Offering</b> — the device that has the key shows an eight-digit code and waits.
 * <b>Joining</b> — the device that wants it browses, picks, and types the code it is being shown.
 * They are the same conversation from opposite sides, which is why they are one sheet and one file;
 * what differs is which pieces are visible and what the button does.
 *
 * <p>Everything the mechanism does blocks for <em>seconds</em> — a browse waits out its four-second
 * window, the key derivation is slow on purpose ({@link Pairing#channelKey}), and the provider's
 * user has twenty seconds to approve this device — so all of it runs on {@link #worker} and comes
 * back through {@link #ui}. Off the main thread is only half of what that costs: a bottom sheet that
 * sits still for seconds is a bottom sheet the user believes has died, so every state that starts
 * such a call shows the progress row and says which of them it is in before handing the work over.
 * (The derivation used to be the longest of the three at around four seconds of PBKDF2; scrypt has
 * made it the shortest. The rule did not change with it — the browse and the approval wait were
 * always there, and they are not getting faster.) The
 * rule in here is that no method touches a view except on the main thread, and none of the pairing calls
 * happen on it. {@link #askUser} is the one place that crosses in the other direction as well:
 * the question goes to the main thread and the answer is waited for on the caller's.
 *
 * <p><b>The caller must hold this and {@link #dismiss()} it when its Activity is destroyed.</b> A
 * rotation destroys and recreates the Activity while this object, its dialog and — the part that
 * matters — the {@link PairProvider} behind it carry on: an advertisement offering the key to the
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
     * One thread, not a pool: pairing is strictly sequential — browse, then connect, then write —
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
     * and the accept loop — a bottom sheet is not a child of the Activity's view tree and is not torn
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
        // size: the code as a greyed placeholder of the same eight digits, and the instruction in its
        // final wording rather than a short "Opening…" that is replaced by three lines a moment
        // later. That swap was most of the height jump — the code line was only ever one of two.
        state(SHOW, SHOW, HIDE, HIDE, HIDE);
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
        // first device animated (the list appeared) and every one after it did not — the list was
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
        settled = true;
    }

    /**
     * "<i>Galaxy Tab wants the key. Give it?</i>" — asked on the main thread, answered on the
     * provider's.
     *
     * <p>Called from {@link PairProvider}'s accept loop, which blocks until this returns. That is
     * the whole design: the key does not leave the device until somebody has looked at a name and
     * said yes, and the only way to hold the handshake open while a person decides is to block the
     * thread that would otherwise be sending the key.
     *
     * <p>Three ways to reach "no", and they are deliberately the same answer: the button, dismissing
     * the dialog (Back, or the sheet going away under it), and the timeout. A refusal costs the
     * caller nothing but its connection — see {@link PairProvider.Listener#onAskUser} — so erring
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
                    // setter it inherits purely to narrow the return type — setOnDismissListener
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
        // Only the eight characters and their colour change: same view, same font, same length as the
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
        // The progress row is reserved and not removed: this state, "checking", and "wrong code" are
        // one screen, and the row and the button below it take turns on it. See KEEP.
        state(HIDE, KEEP, HIDE, SHOW, SHOW);
        text.setText(a.getString(R.string.pair_enter_code, device.name));
        codeLayout.setError(null);
        button(R.string.pair_connect, v -> connect(device));
        typeCode();
    }

    /**
     * Put the cursor in the code field and raise the keyboard.
     *
     * <p>There is exactly one thing to do at this point and it needs eight keystrokes, so making the
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
        if (typed.length() != Pairing.CODE_DIGITS) {
            // No state change and no transition: the error line is reserved in the layout
            // (errorEnabled), so this writes a message into a row that is already there.
            codeLayout.setError(a.getString(R.string.pair_code_length));
            return;
        }
        // The button's space is held, the row takes the sheet's attention: one height, either way.
        state(HIDE, SHOW, HIDE, SHOW, KEEP);
        // After state(), like every other text on this sheet — beginDelayedTransition has captured
        // the start scene by now, so whatever this does to the height is animated rather than
        // snapped. It should do nothing to it, the line being reserved, but the order is the rule.
        codeLayout.setError(null);
        // Two labels for what used to be one, because the wait has two halves and no socket exists
        // during the first: stretching the eight digits into a channel key (Pairing.SCRYPT_N), and
        // only afterwards the network. Showing "Connecting" across all of it described something
        // that had not started yet, over the exact stretch of time in which a silent sheet looks
        // like a hung one — right after the user has finished typing and is watching for a reaction.
        //
        // THIS STATE IS BRIEF, AND IT STAYS. The derivation was four seconds of pure-Java PBKDF2
        // when this label was added; native scrypt has taken it to something under one (see
        // Pairing.SCRYPT_P — under one is an estimate, and p was chosen so that the pessimistic end
        // of it is exactly one second). So on a decent phone the user sees "Checking the code"
        // briefly before it becomes "Connecting". That is not a reason to delete it. It is the
        // honest label for a slow or loaded device, where that second is two, and the sheet would
        // otherwise be claiming to be on the network while it is not.
        //
        // The flicker is free, and that is the part that had to be checked rather than assumed: the
        // three joiner states that share this screen — askCode, this one, and the error branch —
        // pass SHOW or KEEP for both the progress row and the button and HIDE/SHOW identically for
        // everything else, so all three measure the same height (see KEEP). Swapping between them
        // moves no edge, at any speed. A fast changeover is a label changing inside a row that was
        // already reserved, which is the one kind of change this sheet does not have to animate.
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
                    state(HIDE, KEEP, HIDE, SHOW, SHOW);
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
        action.setText(labelRes);
        Haptics.onClick(action, () -> onClick.onClick(action));
    }

    /** On screen. */
    private static final int SHOW = View.VISIBLE;
    /** Off screen, and its space with it: the sheet is shorter by exactly this view. */
    private static final int HIDE = View.GONE;
    /**
     * Off screen, but still measured — a placeholder.
     *
     * <p>For the pieces that take turns. Three of the joiner's states are one screen with one thing
     * different about it: the field with a button under it, the field with the progress row while
     * the code is checked, the field with a button and a complaint. The row and the button swap, and
     * a swap in which both sides are GONE moves the sheet's top edge twice within a second — up as
     * the button goes, down as the row arrives — which is the jump this exists to stop. Whichever of
     * the two is not wanted is KEEP, so the screen has one height from the moment it opens until it
     * leaves, and the derivation being seconds long or milliseconds long stops mattering: nothing
     * the clock does can move the sheet.
     *
     * <p>That last clause is load-bearing and was re-checked when the derivation went from four
     * seconds of PBKDF2 to under a second of scrypt. It still holds, and it holds for the
     * reason it was built to: the three states pass {@code (HIDE, KEEP, HIDE, SHOW, SHOW)},
     * {@code (HIDE, SHOW, HIDE, SHOW, KEEP)} and {@code (HIDE, KEEP, HIDE, SHOW, SHOW)}, so the
     * progress row and the button are each measured in all three and every other piece agrees.
     * "Checking the code" now often lasts a few frames, and a few frames is exactly as harmless as
     * four seconds was.
     *
     * <p>Not a spacer view and not a padded blank string: an INVISIBLE view is skipped by TalkBack
     * and cannot be focused or tapped, so the reservation costs the user nothing to walk past.
     */
    private static final int KEEP = View.INVISIBLE;

    /**
     * The five pieces that come and go, set in one call so the sheet can animate between states.
     *
     * <p>One call rather than five {@code setVisibility}s, because a transition has to be started
     * <b>before</b> anything changes and exactly once: toggling views one at a time either starts
     * five overlapping transitions or, worse, starts one and then changes things it has already
     * captured. Collecting the differences first also means a state that changes nothing animates
     * nothing, which is what keeps the once-a-second countdown from re-running the motion.
     *
     * <p>Each piece is {@link #SHOW}, {@link #HIDE} or {@link #KEEP} — the third being the one that
     * holds the sheet's height still where two pieces trade places.
     *
     * <p>Call this first in a state, then set the text. {@code beginDelayedTransition} captures the
     * start values as it is called, so text set beforehand is text the transition thinks was always
     * there — and the height change it causes would snap while everything around it slid.
     */
    private void state(int codeShown, int progressShown, int listShown,
                       int fieldShown, int actionShown) {
        View[] views = {code, progress, list, codeLayout, action};
        int[] want = {codeShown, progressShown, listShown, fieldShown, actionShown};
        java.util.List<View> changing = new java.util.ArrayList<>();
        for (int i = 0; i < views.length; i++) {
            // Three values rather than two, so this compares the value and not "is it VISIBLE": a
            // view going VISIBLE -> KEEP is a fade like any other (MaterialFade, like every
            // androidx Visibility transition, counts INVISIBLE as gone), and one going KEEP -> KEEP
            // has not changed and must not restart the motion.
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
     * Make a key, then hand it out — which is what "this is my first device" means: there is nothing
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
