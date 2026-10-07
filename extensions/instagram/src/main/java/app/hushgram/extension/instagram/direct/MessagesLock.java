/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Lock your messages" patch.
 *
 * <p>While the switch is on, your inbox and every chat stay under a cover until the phone's own
 * lock (fingerprint, face, PIN, pattern or password) says it's you. Once it does, they stay open
 * until Instagram leaves the screen, then lock again, at once or after the time Lock again sets.
 * While they're locked, a message notification says only that a message came, and Instagram's
 * banner for a new message inside the app waits. Lock all of Instagram, the second switch, covers
 * every screen the same way instead of just the messages.
 *
 * <p>The inbox and a chat are found by the view ids Instagram gives them when it builds them, on
 * each frame of the activity in front, and the cover is drawn over just that part of the screen,
 * so the tabs and the top bar still work. Nothing here is Instagram's own state: the lock lives in
 * this class, and a restart starts it locked.
 *
 * <p>Off, HushGram paused, the settings not read yet or anything thrown, Instagram shows everything
 * as it always does. A phone with no screen lock has nothing to ask, so the messages stay open and a
 * toast says why.
 */
public final class MessagesLock {
    /** The inbox's list of chats, and the frame around it, as Instagram names them. */
    static final String INBOX_LIST = "inbox_refreshable_thread_list_recyclerview";
    static final String INBOX_FRAME = "list_container";
    /** A chat's whole screen: its header, the messages and the composer. */
    static final String CHAT_ROOT = "thread_view_root";
    /** Every screen's content, for Lock all of Instagram. */
    static final String APP = "app";
    /** Marks the cover this class puts in a window. */
    static final String COVER_TAG = "hushgram_messages_lock";

    /** Steps a failure is reported under. */
    static final String SWITCH = "switch read";
    static final String NOTIFICATION = "notification";
    static final String BANNER = "message banner";
    static final String SCREEN = "screen check";
    static final String ASK = "phone lock";

    /** Counted outcomes a report needs. */
    static final String HIDDEN = "notification text hidden";
    static final String HELD = "banner held";
    static final String COVERED = "covered";
    static final String NO_PHONE_LOCK = "no screen lock on the phone";

    /** An ask that never answered (an activity gone mid-prompt) stops blocking a new one after this. */
    private static final long ASK_TIMEOUT_MS = 60_000;

    /** Asks the phone's lock, then runs one of the two. Tests put a fake in. */
    interface Asker {
        void ask(Activity activity, Runnable confirmed, Runnable notConfirmed);
    }

    static volatile Asker asker = MessagesLock::askPhone;
    /** View ids by name, for tests whose app has none of Instagram's. */
    static volatile Map<String, Integer> idsForTests;

    private static volatile boolean open;
    /** When Instagram last left the screen while open and Lock again said to wait; 0 when it isn't away. */
    private static volatile long leftAt;
    private static volatile boolean watching;
    private static int started;
    private static long askedAt;
    private static boolean askedThisTime;
    /** What the activity in front was last told about the recent apps picture, so it's told only on a change. */
    private static Boolean keptOutOfRecents;
    private static WeakReference<Activity> watched = new WeakReference<>(null);
    private static ViewTreeObserver.OnPreDrawListener drawListener;
    private static final Map<String, WeakReference<View>> covers = new HashMap<>();
    private static final Map<String, WeakReference<View>> anchors = new HashMap<>();

    private MessagesLock() {
    }

    /**
     * Called once Instagram's application has started, when this patch is in the build. Watches
     * every activity: the one in front is checked before each frame, and leaving Instagram locks
     * the messages again.
     */
    public static void watch(Context context) {
        try {
            if (watching || !(context instanceof Application)) return;
            watching = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Lifecycle());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SCREEN, t);
        }
    }

    /**
     * Asked by Instagram's notification poster with each notification before it goes to Android.
     * While the messages are locked, a message's notification comes back as a copy that says only
     * that a message came. Everything else, and everything while unlocked, goes as it is.
     */
    public static Notification notification(Notification notification) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (notification == null || !locked() || !isMessage(notification)) return notification;
            Notification hidden = hide(Utils.getContext(), notification);
            HookStatus.counted(FamilyNames.MESSAGES_LOCK, HIDDEN);
            return hidden;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, NOTIFICATION, t);
            return notification;
        }
    }

    /**
     * Asked first when Instagram goes to show its banner for something new while you're in the app.
     * True skips that banner while the messages are locked, since it shows the message.
     */
    public static boolean holdBanner() {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (!locked()) return false;
            HookStatus.counted(FamilyNames.MESSAGES_LOCK, HELD);
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, BANNER, t);
            return false;
        }
    }

    /** True while a lock is on and the phone's lock hasn't been confirmed since Instagram came back. */
    public static boolean locked() {
        if (!switchedOn()) return false;
        expire();
        return !open;
    }

    /**
     * A lock was just turned on while nothing was locked. It waits until you leave Instagram rather
     * than covering the screen you're on, since you're the one who turned it on.
     */
    public static void openUntilLeft() {
        if (!locked()) open = true;
    }

    /**
     * Runs [then] at once while the messages are open or the switch is off, else after the phone's
     * lock confirms it's you. Turning the switch off goes through here, so it can't be used to get
     * around the lock.
     */
    public static void confirmThen(Activity activity, Runnable then) {
        if (!locked()) {
            then.run();
            return;
        }
        ask(activity, then);
    }

    private static boolean switchedOn() {
        try {
            return Utils.settingsReady() && (Settings.LOCK_MESSAGES.get() || Settings.LOCK_APP.get());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SWITCH, t);
            return false;
        }
    }

    /** Lock all of Instagram is on: every screen gets the cover, not just the messages. */
    private static boolean wholeApp() {
        try {
            return Utils.settingsReady() && Settings.LOCK_APP.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SWITCH, t);
            return false;
        }
    }

    /** How long Instagram may be away before it locks, in milliseconds. */
    private static long lockDelay() {
        try {
            return Settings.LOCK_AGAIN.get().millis;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SWITCH, t);
            return 0;
        }
    }

    // ---------------------------------------------------------------- notifications

    /**
     * A message, by what Instagram marks it with: the message category, a conversation's messages,
     * or one of Instagram's message channels. A call is left alone, since its screen needs the
     * caller and the buttons.
     */
    static boolean isMessage(Notification notification) {
        if (Notification.CATEGORY_CALL.equals(notification.category)) return false;
        if (Notification.CATEGORY_MESSAGE.equals(notification.category)) return true;
        Bundle extras = notification.extras;
        if (extras != null && extras.containsKey(Notification.EXTRA_MESSAGES)) return true;
        String channel = notification.getChannelId();
        return channel != null && channel.startsWith("ig_direct") && !channel.contains("video_chat");
    }

    /**
     * A copy that keeps how the notification behaves (where a tap goes, its group, its channel,
     * when it came) and drops what it says: the sender, the text, the picture, the reply and other
     * buttons, and the conversation it belongs to, which Android would show with the sender's name.
     */
    static Notification hide(Context context, Notification original) {
        Notification.Builder builder = new Notification.Builder(context, original.getChannelId());
        builder.setSmallIcon(original.getSmallIcon())
                .setContentTitle(appName(context))
                .setContentText(L10n.t("New message"))
                .setContentIntent(original.contentIntent)
                .setDeleteIntent(original.deleteIntent)
                .setWhen(original.when)
                .setShowWhen(true)
                .setAutoCancel((original.flags & Notification.FLAG_AUTO_CANCEL) != 0)
                .setOnlyAlertOnce((original.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0)
                .setGroup(original.getGroup())
                .setGroupSummary((original.flags & Notification.FLAG_GROUP_SUMMARY) != 0)
                .setSortKey(original.getSortKey())
                .setCategory(original.category)
                .setColor(original.color)
                .setNumber(original.number)
                .setVisibility(Notification.VISIBILITY_PRIVATE);
        builder.setTimeoutAfter(original.getTimeoutAfter())
                .setGroupAlertBehavior(original.getGroupAlertBehavior())
                .setBadgeIconType(original.getBadgeIconType());
        return builder.build();
    }

    private static String appName(Context context) {
        CharSequence label = context.getApplicationInfo().loadLabel(context.getPackageManager());
        return label == null ? "Instagram" : label.toString();
    }

    // ---------------------------------------------------------------- the cover

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityStarted(Activity activity) {
            if (started++ <= 0) {
                // Back before Lock again's time ran out: still open, and the next leave starts over.
                expire();
                leftAt = 0;
            }
        }

        @Override public void onActivityStopped(Activity activity) {
            started--;
            // A rotation stops and starts the activity again; only leaving Instagram locks. The
            // phone's own lock screen check on Android 9 stops Instagram too, and coming back from
            // it mustn't ask again.
            if (started <= 0 && !activity.isChangingConfigurations()) left();
        }

        @Override public void onActivityResumed(Activity activity) {
            follow(activity);
        }

        @Override public void onActivityPaused(Activity activity) {
            if (watched.get() == activity) unfollow();
        }

        @Override public void onActivityCreated(Activity activity, Bundle state) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onActivityDestroyed(Activity activity) { }
    }

    /** Instagram left the screen: locked now, or once it's been away as long as Lock again says. */
    static void left() {
        if (open && lockDelay() > 0) {
            leftAt = SystemClock.elapsedRealtime();
            return;
        }
        leftAt = 0;
        relock(askedAt == 0);
    }

    /** Away longer than Lock again allows: locked, even before Instagram comes back. */
    private static void expire() {
        long since = leftAt;
        if (!open || since == 0 || SystemClock.elapsedRealtime() - since < lockDelay()) return;
        leftAt = 0;
        relock(askedAt == 0);
    }

    /** The next look at the messages asks again. */
    static void relock(boolean askAgain) {
        open = false;
        if (askAgain) askedThisTime = false;
    }

    /** Checks the activity in front before each of its frames, so a cover is in place before the messages draw. */
    static void follow(Activity activity) {
        unfollow();
        try {
            watched = new WeakReference<>(activity);
            keptOutOfRecents = null;
            ViewTreeObserver.OnPreDrawListener listener = () -> {
                check(activity);
                return true;
            };
            activity.getWindow().getDecorView().getViewTreeObserver().addOnPreDrawListener(listener);
            drawListener = listener;
            check(activity);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SCREEN, t);
        }
    }

    private static void unfollow() {
        Activity activity = watched.get();
        ViewTreeObserver.OnPreDrawListener listener = drawListener;
        drawListener = null;
        watched = new WeakReference<>(null);
        if (activity == null || listener == null) return;
        try {
            ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(listener);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SCREEN, t);
        }
    }

    /**
     * Puts a cover over the inbox and over a chat wherever either is on screen while the messages
     * are locked, or over the whole screen with Lock all of Instagram, and takes them away otherwise.
     * The first time one shows after Instagram comes back, the phone's lock is asked once; a cancel
     * leaves the cover with its Unlock button.
     */
    static void check(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            boolean lock = locked();
            boolean whole = wholeApp();
            // One cover at a time: two would each keep pulling itself in front of the other.
            boolean app = (whole || cover(APP) != null) && place(activity, decor, APP, lock && whole);
            boolean inbox = place(activity, decor, INBOX_LIST, lock && !whole);
            boolean chat = place(activity, decor, CHAT_ROOT, lock && !whole);
            boolean guarded = whole ? app : inbox || chat;
            keepOutOfRecents(activity, !lock && switchedOn() && guarded);
            if (!lock || !guarded) {
                // Asked again the next time the messages show, unless a prompt is still up.
                if (askedAt == 0) askedThisTime = false;
                return;
            }
            if (!askedThisTime) {
                askedThisTime = true;
                HookStatus.counted(FamilyNames.MESSAGES_LOCK, COVERED);
                ask(activity, null);
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SCREEN, t);
        }
    }

    /**
     * Shows the cover for [name]'s screen over the part of the window it takes up, or hides it.
     * True when that screen is on screen at all.
     */
    private static boolean place(Activity activity, View decor, String name, boolean lock) {
        View anchor = anchor(activity, decor, name);
        View cover = cover(name);
        Rect area = new Rect();
        boolean shown = anchor != null && anchor.isShown() && anchor.getGlobalVisibleRect(area) && !area.isEmpty();
        if (!shown || !lock) {
            if (cover != null && cover.getVisibility() != View.GONE) cover.setVisibility(View.GONE);
            return shown;
        }
        ViewGroup window = (ViewGroup) decor;
        boolean moved = false;
        if (cover == null || cover.getParent() != decor) {
            if (cover != null && cover.getParent() instanceof ViewGroup) ((ViewGroup) cover.getParent()).removeView(cover);
            cover = buildCover(activity, APP.equals(name));
            covers.put(name, new WeakReference<>(cover));
            window.addView(cover, new FrameLayout.LayoutParams(area.width(), area.height(), Gravity.TOP | Gravity.START));
            moved = true;
        }
        if (cover.getVisibility() != View.VISIBLE) {
            cover.setVisibility(View.VISIBLE);
            moved = true;
        }
        // The cover stays the window's last child, so it's drawn over anything Instagram adds later.
        if (window.getChildAt(window.getChildCount() - 1) != cover) cover.bringToFront();
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) cover.getLayoutParams();
        if (params.width != area.width() || params.height != area.height()
                || params.leftMargin != area.left || params.topMargin != area.top) {
            params.width = area.width();
            params.height = area.height();
            params.leftMargin = area.left;
            params.topMargin = area.top;
            cover.setLayoutParams(params);
            moved = true;
        }
        if (moved || cover.getLeft() != area.left || cover.getTop() != area.top) {
            // Laid out now, so the frame about to be drawn has the cover where the messages are.
            cover.measure(View.MeasureSpec.makeMeasureSpec(area.width(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(area.height(), View.MeasureSpec.EXACTLY));
            cover.layout(area.left, area.top, area.right, area.bottom);
        }
        return true;
    }

    private static View cover(String name) {
        WeakReference<View> reference = covers.get(name);
        return reference == null ? null : reference.get();
    }

    /** The screen's view, the inbox's frame around its list when there is one, kept until it leaves the window. */
    private static View anchor(Activity activity, View decor, String name) {
        WeakReference<View> kept = anchors.get(name);
        View view = kept == null ? null : kept.get();
        if (view != null && view.isAttachedToWindow() && view.getRootView() == decor) return view;
        int id = id(activity, name);
        if (id == 0) {
            HookStatus.missingViewId(FamilyNames.MESSAGES_LOCK, name);
            return null;
        }
        view = decor.findViewById(id);
        if (view != null && INBOX_LIST.equals(name)) view = frameAround(activity, view);
        if (view == null) anchors.remove(name);
        else anchors.put(name, new WeakReference<>(view));
        return view;
    }

    /** The inbox's frame when it holds the list, so its edges are covered too; the list otherwise. */
    private static View frameAround(Activity activity, View list) {
        int frame = id(activity, INBOX_FRAME);
        if (frame == 0) return list;
        for (ViewParent parent = list.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (((View) parent).getId() == frame) return (View) parent;
        }
        return list;
    }

    static int id(Context context, String name) {
        if (APP.equals(name)) return android.R.id.content;
        Map<String, Integer> forTests = idsForTests;
        if (forTests != null) {
            Integer id = forTests.get(name);
            return id == null ? 0 : id;
        }
        return context.getResources().getIdentifier(name, "id", context.getPackageName());
    }

    private static View buildCover(Activity activity, boolean wholeApp) {
        boolean dark = Utils.isDarkModeEnabled();
        int text = dark ? Color.WHITE : Color.BLACK;
        FrameLayout cover = new FrameLayout(activity);
        cover.setTag(COVER_TAG);
        cover.setBackgroundColor(dark ? Color.BLACK : Color.WHITE);
        // Takes every touch, so nothing under it can be scrolled or opened.
        cover.setClickable(true);
        cover.setFocusable(true);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        cover.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        ImageView icon = new ImageView(activity);
        icon.setImageResource(android.R.drawable.ic_lock_lock);
        icon.setColorFilter(text);
        int size = dp(activity, 48);
        column.addView(icon, new LinearLayout.LayoutParams(size, size));

        TextView title = new TextView(activity);
        title.setText(wholeApp ? L10n.t("Instagram is locked") : L10n.t("Your messages are locked"));
        title.setTextColor(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(activity, 12), 0, dp(activity, 12));
        column.addView(title);

        TextView unlock = new TextView(activity);
        unlock.setText(L10n.t("Unlock"));
        unlock.setTextColor(Color.rgb(0, 149, 246));
        unlock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        unlock.setGravity(Gravity.CENTER);
        unlock.setPadding(dp(activity, 24), dp(activity, 12), dp(activity, 24), dp(activity, 12));
        unlock.setOnClickListener(v -> ask(activity, null));
        column.addView(unlock);
        return cover;
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    /** Android 13 and up: an open inbox or chat stays out of the recent apps picture. */
    private static void keepOutOfRecents(Activity activity, boolean keepOut) {
        if (Build.VERSION.SDK_INT >= 33 && !Boolean.valueOf(keepOut).equals(keptOutOfRecents)) {
            keptOutOfRecents = keepOut;
            activity.setRecentsScreenshotEnabled(!keepOut);
        }
    }

    // ---------------------------------------------------------------- asking the phone's lock

    private static void ask(Activity activity, Runnable then) {
        long now = SystemClock.elapsedRealtime();
        if (askedAt != 0 && now - askedAt < ASK_TIMEOUT_MS) return;
        askedAt = now;
        try {
            asker.ask(activity, () -> {
                askedAt = 0;
                open = true;
                if (then != null) then.run();
            }, () -> askedAt = 0);
        } catch (Throwable t) {
            askedAt = 0;
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, ASK, t);
        }
    }

    /**
     * Android 10 and up ask in Instagram's own window, with the fingerprint or face and the PIN,
     * pattern or password as the fallback. Android 9 can't offer that fallback there, so it opens
     * the phone's own lock screen check and reads its answer through a small headless fragment.
     */
    private static void askPhone(Activity activity, Runnable confirmed, Runnable notConfirmed) {
        KeyguardManager keyguard = activity.getSystemService(KeyguardManager.class);
        if (keyguard == null || !keyguard.isDeviceSecure()) {
            HookStatus.counted(FamilyNames.MESSAGES_LOCK, NO_PHONE_LOCK);
            Utils.showToastLong(wholeApp()
                    ? L10n.t("Set a screen lock on your phone so HushGram can lock Instagram.")
                    : L10n.t("Set a screen lock on your phone so HushGram can lock your messages."));
            confirmed.run();
            return;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            prompt(activity, confirmed, notConfirmed);
        } else {
            Intent intent = keyguard.createConfirmDeviceCredentialIntent(askTitle(), null);
            if (intent == null) {
                confirmed.run();
                return;
            }
            Answer.start(activity, intent, confirmed, notConfirmed);
        }
    }

    /**
     * Android 10 and up ask over Instagram. Android 10 has only the older way to take the phone's
     * own lock as well, and Android 9 never comes here: it opens the phone's lock screen check.
     */
    @RequiresApi(29)
    @SuppressWarnings("deprecation")
    private static void prompt(Activity activity, Runnable confirmed, Runnable notConfirmed) {
        BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
                .setTitle(askTitle());
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else {
            builder.setDeviceCredentialAllowed(true);
        }
        builder.build().authenticate(new CancellationSignal(), activity.getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        confirmed.run();
                        refresh(activity);
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence message) {
                        notConfirmed.run();
                    }
                });
    }

    private static String askTitle() {
        return wholeApp() ? L10n.t("Unlock Instagram") : L10n.t("Unlock your messages");
    }

    /** Draws the activity again, so the covers come off at once. */
    private static void refresh(Activity activity) {
        try {
            activity.getWindow().getDecorView().invalidate();
        } catch (Throwable t) {
            Logger.printException(() -> "Lock your messages: could not redraw", t);
        }
    }

    /**
     * Android 9's answer from the phone's lock screen check. A platform fragment gets the result
     * of the activity it starts in any activity, Instagram's included.
     */
    public static final class Answer extends Fragment {
        private static final String TAG = "hushgram_messages_lock";
        private static final int REQUEST = 0x4847;
        private static Runnable pendingConfirmed;
        private static Runnable pendingNotConfirmed;
        private Intent intent;

        static void start(Activity activity, Intent intent, Runnable confirmed, Runnable notConfirmed) {
            FragmentManager manager = activity.getFragmentManager();
            Fragment old = manager.findFragmentByTag(TAG);
            if (old != null) manager.beginTransaction().remove(old).commitNowAllowingStateLoss();
            Answer answer = new Answer();
            answer.intent = intent;
            pendingConfirmed = confirmed;
            pendingNotConfirmed = notConfirmed;
            manager.beginTransaction().add(answer, TAG).commitNowAllowingStateLoss();
        }

        @Override
        public void onCreate(Bundle state) {
            super.onCreate(state);
            if (intent != null) startActivityForResult(intent, REQUEST);
            else finish(false);
        }

        @Override
        public void onActivityResult(int request, int result, Intent data) {
            if (request == REQUEST) finish(result == Activity.RESULT_OK);
        }

        private void finish(boolean ok) {
            Runnable run = ok ? pendingConfirmed : pendingNotConfirmed;
            pendingConfirmed = null;
            pendingNotConfirmed = null;
            try {
                getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
            } catch (Throwable t) {
                Logger.printException(() -> "Lock your messages: could not remove the answer", t);
            }
            if (run != null) run.run();
        }
    }

    static Asker realAskerForTests() {
        return MessagesLock::askPhone;
    }

    /** Back to how a fresh start finds it. */
    static void resetForTests() {
        open = false;
        leftAt = 0;
        watching = false;
        started = 0;
        askedAt = 0;
        askedThisTime = false;
        keptOutOfRecents = null;
        unfollow();
        covers.clear();
        anchors.clear();
        asker = MessagesLock::askPhone;
        idsForTests = null;
    }
}
