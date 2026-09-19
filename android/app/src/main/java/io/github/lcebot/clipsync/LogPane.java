package io.github.lcebot.clipsync;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

/**
 * The Log page: one selectable TextView, kept at the tail of a file two processes write to.
 *
 * <p>Three jobs, and they are here together because each one exists to protect the other two. The
 * <b>poll</b> is how lines written by the {@code :sync} process are noticed at all; the
 * <b>append</b> is what keeps a selection, a scroll position and an already-measured layout alive
 * across that poll; and the <b>follow-the-tail</b> rule is what decides whether a new line is
 * allowed to move the viewport. Splitting them would leave three classes each able to break the
 * other two silently.
 *
 * <p>Its owner tells it two things and asks it three. It is told when the Activity is resumed
 * ({@link #onResume}/{@link #onPause}) and whether its page is the one in front
 * ({@link #setPolling}); it is asked for the text to copy, to clear the buffer, and to take the
 * horizontal insets its owner has worked out. Nothing else on the page is visible from in here.
 */
final class LogPane {
    private final NestedScrollView page;
    private final TextView log;
    /**
     * The padding the layout starts with, read before any inset is added to it: the inset listener
     * runs repeatedly (rotation, a cutout coming into play) and has to add to the designed value
     * each time, not to whatever it left behind last time.
     */
    private final int basePad;

    private final Logger.Cursor cursor = new Logger.Cursor();
    private boolean toBottom;                               // jump to the newest line once laid out

    private final Handler ui = new Handler(Looper.getMainLooper());
    // lines written in this process arrive here; lines written by the :sync process arrive through
    // the file, picked up by the poll below
    private final Logger.Listener listener = line -> ui.post(this::showLog);
    /**
     * Reads the log file. One thread, off the main one, because {@link Logger#refresh()} stats and
     * reads a file that another process is appending to — small, but it is disk, and it was being
     * done on the main thread once a second whichever page was in front.
     */
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "clipsync-log-read");
                t.setDaemon(true);
                return t;
            });
    /**
     * How often the log file is checked for lines written by the :sync process.
     *
     * <p>The log is a tail: it grows by appending, there is no "the whole thing changed" event to
     * wait for, and a second's latency on a line of text is not felt. So it is polled — but only
     * while the Log page is actually visible, which is what {@link #setPolling} is for. Sitting
     * on Settings, the old unconditional 1 Hz poll read a file nobody was looking at.
     */
    private static final long POLL_MS = 1_000;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            io.execute(() -> {
                Logger.refresh();
                ui.post(LogPane.this::showLog);
            });
            ui.postDelayed(this, POLL_MS);
        }
    };

    LogPane(NestedScrollView page, TextView log) {
        this.page = page;
        this.log = log;
        this.basePad = log.getPaddingLeft();

        // The log keeps the app bar collapsed by never driving it: with nested scrolling off it
        // still scrolls its own content, but it cannot push the bar back open. That is all the
        // "always collapsed" rule needs — no scroll flags to swap, no height to juggle.
        page.setNestedScrollingEnabled(false);

        // Scrolling to the end has to wait until the new text has actually been laid out, which is
        // what the layout listener is for. scrollTo and not fullScroll: fullScroll moves focus into
        // the (selectable) TextView, and the scroll container then scrolls that view's *top* into
        // view — the original bug.
        //
        // The scroll target is computed here rather than delegated, because this took three tries and
        // each failure was a different way of trusting someone else's arithmetic:
        //
        //   log.getBottom(), unposted — the callback runs DURING layout, so the range was computed
        //       from dimensions that were not final and the scroll landed short;
        //   Integer.MAX_VALUE — meant as "clamp me to the end". It does not: NestedScrollView's
        //       clamp tests (viewport + n) > childHeight, and with n = MAX_VALUE that addition
        //       OVERFLOWS to a negative number, the test is false, and the value is returned
        //       unclamped. Every line ends up scrolled off the top — the blank screen.
        //
        // So: wait for layout (post), work out the maximum with the same formula the clamp uses but
        // without the overflow, and refuse to act at all until there is a viewport to act on. A page
        // switched from GONE has height 0 while its bottom padding does not, which makes that
        // formula negative — the other way to end up scrolled past the end.
        log.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (!toBottom) return;
            page.post(() -> {
                if (!toBottom || page.getVisibility() != View.VISIBLE) return;
                int viewport = page.getHeight() - page.getPaddingTop() - page.getPaddingBottom();
                if (viewport <= 0) return;                  // not laid out yet: keep the flag, try again
                page.scrollTo(0, Math.max(0, log.getHeight() - viewport));
                // Cleared only once it actually reached the end. The flag is re-armed by showLog()
                // solely when the view is already at the bottom, so clearing it after a scroll that
                // fell short used to be permanent — every later line pushed the end further away.
                if (!page.canScrollVertically(1)) toBottom = false;
            });
        });
    }

    /**
     * Start or stop the log poll. Both conditions have to hold: the page in front has to be the Log,
     * and the Activity has to be resumed — the caller knows both and hands in the conjunction.
     *
     * <p>Idempotent by construction — the callback is removed first either way — so every caller can
     * simply restate what it knows rather than tracking whether the poll is already running.
     */
    void setPolling(boolean on) {
        ui.removeCallbacks(tick);
        if (on) ui.post(tick);
    }

    void onResume() {
        follow();
        Logger.addListener(listener);
    }

    void onPause() {
        Logger.removeListener(listener);
        setPolling(false);
    }

    /** Everything this page owns that would otherwise outlive the Activity. */
    void shutdown() {
        setPolling(false);
        Logger.removeListener(listener);
        io.shutdownNow();
    }

    /** Arm the follow-the-tail jump and take whatever is already in the buffer. */
    void follow() {
        toBottom = true;
        showLog();
    }

    /** What the Copy action puts on the clipboard. */
    CharSequence text() {
        return log.getText();
    }

    void clear() {
        Logger.clear();
        showLog();
    }

    /**
     * The horizontal display-cutout insets, added to the padding the layout designed.
     *
     * <p>The pane itself paints a surface and is allowed to run under a cutout; the text inside it is
     * readable content and steps aside. So the inset lands on the TextView's padding, never on the
     * scroll container's.
     */
    void applyInsets(int left, int right) {
        log.setPadding(basePad + left, log.getPaddingTop(), basePad + right, log.getPaddingBottom());
    }

    /**
     * Brings the visible log up to date, appending where it can.
     *
     * <p>Appending is what makes the log usable in a bug report: the text is one TextView so a
     * selection can span any number of lines, and appending leaves that selection, the scroll
     * position and the already-laid-out text alone. Only a rebuild (the buffer was cleared, or has
     * dropped enough old entries to be worth trimming) replaces the text.
     *
     * <p>While anything is selected the log is left frozen — nothing reflows under the reader's
     * fingers, and nothing is lost either, because the cursor is only advanced by a read that
     * actually happened; the next tick catches up.
     *
     * <p>Following the tail is likewise only automatic while the view is already at the bottom, so
     * reading further up is not yanked away by the next line.
     */
    private void showLog() {
        if (log.hasSelection()) return;
        Logger.Tail tail = Logger.read(cursor);
        if (tail.isEmpty()) return;
        // false both at the bottom and while the page is still GONE or unmeasured, which is what
        // we want: a page that has not been shown yet starts at the end
        if (!page.canScrollVertically(1)) toBottom = true;
        String text = String.join("\n", tail.lines);
        if (tail.replace) {
            // EDITABLE from the outset: append() would otherwise convert the buffer on its first
            // call, and that conversion is itself a setText that drops the selection
            log.setText(text, TextView.BufferType.EDITABLE);
        } else if (log.length() == 0) {
            log.append(text);
        } else {
            log.append("\n" + text);
        }
    }
}
