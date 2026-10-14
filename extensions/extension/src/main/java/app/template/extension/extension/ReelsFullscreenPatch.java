package app.template.extension.extension;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Lets the Reels viewer draw behind the (still visible) status bar and down to the bottom
 * edge of the screen, optionally hiding Instagram's tab bar while Reels is on screen.
 *
 * Works purely on the view hierarchy, every change is recorded and undone when Reels is left.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class ReelsFullscreenPatch {
    private static final String TAG = "piko-reels-fullscreen";

    // Resource entry names are not obfuscated. First one that is on screen wins.
    private static final String[] REELS_VIEW_IDS = {
            "clips_viewer_view_pager",
            "clips_swipe_refresh_container",
            "clips_viewer_container",
            "clips_viewer_fragment_container",
    };
    private static final String TAB_BAR_ID = "tab_bar";
    // Navigation rail that replaces the tab bar on large screens.
    private static final String NAVIGATION_RAIL_ID = "ls_vertical_nav_bar_stub";

    private static final int EDGE_TO_EDGE_FLAGS =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
    // Insets are whole pixels, allow for rounding done by Instagram.
    private static final int TOLERANCE_PX = 2;
    // Each reel page is rendered by one of these.
    private static final String LITHO_VIEW_CLASS = "com.facebook.litho.LithoView";
    private static final int MAX_CONTENT_DEPTH = 3;
    private static final float MIN_SCALE = 1.01f;
    // Card that holds the video or photo of a reel.
    private static final String MEDIA_ID = "clips_media_component";
    private static final int MAX_MEDIA_DEPTH = 6;
    // Media counts as letterboxed when it leaves this much of the card unused.
    private static final float LETTERBOX_RATIO = 0.93f;
    private static final float MIN_MEDIA_AREA = 0.25f;
    private static final float MIN_ENLARGE = 1.02f;
    // How far the card is grown past the page to get its border off screen.
    private static final float CARD_OVERSCAN = 1.03f;
    // Progress bar of a video reel.
    private static final String SCRUBBER_ID = "scrubber";
    private static final int SEAM_TOUCH_WIDTH_DP = 28;
    // Column with the like, comment and share buttons.
    private static final String BUTTONS_ID = "clips_ufi_component";
    private static final int MAX_DUMP_DEPTH = 18;
    private static final int MAX_DUMP_LINES = 400;
    private static final long DUMP_DELAY_MS = 2500;
    // Stop adjusting when Instagram keeps putting its spacing back on every layout pass.
    private static final int MAX_CHURN = 50;
    // Frames in a row that may be skipped to keep a frame with Instagram's spacing off screen.
    private static final int MAX_SKIPPED_FRAMES = 3;
    // Passes between two searches for a navigation rail that has not been found.
    private static final int RAIL_SEARCH_INTERVAL = 30;
    private static final int RESTART_BUTTON_SIZE_DP = 48;
    // Holds the screen of the current tab, the tab bar sits next to it.
    private static final String CONTENT_ID = "layout_container_main";
    private static final int MAX_BUTTONS_DEPTH = 6;
    // Buttons of one bar are about the same size.
    private static final float MAX_BUTTON_SIZE_RATIO = 1.6f;
    private static final int MIN_BUTTONS = 3;
    private static final int GLASS_BLUR_DP = 20;
    // With edges that bend the picture the glass can be clearer.
    private static final int GLASS_CLEAR_BLUR_DP = 8;
    private static final int GLASS_EDGE_DP = 11;
    private static final int GLASS_BEND_DP = 14;
    private static final String MESSAGES_LINK = "instagram://direct-inbox";
    // Line Instagram draws along the top of the tab bar.
    private static final String TAB_BAR_LINE_ID = "tab_bar_shadow";
    // How much the button under the finger grows while sliding over the tab bar.
    private static final float HOVER_SCALE = 1.18f;
    // The rim of the navigation rail takes its colors from the screen this often, in small samples.
    private static final long AMBIENT_INTERVAL_MS = 900;
    private static final int AMBIENT_COLUMNS = 8;
    private static final int AMBIENT_ROWS = 40;
    // How far the floating tab bar sits above the bottom of the screen.
    private static final int BAR_RAISE_DP = 14;
    private static final int MAX_LIST_DEPTH = 12;
    private static final int LIST_SEARCH_INTERVAL = 20;
    private static final int RESTART_BUTTON_MARGIN_DP = 12;

    private static final Map<Activity, State> STATES = new WeakHashMap<>();
    private static boolean registered;
    private static boolean hideTabBar;
    private static boolean debug;
    private static boolean expandMedia;
    private static boolean moveButtons;
    private static boolean seamScrubber;
    private static boolean restartButton;
    private static boolean glassBar;
    private static boolean messagesTab;

    private ReelsFullscreenPatch() {
    }

    /** Injection point. Added by the "Hide tab bar in Reels" patch. */
    public static void enableHideTabBar() {
        hideTabBar = true;
    }

    /** Injection point. Added by the "Expand photos in Reels" patch. */
    public static void enableExpandMedia() {
        expandMedia = true;
    }

    /** Injection point. Added by the "Move Reels buttons to the edge" patch. */
    public static void enableMoveButtons() {
        moveButtons = true;
    }

    /** Injection point. Added by the "Reels progress bar on the navigation rail" patch. */
    public static void enableSeamScrubber() {
        seamScrubber = true;
    }

    /** Injection point. Added by the "Restart button on the navigation rail" patch. */
    public static void enableRestartButton() {
        restartButton = true;
    }

    /** Injection point. Added by the "Liquid glass navigation bar" patch. */
    public static void enableGlassBar() {
        glassBar = true;
    }

    /** Injection point. Added by the "Replace create button with messages" patch. */
    public static void enableMessagesTab() {
        messagesTab = true;
    }

    /** Injection point. Added by the "Reels fullscreen debug" patch. */
    public static void enableDebug() {
        debug = true;
    }

    /** Injection point. Called from the application's onCreate. */
    public static void init(Context context) {
        try {
            if (registered) return;
            Context appContext = context.getApplicationContext();
            if (appContext == null) appContext = context;
            if (!(appContext instanceof Application)) return;
            registered = true;

            ((Application) appContext).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityResumed(Activity activity) {
                    attach(activity);
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                    STATES.remove(activity);
                }

                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                }

                @Override
                public void onActivityStarted(Activity activity) {
                }

                @Override
                public void onActivityPaused(Activity activity) {
                }

                @Override
                public void onActivityStopped(Activity activity) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "init failure", e);
        }
    }

    private static void attach(Activity activity) {
        try {
            if (STATES.containsKey(activity)) return;
            Window window = activity.getWindow();
            if (window == null) return;

            State state = new State(activity);
            STATES.put(activity, state);
            ViewTreeObserver observer = window.getDecorView().getViewTreeObserver();
            observer.addOnGlobalLayoutListener(state);
            observer.addOnPreDrawListener(state);
            state.watchTouches();
        } catch (Exception e) {
            Log.e(TAG, "attach failure", e);
        }
    }

    private static final class State implements ViewTreeObserver.OnGlobalLayoutListener,
            ViewTreeObserver.OnPreDrawListener {
        private final Activity activity;
        private final int[] reelsViewIds;
        private final int tabBarId;
        private final int navigationRailId;
        private final int mediaId;
        private final int buttonsId;
        private final int scrubberId;
        private final int contentId;
        private final int tabBarLineId;

        // What Instagram had set before it was changed, to put it back when Reels is left.
        /** Left, top, right and bottom padding. */
        private final Map<View, int[]> originalPadding = new WeakHashMap<>();
        /** Top and bottom margin. */
        private final Map<View, int[]> originalMargins = new WeakHashMap<>();
        private final Map<View, Float> originalTranslation = new WeakHashMap<>();
        private Integer originalStatusBarColor;
        private int addedUiFlags;
        private boolean changed;
        private int churn;
        /** Reel pages that were scaled up to the new Reels height. */
        private final Map<View, Boolean> scaled = new WeakHashMap<>();
        /** Views inside a page that were scaled or moved, with the pass they were last seen in. */
        private final Map<View, Integer> enlarged = new WeakHashMap<>();
        /** Progress bars that were made invisible, with the pass they were last seen in. */
        private final Map<View, Integer> faded = new WeakHashMap<>();
        private SeamBar seamBar;
        private RestartButton restartView;
        private GlassBar tabGlass;
        private MessagesButton messagesView;
        /** Where the touches of the window went before they were watched. */
        private Window.Callback touchCallback;
        private int touchSlop;
        private boolean gesture;
        private boolean gestureRail;
        private boolean gestureDragging;
        private float gestureDown;
        private final int[] hitLocation = new int[2];
        /** View that holds the buttons of the tab bar. */
        private WeakReference<View> lastTabHost = new WeakReference<>(null);
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private Bitmap ambientShot;
        private int[] ambientRows;
        private boolean ambientPending;
        private long ambientTime;
        /** Padding of the navigation rail before it was kept in place under an expanded reel. */
        private int[] railPadding;
        private WeakReference<View> paddedRail = new WeakReference<>(null);
        /** View that holds the buttons of the navigation rail. */
        private WeakReference<View> lastRailHost = new WeakReference<>(null);
        private ViewGroup styledHost;
        private boolean railHovering;
        private WeakReference<View> lastTabBarLine = new WeakReference<>(null);
        private boolean hovering;
        private boolean barRaised;
        /** Button of the tab bar that the messages button stands in for. */
        private WeakReference<View> createSlot = new WeakReference<>(null);
        /** Lists that got room to scroll clear of the floating tab bar, with their own bottom padding. */
        private final Map<View, Integer> listPadding = new WeakHashMap<>();
        private int listSearches;
        private GlassBar railGlass;
        private WeakReference<View> lastTabBar = new WeakReference<>(null);
        private WeakReference<View> lastContent = new WeakReference<>(null);
        private WeakReference<View> floated = new WeakReference<>(null);
        private int glassSearches;
        private boolean blurFailed;
        /** Height of the tab bar while it floats over the screen behind it, otherwise 0. */
        private int floatLift;
        private WeakReference<View> lastRail = new WeakReference<>(null);
        private int railSearches;
        private boolean layoutChanged;
        private int skippedFrames;
        private boolean railShown;
        /** Whether Instagram shows its layout for large screens, with the navigation rail. */
        private boolean wideLayout;
        private int pass;

        private WeakReference<View> lastReels = new WeakReference<>(null);
        private WeakReference<View> dumpedPage = new WeakReference<>(null);
        private int dumpedWidth;
        private int dumpedHeight;
        private Runnable pendingDump;
        private boolean applying;
        private boolean active;
        private boolean tabBarHidden;
        private int tabBarHeight;

        State(Activity activity) {
            this.activity = activity;
            Resources resources = activity.getResources();
            String packageName = activity.getPackageName();

            reelsViewIds = new int[REELS_VIEW_IDS.length];
            for (int i = 0; i < REELS_VIEW_IDS.length; i++) {
                reelsViewIds[i] = resources.getIdentifier(REELS_VIEW_IDS[i], "id", packageName);
            }
            tabBarId = resources.getIdentifier(TAB_BAR_ID, "id", packageName);
            navigationRailId = resources.getIdentifier(NAVIGATION_RAIL_ID, "id", packageName);
            mediaId = resources.getIdentifier(MEDIA_ID, "id", packageName);
            buttonsId = resources.getIdentifier(BUTTONS_ID, "id", packageName);
            scrubberId = resources.getIdentifier(SCRUBBER_ID, "id", packageName);
            contentId = resources.getIdentifier(CONTENT_ID, "id", packageName);
            tabBarLineId = resources.getIdentifier(TAB_BAR_LINE_ID, "id", packageName);
        }

        @Override
        public boolean onPreDraw() {
            // Reel pages fill in their content without a layout pass, and Instagram puts its
            // spacing back at times, so one layout callback is not enough to catch everything.
            onGlobalLayout();

            // Instagram puts its status bar spacing back when comments close, among others.
            // Taking it out again needs a new layout, drawing now would show one frame of the
            // whole screen shifted down. That frame is skipped instead.
            boolean skip = layoutChanged && skippedFrames < MAX_SKIPPED_FRAMES;
            layoutChanged = false;
            skippedFrames = skip ? skippedFrames + 1 : 0;
            return !skip;
        }

        @Override
        public void onGlobalLayout() {
            // Changes made here schedule another layout pass.
            if (applying) return;
            applying = true;
            try {
                update();
            } catch (Exception e) {
                Log.e(TAG, "update failure", e);
            } finally {
                applying = false;
            }
        }

        private void update() {
            View decor = activity.getWindow().getDecorView();
            if (restartButton) updateRestartButton(decor);
            if (messagesTab) updateMessagesTab(decor);
            if (glassBar) updateGlass(decor);
            View reels = findReelsView(decor);

            if (reels == null) {
                if (active) restore(decor);
                return;
            }

            WindowInsets insets = decor.getRootWindowInsets();
            if (insets == null) return;
            int top = insets.getStableInsetTop();
            int bottom = insets.getStableInsetBottom();

            active = true;
            // Folding, unfolding and rotating make Instagram apply its spacing again,
            // so everything is checked on every pass instead of once.
            drawBehindStatusBar(decor);
            if (hideTabBar) {
                View tabBar = tabBarId == 0 ? null : decor.findViewById(tabBarId);
                if (tabBar != null && tabBar.getVisibility() == View.VISIBLE && tabBar.getHeight() > 0) {
                    tabBarHeight = tabBar.getHeight();
                }
                if (tabBarHidden && navigationRailShown(decor)) tabBarHidden = false;
                hideTabBar(tabBar);
            }
            if (churn <= MAX_CHURN) {
                changed = false;
                expand(reels, decor, top, bottom);
                churn = changed ? churn + 1 : 0;
                if (changed) layoutChanged = true;
            }
            wideLayout = navigationRailShown(decor);
            railShown = seamScrubber && wideLayout;
            fill(reels);
            if (seamScrubber) updateSeamBar(reels, decor, top, bottom);
            if (debug) watchPage(reels);
        }

        private View findReelsView(View decor) {
            // This runs for every frame, skip the lookup while the same view is still on screen.
            View last = lastReels.get();
            if (last != null && last.isAttachedToWindow() && last.isShown() && last.getWidth() > 0) {
                return last;
            }
            for (int id : reelsViewIds) {
                if (id == 0) continue;
                View view = decor.findViewById(id);
                if (view != null && view.isShown() && view.getWidth() > 0) {
                    lastReels = new WeakReference<>(view);
                    return view;
                }
            }
            return null;
        }

        private View findNavigationRail(View decor) {
            View rail = lastRail.get();
            if (rail != null && rail.isAttachedToWindow()) return rail;
            // This runs for every frame on every screen, do not search the whole window each time.
            if (navigationRailId == 0 || railSearches++ % RAIL_SEARCH_INTERVAL != 0) return null;
            rail = decor.findViewById(navigationRailId);
            lastRail = new WeakReference<>(rail);
            return rail;
        }

        private boolean navigationRailShown(View decor) {
            View rail = findNavigationRail(decor);
            return rail != null && rail.isShown() && rail.getWidth() > 0;
        }

        private View lookup(View decor, int id, WeakReference<View> last) {
            View view = last.get();
            if (view != null && view.isAttachedToWindow()) return view;
            if (id == 0 || glassSearches++ % RAIL_SEARCH_INTERVAL > 1) return null;
            return decor.findViewById(id);
        }

        /**
         * Draws the tab bar and the navigation rail as one rounded glass pill each.
         * The tab bar also floats over the screen behind it, the rail stays next to it.
         */
        private void updateGlass(View decor) {
            floatLift = 0;
            try {
                View tabBar = findTabBar(decor);
                View content = lookup(decor, contentId, lastContent);
                if (content != null && content != lastContent.get()) lastContent = new WeakReference<>(content);
                if (tabBar != null && tabBar.isShown() && tabBar.getWidth() > 0 && tabBar.getHeight() > 0) {
                    if (tabGlass == null) tabGlass = new GlassBar(activity);
                    floatContent(content, tabBar, decor);
                    styleBar(tabGlass, tabBar, tabGlass.floating ? content : null, false);
                    View tabHost = styledHost != null ? styledHost : tabBar;
                    if (tabHost != lastTabHost.get()) lastTabHost = new WeakReference<>(tabHost);
                    if (tabGlass.floating) {
                        int raise = Math.round(BAR_RAISE_DP * activity.getResources().getDisplayMetrics().density);
                        tabBar.setTranslationY(-raise);
                        barRaised = true;
                        floatLift = tabBar.getHeight() + raise;
                        insetLists(content, tabBar, floatLift);
                    } else {
                        if (barRaised) tabBar.setTranslationY(0f);
                        barRaised = false;
                        restoreLists();
                    }

                    View line = lookup(decor, tabBarLineId, lastTabBarLine);
                    if (line != null) {
                        if (line != lastTabBarLine.get()) lastTabBarLine = new WeakReference<>(line);
                        line.setAlpha(0f);
                    }
                    hover(tabGlass, tabHost, false);
                } else {
                    restoreLists();
                }

                // Nothing runs underneath the rail, its glass shows the colors of the screen next to it.
                View rail = findNavigationRail(decor);
                if (rail != null && rail.isShown() && rail.getWidth() > 0) {
                    if (railGlass == null) railGlass = new GlassBar(activity);
                    railGlass.floating = true;
                    railGlass.ambient = true;
                    styleBar(railGlass, rail, content, true);
                    if (styledHost != null) {
                        if (styledHost != lastRailHost.get()) lastRailHost = new WeakReference<>(styledHost);
                        captureAmbient(railGlass, styledHost, rail, decor);
                        hover(railGlass, styledHost, true);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "glass failure", e);
            }
        }

        /** Lets the screen of the current tab run underneath the tab bar. */
        private void floatContent(View content, View tabBar, View decor) {
            tabGlass.floating = false;
            if (content == null || !(content.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) content.getLayoutParams();

            WindowInsets insets = decor.getRootWindowInsets();
            int bottom = insets == null ? 0 : insets.getStableInsetBottom();
            int reserved = params.bottomMargin;
            if (reserved > 0 && (Math.abs(reserved - tabBar.getHeight()) <= TOLERANCE_PX
                    || Math.abs(reserved - tabBar.getHeight() - bottom) <= TOLERANCE_PX)) {
                params.bottomMargin = 0;
                content.setLayoutParams(params);
                floated = new WeakReference<>(content);
                layoutChanged = true;
            }
            tabGlass.floating = floated.get() == content && params.bottomMargin == 0;
        }

        /** View that the glass of a bar is drawn on and its touches are measured in. */
        private View barHost(boolean rail) {
            return rail ? lastRailHost.get() : lastTabHost.get();
        }

        private GlassBar barGlass(boolean rail) {
            return rail ? railGlass : tabGlass;
        }

        /** Button of a bar at a position along it. */
        private View buttonAt(View host, float position, boolean vertical) {
            ViewGroup buttons = findButtons(host);
            if (buttons == null) return null;
            float offset = 0;
            for (View view = buttons; view != host; view = (View) view.getParent()) {
                offset += vertical ? view.getTop() : view.getLeft();
            }
            for (int i = 0; i < buttons.getChildCount(); i++) {
                View child = buttons.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                float start = offset + (vertical ? child.getTop() : child.getLeft());
                float end = offset + (vertical ? child.getBottom() : child.getRight());
                if (position >= start && position < end) return child;
            }
            return null;
        }

        private boolean isCreateButton(View button) {
            return messagesTab && button != null && createSlot.get() == button;
        }

        /** The button under the lens grows, as seen through it, and eases back when the lens leaves. */
        private void hover(GlassBar glass, View host, boolean rail) {
            boolean active = glass != null && glass.isTouched();
            boolean was = rail ? railHovering : hovering;
            if (!active && !was) return;

            ViewGroup buttons = findButtons(host);
            if (buttons == null) return;
            View under = active ? buttonAt(host, glass.position(), rail) : null;
            boolean settled = true;
            for (int i = 0; i < buttons.getChildCount(); i++) {
                View child = buttons.getChildAt(i);
                settled &= easeScale(child, child == under ? HOVER_SCALE : 1f);
            }
            if (!rail && messagesView != null) {
                settled &= easeScale(messagesView, isCreateButton(under) ? HOVER_SCALE : 1f);
            }
            boolean busy = active || !settled;
            if (rail) railHovering = busy;
            else hovering = busy;
            // Keep the frames coming until every button is back to its size.
            if (!settled) host.invalidate();
        }

        private boolean easeScale(View view, float goal) {
            float scale = view.getScaleX();
            float next = scale + (goal - scale) * 0.24f;
            boolean settled = Math.abs(goal - next) < 0.004f;
            if (settled) next = goal;
            if (next != scale) {
                view.setScaleX(next);
                view.setScaleY(next);
            }
            return settled;
        }

        /** Middle of a button along its bar, in the coordinates of the bar. */
        private float buttonCenter(View host, View button, boolean vertical) {
            float center = (vertical ? button.getHeight() : button.getWidth()) / 2f;
            for (View view = button; view != host; view = (View) view.getParent()) {
                center += vertical ? view.getTop() : view.getLeft();
            }
            return center;
        }

        /** A finger went down on a bar: the lens rises and goes to the button under it. */
        private void barPress(boolean rail, float position) {
            View host = barHost(rail);
            GlassBar glass = barGlass(rail);
            View button = host == null || glass == null ? null : buttonAt(host, position, rail);
            if (button != null) glass.press(buttonCenter(host, button, rail));
        }

        private void barUnpress(boolean rail) {
            GlassBar glass = barGlass(rail);
            if (glass == null) return;
            glass.unpress();
        }

        private void barDrag(boolean rail, float position) {
            GlassBar glass = barGlass(rail);
            if (glass != null) glass.drag(position);
        }

        private void barRelease(boolean rail, float position, boolean select) {
            GlassBar glass = barGlass(rail);
            View host = barHost(rail);
            if (glass != null) {
                // Let go between two buttons, the lens snaps to the one it is mostly over.
                View button = host == null ? null : buttonAt(host, position, rail);
                glass.release(button == null ? position : buttonCenter(host, button, rail));
            }
            if (select) barTap(rail, position);
        }

        /** Presses the button a slide ended on the way a finger would: with a touch on the window. */
        private void barTap(boolean rail, float position) {
            final View host = barHost(rail);
            View button = host == null ? null : buttonAt(host, position, rail);
            if (button == null || touchCallback == null) return;
            if (isCreateButton(button)) {
                openMessages();
                return;
            }
            int[] location = new int[2];
            button.getLocationInWindow(location);
            final float x = location[0] + button.getWidth() / 2f;
            final float y = location[1] + button.getHeight() / 2f;
            // Once the touch that is being handled right now is over.
            host.post(() -> {
                long time = SystemClock.uptimeMillis();
                windowTouch(time, time, MotionEvent.ACTION_DOWN, x, y);
                windowTouch(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y);
            });
        }

        private void windowTouch(long downTime, long time, int action, float x, float y) {
            MotionEvent event = MotionEvent.obtain(downTime, time, action, x, y, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try {
                touchCallback.dispatchTouchEvent(event);
            } catch (Exception e) {
                Log.e(TAG, "bar press failure", e);
            } finally {
                event.recycle();
            }
        }

        /**
         * Watches the touches of the window. Instagram keeps getting every one of them as before,
         * taps and long presses on the bars are its own. Only a slide along a bar is taken over.
         */
        void watchTouches() {
            try {
                Window window = activity.getWindow();
                final Window.Callback original = window.getCallback();
                if (original == null || touchCallback != null) return;
                touchCallback = original;
                // Fingers wobble during a tap, only a clear slide counts as one.
                touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop() * 2;
                window.setCallback((Window.Callback) Proxy.newProxyInstance(activity.getClassLoader(),
                        new Class<?>[]{Window.Callback.class}, (proxy, method, arguments) -> {
                            if (arguments != null && arguments.length == 1 && arguments[0] instanceof MotionEvent
                                    && "dispatchTouchEvent".equals(method.getName())) {
                                boolean taken = false;
                                try {
                                    taken = onWindowTouch((MotionEvent) arguments[0]);
                                } catch (Throwable e) {
                                    Log.e(TAG, "touch failure", e);
                                }
                                if (taken) return Boolean.TRUE;
                            }
                            try {
                                return method.invoke(original, arguments);
                            } catch (InvocationTargetException e) {
                                throw e.getCause() != null ? e.getCause() : e;
                            }
                        }));
            } catch (Throwable e) {
                Log.e(TAG, "touch watch failure", e);
            }
        }

        /** Position of a touch along a bar, in the coordinates of the bar. */
        private float along(boolean rail, MotionEvent event) {
            View host = barHost(rail);
            if (host == null) return 0;
            host.getLocationInWindow(hitLocation);
            return rail ? event.getY() - hitLocation[1] : event.getX() - hitLocation[0];
        }

        /** Whether the touch of the window is taken away from Instagram. */
        private boolean onWindowTouch(MotionEvent event) {
            if (!glassBar) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                gesture = false;
                gestureDragging = false;
                Boolean rail = barUnder(event.getX(), event.getY());
                if (rail == null) return false;
                gesture = true;
                gestureRail = rail;
                gestureDown = rail ? event.getY() : event.getX();
                barPress(rail, along(rail, event));
                return false;
            }
            if (!gesture) return false;

            boolean rail = gestureRail;
            switch (action) {
                case MotionEvent.ACTION_MOVE:
                    float moved = (rail ? event.getY() : event.getX()) - gestureDown;
                    if (!gestureDragging && Math.abs(moved) > touchSlop) {
                        gestureDragging = true;
                        // From here on the finger moves the lens. Instagram lets go of the button
                        // it had pressed.
                        MotionEvent cancel = MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        try {
                            touchCallback.dispatchTouchEvent(cancel);
                        } finally {
                            cancel.recycle();
                        }
                    }
                    if (!gestureDragging) return false;
                    barDrag(rail, along(rail, event));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    gesture = false;
                    if (!gestureDragging) {
                        barUnpress(rail);
                        return false;
                    }
                    gestureDragging = false;
                    barRelease(rail, along(rail, event), action == MotionEvent.ACTION_UP);
                    return true;
                default:
                    return gestureDragging;
            }
        }

        /** The bar a touch lands on: true for the navigation rail, false for the tab bar, or null. */
        private Boolean barUnder(float x, float y) {
            View railHost = lastRailHost.get();
            View tabHost = lastTabHost.get();
            boolean onRail = railHost != null && railHost.isShown() && contains(railHost, x, y);
            boolean onTab = !onRail && tabHost != null && tabHost.isShown() && contains(tabHost, x, y);
            if (!onRail && !onTab) return null;

            // Something else can be on top of a bar, a sheet or a viewer for example.
            View hit = topClickableAt(activity.getWindow().getDecorView(), x, y, 0);
            if (hit == null) return onRail;
            if (onRail) return isInside(hit, railHost) ? Boolean.TRUE : null;
            return isInside(hit, tabHost) || hit == messagesView ? Boolean.FALSE : null;
        }

        private boolean contains(View view, float x, float y) {
            view.getLocationInWindow(hitLocation);
            return x >= hitLocation[0] && x < hitLocation[0] + view.getWidth()
                    && y >= hitLocation[1] && y < hitLocation[1] + view.getHeight();
        }

        /** The view a touch at this place goes to first: the topmost one that reacts to a tap. */
        private View topClickableAt(View view, float x, float y, int depth) {
            if (view.getVisibility() != View.VISIBLE || !contains(view, x, y)) return null;
            if (view instanceof ViewGroup && depth < 48) {
                ViewGroup group = (ViewGroup) view;
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    View hit = topClickableAt(group.getChildAt(i), x, y, depth + 1);
                    if (hit != null) return hit;
                }
            }
            return view.isClickable() || view.isLongClickable() ? view : null;
        }

        /**
         * Samples the screen next to the navigation rail into one color for every stretch of its
         * height, for the rim of its glass. Cheap and only now and then, the screen is not drawn twice.
         */
        private void captureAmbient(final GlassBar glass, final View host, View rail, View decor) {
            if (Build.VERSION.SDK_INT < 26 || ambientPending || glass.pill.isEmpty()) return;
            // Reading the screen back costs the renderer a moment, not while the glass is moving.
            if (glass.isBusy()) return;
            long now = SystemClock.uptimeMillis();
            if (now - ambientTime < AMBIENT_INTERVAL_MS) return;

            int[] location = new int[2];
            rail.getLocationInWindow(location);
            int left = location[0] + rail.getWidth();
            host.getLocationInWindow(location);
            Rect source = new Rect(left, Math.round(location[1] + glass.pill.top),
                    decor.getWidth(), Math.round(location[1] + glass.pill.bottom));
            if (source.width() <= 0 || source.height() <= 0) return;

            if (ambientShot == null) {
                ambientShot = Bitmap.createBitmap(AMBIENT_COLUMNS, AMBIENT_ROWS, Bitmap.Config.ARGB_8888);
            }
            ambientPending = true;
            ambientTime = now;
            try {
                PixelCopy.request(activity.getWindow(), source, ambientShot, result -> {
                    ambientPending = false;
                    if (result != PixelCopy.SUCCESS) return;
                    try {
                        int[] rows = ambientColors(ambientShot);
                        // A still screen must not keep the rail redrawing.
                        if (!differs(rows, ambientRows)) return;
                        ambientRows = rows;
                        glass.setGlow(Bitmap.createBitmap(rows, 1, rows.length, Bitmap.Config.ARGB_8888));
                        host.invalidate();
                    } catch (Throwable e) {
                        Log.e(TAG, "ambient failure", e);
                    }
                }, mainHandler);
            } catch (Throwable e) {
                ambientPending = false;
                Log.e(TAG, "ambient failure", e);
            }
        }

        /** One richer version of the average color of every row of a sample. */
        private int[] ambientColors(Bitmap shot) {
            int width = shot.getWidth();
            int height = shot.getHeight();
            int[] pixels = new int[width * height];
            shot.getPixels(pixels, 0, width, 0, 0, width, height);
            int[] rows = new int[height];
            float[] hsv = new float[3];
            for (int y = 0; y < height; y++) {
                int red = 0;
                int green = 0;
                int blue = 0;
                for (int x = 0; x < width; x++) {
                    int pixel = pixels[y * width + x];
                    red += Color.red(pixel);
                    green += Color.green(pixel);
                    blue += Color.blue(pixel);
                }
                Color.RGBToHSV(red / width, green / width, blue / width, hsv);
                hsv[1] = Math.min(1f, hsv[1] * 1.5f);
                rows[y] = Color.HSVToColor(hsv);
            }
            return rows;
        }

        private boolean differs(int[] rows, int[] other) {
            if (other == null || other.length != rows.length) return true;
            for (int i = 0; i < rows.length; i++) {
                if (Math.abs(Color.red(rows[i]) - Color.red(other[i])) > 5
                        || Math.abs(Color.green(rows[i]) - Color.green(other[i])) > 5
                        || Math.abs(Color.blue(rows[i]) - Color.blue(other[i])) > 5) return true;
            }
            return false;
        }

        private View findTabBar(View decor) {
            View tabBar = lookup(decor, tabBarId, lastTabBar);
            if (tabBar != null && tabBar != lastTabBar.get()) lastTabBar = new WeakReference<>(tabBar);
            return tabBar;
        }

        /** Name of the id of a view, bars name their buttons after what they open. */
        private String idName(View view) {
            if (view.getId() == View.NO_ID) return "";
            try {
                return activity.getResources().getResourceEntryName(view.getId());
            } catch (Resources.NotFoundException e) {
                return "";
            }
        }

        private View findCreateButton(ViewGroup buttons, boolean orMiddle) {
            List<View> shown = new ArrayList<>();
            for (int i = 0; i < buttons.getChildCount(); i++) {
                View child = buttons.getChildAt(i);
                String name = idName(child);
                if (name.contains("creation") || name.contains("create")) return child;
                if (child.getVisibility() == View.VISIBLE && child.getWidth() > 0) shown.add(child);
            }
            // The tab bar has it in the middle.
            return orMiddle && shown.size() % 2 == 1 ? shown.get(shown.size() / 2) : null;
        }

        /**
         * The tab bar gets a messages button in place of its create button. The navigation rail
         * already has a messages button, its create button is only removed.
         */
        private void updateMessagesTab(View decor) {
            try {
                View rail = findNavigationRail(decor);
                if (rail != null && rail.isShown()) {
                    ViewGroup buttons = findButtons(rail);
                    View create = buttons == null ? null : findCreateButton(buttons, false);
                    if (create != null && create.getVisibility() != View.GONE) {
                        create.setVisibility(View.GONE);
                        layoutChanged = true;
                    }
                }

                View tabBar = findTabBar(decor);
                ViewGroup buttons = tabBar != null && tabBar.isShown() ? findButtons(tabBar) : null;
                View create = null;
                if (buttons != null) {
                    View named = null;
                    boolean hasMessages = false;
                    List<View> shown = new ArrayList<>();
                    for (int i = 0; i < buttons.getChildCount(); i++) {
                        View child = buttons.getChildAt(i);
                        boolean visible = child.getVisibility() == View.VISIBLE && child.getWidth() > 0;
                        String name = idName(child);
                        if (name.contains("creation") || name.contains("create")) {
                            named = child;
                        } else if (visible && (name.contains("direct") || name.contains("message")
                                || name.contains("inbox"))) {
                            hasMessages = true;
                        }
                        if (visible) shown.add(child);
                    }
                    if (hasMessages) {
                        // Newer tab bars come with a messages button, only the create button has to go.
                        if (named != null && named.getVisibility() == View.VISIBLE) {
                            named.setVisibility(View.GONE);
                            layoutChanged = true;
                        }
                    } else if (named != null) {
                        // A hidden create button is brought back as a place for the messages button
                        // when the bar is one button short.
                        if (named.getVisibility() == View.VISIBLE || shown.size() % 2 == 0) create = named;
                    } else if (shown.size() % 2 == 1) {
                        create = shown.get(shown.size() / 2);
                    }
                }
                createSlot = new WeakReference<>(create);
                if (create == null || !(tabBar.getParent() instanceof ViewGroup)) {
                    if (messagesView != null) messagesView.setVisibility(View.GONE);
                    return;
                }
                ViewGroup parent = (ViewGroup) tabBar.getParent();

                // Instagram's button stays where it is, unseen, so the other buttons keep their places.
                // It can be hidden altogether, then there is no place for the messages button yet.
                create.setAlpha(0f);
                if (create.getVisibility() != View.VISIBLE) {
                    create.setVisibility(View.VISIBLE);
                    layoutChanged = true;
                }
                if (create.getWidth() == 0 || create.getHeight() == 0) {
                    if (messagesView != null) messagesView.setVisibility(View.GONE);
                    return;
                }
                if (messagesView == null) {
                    messagesView = new MessagesButton(activity);
                    messagesView.setOnClickListener(view -> openMessages());
                }
                if (messagesView.getParent() != parent) {
                    if (messagesView.getParent() instanceof ViewGroup) {
                        ((ViewGroup) messagesView.getParent()).removeView(messagesView);
                    }
                    parent.addView(messagesView, new ViewGroup.LayoutParams(create.getWidth(), create.getHeight()));
                }
                ViewGroup.LayoutParams params = messagesView.getLayoutParams();
                if (params.width != create.getWidth() || params.height != create.getHeight()) {
                    params.width = create.getWidth();
                    params.height = create.getHeight();
                    messagesView.setLayoutParams(params);
                }
                float left = 0;
                float top = 0;
                for (View view = create; view != parent; view = (View) view.getParent()) {
                    left += view.getLeft();
                    top += view.getTop();
                }
                messagesView.setTranslationX(left + tabBar.getTranslationX());
                messagesView.setTranslationY(top + tabBar.getTranslationY());
                messagesView.setVisibility(View.VISIBLE);

                boolean night = (activity.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
                messagesView.setLight(tabGlass != null ? tabGlass.light : !night);
            } catch (Exception e) {
                Log.e(TAG, "messages button failure", e);
            }
        }

        private void openMessages() {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(MESSAGES_LINK));
                intent.setPackage(activity.getPackageName());
                activity.startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "open messages failure", e);
            }
        }

        private void styleBar(GlassBar glass, View bar, View behind, boolean ambient) {
            // The glass is drawn by the view that holds the buttons. That view can sit inside the
            // bar with a solid background of its own, which would hide anything drawn by the bar.
            styledHost = null;
            ViewGroup buttons = findButtons(bar);
            if (buttons == null) {
                glass.clear();
                return;
            }
            bar = buttons;
            styledHost = buttons;

            Drawable background = bar.getBackground();
            if (background != glass) {
                // Instagram switches between a light and a dark bar, keep following that.
                boolean night = (activity.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
                glass.light = !night;
                if (background instanceof ColorDrawable) {
                    int color = ((ColorDrawable) background).getColor();
                    if (Color.alpha(color) > 0) glass.light = Color.luminance(color) > 0.5f;
                }
                bar.setBackground(glass);
                // The lens under a finger is larger than the pill it rides on.
                if (bar.getParent() instanceof ViewGroup) ((ViewGroup) bar.getParent()).setClipChildren(false);
            }
            glass.layout(bar, buttons, 0, 0);

            // Only a bar that floats over the screen shows it through itself.
            if (ambient || behind == null || blurFailed || Build.VERSION.SDK_INT < 31 || glass.pill.isEmpty()) {
                glass.blur = null;
                return;
            }
            try {
                if (glass.blur == null) {
                    glass.blur = new GlassBlur(activity.getResources().getDisplayMetrics().density);
                }
                int[] barLocation = new int[2];
                int[] behindLocation = new int[2];
                bar.getLocationInWindow(barLocation);
                behind.getLocationInWindow(behindLocation);
                ((GlassBlur) glass.blur).capture(behind,
                        behindLocation[0] - barLocation[0] - glass.pill.left,
                        behindLocation[1] - barLocation[1] - glass.pill.top,
                        Math.round(glass.pill.width()), Math.round(glass.pill.height()), 1f);
            } catch (Throwable e) {
                // Not every device or view can be recorded like this, go without blur then.
                Log.e(TAG, "blur failure", e);
                blurFailed = true;
                glass.blur = null;
            }
        }

        /** The buttons of a bar are the first group of at least three views of about one size inside it. */
        private ViewGroup findButtons(View bar) {
            if (!(bar instanceof ViewGroup)) return null;
            List<ViewGroup> level = new ArrayList<>();
            level.add((ViewGroup) bar);
            for (int depth = 0; depth < MAX_BUTTONS_DEPTH && !level.isEmpty(); depth++) {
                List<ViewGroup> next = new ArrayList<>();
                for (ViewGroup group : level) {
                    int shown = 0;
                    int minWidth = Integer.MAX_VALUE;
                    int maxWidth = 0;
                    int minHeight = Integer.MAX_VALUE;
                    int maxHeight = 0;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        View child = group.getChildAt(i);
                        if (child.getVisibility() != View.VISIBLE || child.getWidth() == 0
                                || child.getHeight() == 0) continue;
                        shown++;
                        minWidth = Math.min(minWidth, child.getWidth());
                        maxWidth = Math.max(maxWidth, child.getWidth());
                        minHeight = Math.min(minHeight, child.getHeight());
                        maxHeight = Math.max(maxHeight, child.getHeight());
                        if (child instanceof ViewGroup) next.add((ViewGroup) child);
                    }
                    if (shown >= MIN_BUTTONS && maxWidth <= MAX_BUTTON_SIZE_RATIO * minWidth
                            && maxHeight <= MAX_BUTTON_SIZE_RATIO * minHeight) {
                        return group;
                    }
                }
                level = next;
            }
            return null;
        }

        /** Lists end underneath the floating tab bar, they get room to scroll their end clear of it. */
        private void insetLists(View content, View tabBar, int room) {
            if (!(content instanceof ViewGroup) || listSearches++ % LIST_SEARCH_INTERVAL != 0) return;
            int[] location = new int[2];
            tabBar.getLocationInWindow(location);
            int barTop = location[1];

            List<ViewGroup> level = new ArrayList<>();
            level.add((ViewGroup) content);
            for (int depth = 0; depth < MAX_LIST_DEPTH && !level.isEmpty(); depth++) {
                List<ViewGroup> next = new ArrayList<>();
                for (ViewGroup group : level) {
                    for (int i = 0; i < group.getChildCount(); i++) {
                        View child = group.getChildAt(i);
                        if (!(child instanceof ViewGroup) || child.getVisibility() != View.VISIBLE) continue;
                        // Reels are pages, not a list, and are handled on their own.
                        if (child.getClass().getName().contains("ViewPager2")) continue;

                        boolean list = child.getHeight() > content.getHeight() / 2
                                && (child.canScrollVertically(1) || child.canScrollVertically(-1));
                        if (!list) {
                            next.add((ViewGroup) child);
                            continue;
                        }
                        child.getLocationInWindow(location);
                        if (location[1] + child.getHeight() <= barTop) continue;

                        Integer own = listPadding.get(child);
                        if (own == null) {
                            own = child.getPaddingBottom();
                            listPadding.put(child, own);
                        }
                        if (child.getPaddingBottom() != own + room) {
                            child.setPadding(child.getPaddingLeft(), child.getPaddingTop(),
                                    child.getPaddingRight(), own + room);
                            ((ViewGroup) child).setClipToPadding(false);
                        }
                    }
                }
                level = next;
            }
        }

        private void restoreLists() {
            if (listPadding.isEmpty()) return;
            for (Map.Entry<View, Integer> entry : listPadding.entrySet()) {
                View view = entry.getKey();
                if (view != null) {
                    view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), entry.getValue());
                }
            }
            listPadding.clear();
        }

        /** Keeps a button that restarts Instagram at the bottom of the navigation rail. */
        private void updateRestartButton(View decor) {
            View rail = findNavigationRail(decor);
            // Next to the rail rather than on top of the window, so sheets that open over the
            // rail, like comments, cover the button as well.
            View root = rail == null ? null : (View) rail.getParent();
            if (rail == null || !rail.isShown() || rail.getWidth() == 0 || !(root instanceof ViewGroup)) {
                if (restartView != null && restartView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) restartView.getParent()).removeView(restartView);
                }
                return;
            }

            float density = activity.getResources().getDisplayMetrics().density;
            int size = Math.round(RESTART_BUTTON_SIZE_DP * density);
            if (restartView == null) {
                restartView = new RestartButton(activity);
                restartView.setOnClickListener(view -> restart());
            }
            if (restartView.getParent() != root) {
                if (restartView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) restartView.getParent()).removeView(restartView);
                }
                ((ViewGroup) root).addView(restartView, new ViewGroup.LayoutParams(size, size));
            }

            WindowInsets insets = decor.getRootWindowInsets();
            int bottom = insets == null ? 0 : insets.getStableInsetBottom();
            int[] railLocation = new int[2];
            int[] rootLocation = new int[2];
            rail.getLocationInWindow(railLocation);
            root.getLocationInWindow(rootLocation);
            restartView.setTranslationX(railLocation[0] - rootLocation[0] + (rail.getWidth() - size) / 2f);
            restartView.setTranslationY(railLocation[1] - rootLocation[1] + rail.getHeight()
                    - bottom - size - RESTART_BUTTON_MARGIN_DP * density);
        }

        private void restart() {
            try {
                Context context = activity.getApplicationContext();
                Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                if (launch == null || launch.getComponent() == null) return;
                Intent intent = Intent.makeRestartActivityTask(launch.getComponent());
                intent.setPackage(context.getPackageName());
                context.startActivity(intent);
                System.exit(0);
            } catch (Exception e) {
                Log.e(TAG, "restart failure", e);
            }
        }

        private void drawBehindStatusBar(View decor) {
            Window window = activity.getWindow();

            int visibility = decor.getSystemUiVisibility();
            int missing = EDGE_TO_EDGE_FLAGS & ~visibility;
            if (missing != 0) {
                addedUiFlags |= missing;
                decor.setSystemUiVisibility(visibility | EDGE_TO_EDGE_FLAGS);
            }

            int color = window.getStatusBarColor();
            if (color != Color.TRANSPARENT) {
                if (originalStatusBarColor == null) originalStatusBarColor = color;
                window.setStatusBarColor(Color.TRANSPARENT);
            }
        }

        private void hideTabBar(View tabBar) {
            if (tabBar == null || tabBar.getVisibility() != View.VISIBLE) return;
            tabBar.setVisibility(View.GONE);
            tabBarHidden = true;
        }

        private void showTabBar(View decor) {
            View tabBar = tabBarId == 0 ? null : decor.findViewById(tabBarId);
            // Only revert our own change, Instagram hides the tab bar by itself at times
            // and does not use it at all next to the navigation rail.
            if (tabBarHidden && tabBar != null && tabBar.getVisibility() == View.GONE
                    && !navigationRailShown(decor)) {
                tabBar.setVisibility(View.VISIBLE);
            }
            tabBarHidden = false;
        }

        /**
         * Walks from the Reels view up to the decor view and removes the padding and margins
         * that keep it clear of the status bar, the navigation bar and the tab bar.
         */
        private void expand(View reels, View decor, int top, int bottom) {
            // Instagram keeps some spacing below the reel pages, that goes. Under a floating tab bar
            // the pages end above the bar instead: Instagram lays a page out for its own height and
            // puts the caption and buttons at its bottom, where the bar would cover them.
            int covered = 0;
            View tabBar = lastTabBar.get();
            if (floatLift > 0 && tabBar != null && tabBar.isShown()) {
                int[] location = new int[2];
                reels.getLocationInWindow(location);
                int reelsBottom = location[1] + reels.getHeight();
                tabBar.getLocationInWindow(location);
                covered = Math.max(0, reelsBottom - location[1]);
            }
            if (reels.getPaddingBottom() != covered) {
                if (!originalPadding.containsKey(reels)) rememberPadding(reels, false, true);
                reels.setPadding(reels.getPaddingLeft(), reels.getPaddingTop(), reels.getPaddingRight(), covered);
                changed = true;
            }

            View child = reels;
            while (child != decor) {
                ViewParent viewParent = child.getParent();
                if (!(viewParent instanceof ViewGroup)) break;
                ViewGroup parent = (ViewGroup) viewParent;

                removeMargins(child, top, bottom);
                removePadding(parent, child, top, bottom);
                child = parent;
            }
            holdRail(reels, decor);
        }

        private boolean isInside(View view, View ancestor) {
            for (View current = view; current != null; ) {
                if (current == ancestor) return true;
                current = current.getParent() instanceof View ? (View) current.getParent() : null;
            }
            return false;
        }

        /**
         * The navigation rail sits inside one of the views that grow when a reel is expanded, and
         * would grow and move its buttons along. It is padded by what was taken away around it,
         * so it looks the same in Reels as everywhere else.
         */
        private void holdRail(View reels, View decor) {
            View rail = lastRail.get();
            if (rail == null || !rail.isShown()) return;

            int top = 0;
            int bottom = 0;
            View child = reels;
            while (child != decor && child.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) child.getParent();
                if (isInside(rail, child)) {
                    int[] padding = originalPadding.get(parent);
                    if (padding != null) {
                        top += padding[1] - parent.getPaddingTop();
                        bottom += padding[3] - parent.getPaddingBottom();
                    }
                    int[] margins = originalMargins.get(child);
                    if (margins != null && child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
                        top += margins[0] - params.topMargin;
                        bottom += margins[1] - params.bottomMargin;
                    }
                }
                child = parent;
            }
            if (top <= 0 && bottom <= 0 && railPadding == null) return;

            if (railPadding == null || paddedRail.get() != rail) {
                railPadding = new int[]{rail.getPaddingLeft(), rail.getPaddingTop(),
                        rail.getPaddingRight(), rail.getPaddingBottom()};
                paddedRail = new WeakReference<>(rail);
            }
            int wantTop = railPadding[1] + Math.max(0, top);
            int wantBottom = railPadding[3] + Math.max(0, bottom);
            if (rail.getPaddingTop() != wantTop || rail.getPaddingBottom() != wantBottom) {
                rail.setPadding(railPadding[0], wantTop, railPadding[2], wantBottom);
                changed = true;
            }
        }

        private void rememberPadding(View view, boolean top, boolean bottom) {
            int[] original = originalPadding.get(view);
            if (original == null) {
                original = new int[]{view.getPaddingLeft(), view.getPaddingTop(),
                        view.getPaddingRight(), view.getPaddingBottom()};
                originalPadding.put(view, original);
                return;
            }
            // Instagram set this side again, its new value is the one to go back to.
            if (top) original[1] = view.getPaddingTop();
            if (bottom) original[3] = view.getPaddingBottom();
        }

        private void removeMargins(View view, int top, int bottom) {
            if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();

            boolean removeTop = isTopInset(params.topMargin, top);
            boolean removeBottom = isBottomInset(params.bottomMargin, bottom);
            if (!removeTop && !removeBottom) return;

            int[] original = originalMargins.get(view);
            if (original == null) {
                originalMargins.put(view, new int[]{params.topMargin, params.bottomMargin});
            } else {
                if (removeTop) original[0] = params.topMargin;
                if (removeBottom) original[1] = params.bottomMargin;
            }

            if (removeTop) params.topMargin = 0;
            if (removeBottom) params.bottomMargin = 0;
            view.setLayoutParams(params);
            changed = true;
        }

        private void removePadding(ViewGroup parent, View pathChild, int top, int bottom) {
            int paddingTop = parent.getPaddingTop();
            int paddingBottom = parent.getPaddingBottom();

            int removedTop = isTopInset(paddingTop, top) ? paddingTop : 0;
            int removedBottom = isBottomInset(paddingBottom, bottom) ? paddingBottom : 0;
            if (removedTop == 0 && removedBottom == 0) return;

            rememberPadding(parent, removedTop != 0, removedBottom != 0);
            parent.setPadding(parent.getPaddingLeft(), paddingTop - removedTop,
                    parent.getPaddingRight(), paddingBottom - removedBottom);
            changed = true;

            // Headers and other overlays next to the Reels view keep their old position,
            // otherwise they would end up underneath the status bar clock.
            int middle = parent.getHeight() / 2;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View sibling = parent.getChildAt(i);
                if (sibling == pathChild || originalTranslation.containsKey(sibling)) continue;

                boolean lowerHalf = sibling.getTop() + sibling.getHeight() / 2 > middle;
                int shift = lowerHalf ? -removedBottom : removedTop;
                if (shift == 0) continue;

                originalTranslation.put(sibling, sibling.getTranslationY());
                sibling.setTranslationY(sibling.getTranslationY() + shift);
            }
        }

        /**
         * Instagram lays out the video card and its buttons for the height that was available
         * below the status bar, so an expanded page keeps empty space at the bottom.
         * The content of each page is scaled up and moved until it spans the full height.
         */
        private void fill(View reels) {
            if (!(reels instanceof ViewGroup)) return;
            ViewGroup pager = (ViewGroup) reels;
            if (pager.getChildCount() == 0 || !(pager.getChildAt(0) instanceof ViewGroup)) return;

            ViewGroup pages = (ViewGroup) pager.getChildAt(0);
            pass++;
            for (int i = 0; i < pages.getChildCount(); i++) {
                View content = findContent(pages.getChildAt(i), 0);
                if (!(content instanceof ViewGroup)) continue;
                scale((ViewGroup) content);
                if (expandMedia) enlarge((ViewGroup) content);
                if (moveButtons) moveButtons((ViewGroup) content);
                if (railShown) fade((ViewGroup) content);
            }
            // Instagram reuses these views for other reels, anything not confirmed in this pass
            // must not keep its changes.
            resetEnlarged(false);
        }

        /** The card of a page is the child that holds its video or photo. */
        private View findCard(ViewGroup content) {
            View media = mediaId == 0 ? null : content.findViewById(mediaId);
            return media == null ? null : childContaining(content, media);
        }

        private View childContaining(ViewGroup group, View descendant) {
            View view = descendant;
            while (view != null && view.getParent() != group) {
                view = view.getParent() instanceof View ? (View) view.getParent() : null;
            }
            return view;
        }

        /**
         * Photos and wide videos only use part of the tall card of a reel. They are scaled up
         * until they reach the top and bottom or the sides of the page, whichever comes first.
         *
         * The card draws its border over everything inside it, so the card as a whole is grown
         * until that border is off screen and its contents are sized back down inside it.
         */
        private void enlarge(ViewGroup content) {
            View card = findCard(content);
            if (!(card instanceof ViewGroup) || card.getWidth() == 0 || card.getHeight() == 0) return;
            View media = content.findViewById(mediaId);
            if (!(media instanceof ViewGroup) || media.getWidth() == 0 || media.getHeight() == 0) return;
            View box = findLetterboxed((ViewGroup) media);
            if (box == null) return;

            // Center of the media inside the card.
            float boxX = box.getWidth() / 2f;
            float boxY = box.getHeight() / 2f;
            View view = box;
            while (view != card) {
                boxX += view.getLeft();
                boxY += view.getTop();
                if (!(view.getParent() instanceof View)) return;
                view = (View) view.getParent();
            }

            // The page itself can be scaled and moved, work out which part of it is on screen.
            float pageScale = content.getScaleY();
            if (pageScale <= 0) return;
            float visibleWidth = content.getWidth() / pageScale;
            float visibleHeight = content.getHeight() / pageScale;
            float factor = Math.min(visibleWidth / box.getWidth(), visibleHeight / box.getHeight());
            if (factor < MIN_ENLARGE) return;
            float centerX = content.getWidth() / 2f;
            float centerY = (content.getHeight() / 2f - content.getTranslationY()) / pageScale;

            float cardScale = CARD_OVERSCAN
                    * Math.max(visibleWidth / card.getWidth(), visibleHeight / card.getHeight());
            float cardX = card.getWidth() / 2f;
            float cardY = card.getHeight() / 2f;
            float moveX = centerX - (card.getLeft() + cardX);
            float moveY = centerY - (card.getTop() + cardY);
            transform(card, cardX, cardY, cardScale, moveX, moveY);
            transform(box, box.getWidth() / 2f, box.getHeight() / 2f, factor / cardScale,
                    cardX - boxX, cardY - boxY);

            // The caption and everything else on top of the media keeps its size and place.
            ViewGroup cardGroup = (ViewGroup) card;
            View mediaBranch = childContaining(cardGroup, box);
            for (int i = 0; i < cardGroup.getChildCount(); i++) {
                View child = cardGroup.getChildAt(i);
                if (child == mediaBranch) continue;
                transform(child, cardX - child.getLeft(), cardY - child.getTop(), 1f / cardScale,
                        -moveX / cardScale, -moveY / cardScale);
            }
        }

        /** The progress bar of a reel is shown on the navigation rail instead. */
        private void fade(ViewGroup content) {
            View scrubber = scrubberId == 0 ? null : content.findViewById(scrubberId);
            if (!(scrubber instanceof ProgressBar)) return;
            scrubber.setAlpha(0f);
            faded.put(scrubber, pass);
        }

        /**
         * Shows the progress of the current reel as a vertical bar on the seam between the
         * navigation rail and the reel, and passes drags on it on to Instagram's own progress bar.
         */
        private void updateSeamBar(View reels, View decor, int top, int bottom) {
            View scrubber = null;
            if (railShown) {
                View page = currentPage(reels);
                View candidate = page == null || scrubberId == 0 ? null : page.findViewById(scrubberId);
                // Instagram hides its own bar at times, for example next to the comments pane.
                // It still tracks the video then, so it does not have to be visible.
                if (candidate instanceof ProgressBar && ((ProgressBar) candidate).getMax() > 0) {
                    scrubber = candidate;
                }
            }
            if (scrubber == null) {
                if (seamBar != null) seamBar.setVisibility(View.GONE);
                return;
            }

            // Next to the rail rather than on top of the window, so sheets that open over the
            // rail, like comments, cover the bar as well.
            View rail = findNavigationRail(decor);
            View root = rail == null ? null : (View) rail.getParent();
            if (!(root instanceof ViewGroup)) {
                if (seamBar != null) seamBar.setVisibility(View.GONE);
                return;
            }
            if (seamBar == null) seamBar = new SeamBar(activity);
            int width = Math.round(SEAM_TOUCH_WIDTH_DP * activity.getResources().getDisplayMetrics().density);
            if (seamBar.getParent() != root) {
                if (seamBar.getParent() instanceof ViewGroup) ((ViewGroup) seamBar.getParent()).removeView(seamBar);
                ((ViewGroup) root).addView(seamBar,
                        new ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            seamBar.setVisibility(View.VISIBLE);

            int[] reelsLocation = new int[2];
            int[] rootLocation = new int[2];
            reels.getLocationInWindow(reelsLocation);
            root.getLocationInWindow(rootLocation);
            seamBar.setTranslationX(reelsLocation[0] - rootLocation[0] - width / 2f);

            ProgressBar progress = (ProgressBar) scrubber;
            seamBar.show(scrubber, progress.getProgress() / (float) progress.getMax(), top, bottom);
        }

        /** Moves the like, comment and share buttons to the right edge of the page. */
        private void moveButtons(ViewGroup content) {
            View buttons = buttonsId == 0 ? null : content.findViewById(buttonsId);
            if (buttons == null || buttons.getParent() != content) return;
            float pageScale = content.getScaleY();
            if (pageScale <= 0) return;

            float visibleRight = content.getWidth() / 2f + content.getWidth() / (2f * pageScale);
            float shift = visibleRight - buttons.getRight();
            if (shift <= 0) return;
            buttons.setTranslationX(shift);
            enlarged.put(buttons, pass);
        }

        private void transform(View view, float pivotX, float pivotY, float scale, float moveX, float moveY) {
            view.setPivotX(pivotX);
            view.setPivotY(pivotY);
            view.setScaleX(scale);
            view.setScaleY(scale);
            view.setTranslationX(moveX);
            view.setTranslationY(moveY);
            enlarged.put(view, pass);
        }

        /** Finds the outermost view inside the card that is clearly smaller than the card. */
        private View findLetterboxed(ViewGroup media) {
            float width = media.getWidth();
            float height = media.getHeight();

            List<ViewGroup> level = new ArrayList<>();
            level.add(media);
            for (int depth = 0; depth < MAX_MEDIA_DEPTH && !level.isEmpty(); depth++) {
                List<ViewGroup> next = new ArrayList<>();
                for (ViewGroup group : level) {
                    for (int i = 0; i < group.getChildCount(); i++) {
                        View child = group.getChildAt(i);
                        if (child.getVisibility() != View.VISIBLE) continue;
                        int childWidth = child.getWidth();
                        int childHeight = child.getHeight();
                        if (childWidth * (float) childHeight < MIN_MEDIA_AREA * width * height) continue;

                        if (childWidth <= LETTERBOX_RATIO * width || childHeight <= LETTERBOX_RATIO * height) {
                            return child;
                        }
                        if (child instanceof ViewGroup) next.add((ViewGroup) child);
                    }
                }
                level = next;
            }
            return null;
        }

        private void resetEnlarged(boolean all) {
            for (Iterator<Map.Entry<View, Integer>> iterator = enlarged.entrySet().iterator(); iterator.hasNext(); ) {
                Map.Entry<View, Integer> entry = iterator.next();
                if (!all && entry.getValue() == pass) continue;
                View view = entry.getKey();
                if (view != null) {
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                    view.setTranslationX(0f);
                    view.setTranslationY(0f);
                }
                iterator.remove();
            }

            for (Iterator<Map.Entry<View, Integer>> iterator = faded.entrySet().iterator(); iterator.hasNext(); ) {
                Map.Entry<View, Integer> entry = iterator.next();
                if (!all && entry.getValue() == pass) continue;
                if (entry.getKey() != null) entry.getKey().setAlpha(1f);
                iterator.remove();
            }
        }

        private View findContent(View view, int depth) {
            if (LITHO_VIEW_CLASS.equals(view.getClass().getName())) return view;
            if (depth >= MAX_CONTENT_DEPTH || !(view instanceof ViewGroup)) return null;

            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View content = findContent(group.getChildAt(i), depth + 1);
                if (content != null) return content;
            }
            return null;
        }

        private void scale(ViewGroup content) {
            int width = content.getWidth();
            int height = content.getHeight();
            if (width == 0 || height == 0) return;
            // Only the layout for large screens leaves part of a page unused. On a phone screen the
            // page is filled already, scaling it there pushes the caption out at the bottom.
            if (!wideLayout) {
                if (content.getScaleY() != 1f || content.getTranslationY() != 0f) {
                    content.setScaleX(1f);
                    content.setScaleY(1f);
                    content.setTranslationY(0f);
                }
                return;
            }

            // Bounds of what the page actually shows.
            int left = Integer.MAX_VALUE;
            int top = Integer.MAX_VALUE;
            int right = Integer.MIN_VALUE;
            int bottom = Integer.MIN_VALUE;
            for (int i = 0; i < content.getChildCount(); i++) {
                View child = content.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getWidth() == 0 || child.getHeight() == 0) continue;
                left = Math.min(left, child.getLeft());
                top = Math.min(top, child.getTop());
                right = Math.max(right, child.getRight());
                bottom = Math.max(bottom, child.getBottom());
            }
            if (bottom <= top) return;
            // Instagram reserves room below the card at times, only the card has to fit.
            View card = findCard(content);
            if (card != null && card.getHeight() > 0) {
                top = card.getTop();
                bottom = card.getBottom();
            }

            // Edge to edge, but never push anything off the sides.
            float center = width / 2f;
            float reach = Math.max(center - left, right - center);
            float scale = height / (float) (bottom - top);
            if (reach > 0) scale = Math.min(scale, center / reach);
            float translation = -top * scale;
            if (scale < MIN_SCALE) {
                scale = 1f;
                translation = 0f;
            }

            if (Math.abs(content.getScaleY() - scale) < 0.001f
                    && Math.abs(content.getTranslationY() - translation) < 0.5f) return;
            scaled.put(content, Boolean.TRUE);
            content.setPivotX(center);
            content.setPivotY(0);
            content.setScaleX(scale);
            content.setScaleY(scale);
            content.setTranslationY(translation);
        }

        private boolean isTopInset(int value, int top) {
            return top > 0 && value > 0 && Math.abs(value - top) <= TOLERANCE_PX;
        }

        private boolean isBottomInset(int value, int bottom) {
            if (value <= 0) return false;
            if (bottom > 0 && Math.abs(value - bottom) <= TOLERANCE_PX) return true;
            // Space reserved for the tab bar is only given up when the tab bar is gone.
            if (!tabBarHidden || tabBarHeight == 0) return false;
            return Math.abs(value - tabBarHeight) <= TOLERANCE_PX
                    || Math.abs(value - tabBarHeight - bottom) <= TOLERANCE_PX;
        }

        private void restore(View decor) {
            View rail = paddedRail.get();
            if (railPadding != null && rail != null) {
                rail.setPadding(railPadding[0], railPadding[1], railPadding[2], railPadding[3]);
            }
            railPadding = null;

            for (Map.Entry<View, int[]> entry : originalPadding.entrySet()) {
                View view = entry.getKey();
                int[] padding = entry.getValue();
                if (view != null) view.setPadding(padding[0], padding[1], padding[2], padding[3]);
            }
            originalPadding.clear();

            for (Map.Entry<View, int[]> entry : originalMargins.entrySet()) {
                View view = entry.getKey();
                if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) continue;
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
                params.topMargin = entry.getValue()[0];
                params.bottomMargin = entry.getValue()[1];
                view.setLayoutParams(params);
            }
            originalMargins.clear();

            for (Map.Entry<View, Float> entry : originalTranslation.entrySet()) {
                if (entry.getKey() != null) entry.getKey().setTranslationY(entry.getValue());
            }
            originalTranslation.clear();

            for (View view : scaled.keySet()) {
                if (view == null) continue;
                view.setScaleX(1f);
                view.setScaleY(1f);
                view.setTranslationY(0f);
            }
            scaled.clear();
            resetEnlarged(true);
            if (seamBar != null && seamBar.getParent() instanceof ViewGroup) {
                ((ViewGroup) seamBar.getParent()).removeView(seamBar);
            }

            if (addedUiFlags != 0) {
                decor.setSystemUiVisibility(decor.getSystemUiVisibility() & ~addedUiFlags);
                addedUiFlags = 0;
            }
            if (originalStatusBarColor != null) {
                activity.getWindow().setStatusBarColor(originalStatusBarColor);
                originalStatusBarColor = null;
            }

            showTabBar(decor);
            churn = 0;
            active = false;
            // The remembered spacing can be from before a fold or rotation, let Instagram redo it.
            decor.requestApplyInsets();
            // Everything goes back in one layout, the frame before it would show half of it.
            layoutChanged = true;
        }

        private View currentPage(View reels) {
            if (!(reels instanceof ViewGroup)) return null;
            ViewGroup pager = (ViewGroup) reels;
            if (pager.getChildCount() == 0 || !(pager.getChildAt(0) instanceof ViewGroup)) return null;

            ViewGroup pages = (ViewGroup) pager.getChildAt(0);
            for (int i = 0; i < pages.getChildCount(); i++) {
                View page = pages.getChildAt(i);
                if (Math.abs(page.getTop()) <= 5 && Math.abs(page.getLeft()) <= 5) return page;
            }
            return null;
        }

        /** Describes every reel the pager settles on and copies that to the clipboard. */
        private void watchPage(final View reels) {
            final View page = currentPage(reels);
            if (page == null) return;
            // Opening the comments pane resizes the page without changing it.
            if (page == dumpedPage.get() && page.getWidth() == dumpedWidth && page.getHeight() == dumpedHeight) return;
            dumpedPage = new WeakReference<>(page);
            dumpedWidth = page.getWidth();
            dumpedHeight = page.getHeight();

            final View decor = activity.getWindow().getDecorView();
            if (pendingDump != null) decor.removeCallbacks(pendingDump);
            pendingDump = () -> {
                try {
                    if (currentPage(reels) == page) dump(reels, page);
                } catch (Exception e) {
                    Log.e(TAG, "dump failure", e);
                }
            };
            decor.postDelayed(pendingDump, DUMP_DELAY_MS);
        }

        private void dump(View reels, View page) {
            StringBuilder builder = new StringBuilder();
            View decor = activity.getWindow().getDecorView();
            builder.append("decor=").append(decor.getWidth()).append('x').append(decor.getHeight()).append('\n');
            describe(builder, reels, "pager ");
            View scrubber = scrubberId == 0 ? null : page.findViewById(scrubberId);
            builder.append("rail=").append(navigationRailShown(decor));
            if (scrubber == null) {
                builder.append(" scrubber=none\n");
            } else {
                builder.append(" scrubber shown=").append(scrubber.isShown());
                if (scrubber instanceof ProgressBar) {
                    builder.append(" progress=").append(((ProgressBar) scrubber).getProgress())
                            .append('/').append(((ProgressBar) scrubber).getMax());
                }
                builder.append('\n');
                describe(builder, scrubber, "scrubber ");
            }
            int[] lines = {0};
            describeTree(builder, page, 0, lines);

            // The bars around the reel and everything the reel sits in.
            View tabBar = tabBarId == 0 ? null : decor.findViewById(tabBarId);
            if (tabBar != null) {
                builder.append("== tab bar\n");
                describeTree(builder, tabBar, 0, new int[]{MAX_DUMP_LINES - 60});
                if (tabBar.getParent() instanceof ViewGroup) {
                    builder.append("== next to the tab bar\n");
                    ViewGroup parent = (ViewGroup) tabBar.getParent();
                    for (int i = 0; i < parent.getChildCount(); i++) {
                        if (parent.getChildAt(i).getVisibility() == View.VISIBLE) {
                            describe(builder, parent.getChildAt(i), " ");
                        }
                    }
                }
            }
            View rail = findNavigationRail(decor);
            if (rail != null) {
                ViewGroup railButtons = findButtons(rail);
                builder.append("== rail buttons ");
                if (railButtons == null) builder.append("not found\n");
                else describe(builder, railButtons, "");
                builder.append("== navigation rail\n");
                describeTree(builder, rail, 0, new int[]{MAX_DUMP_LINES - 60});
            }
            builder.append("== above the reels\n");
            for (View view = reels; view != null; view = view.getParent() instanceof View ? (View) view.getParent() : null) {
                describe(builder, view, "");
            }

            String text = builder.toString();
            Log.d(TAG, text);
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Reels layout", text));
                Toast.makeText(activity, "Reel layout copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        }

        private void describeTree(StringBuilder builder, View view, int depth, int[] lines) {
            if (lines[0]++ >= MAX_DUMP_LINES) return;
            StringBuilder indent = new StringBuilder();
            for (int i = 0; i < depth; i++) indent.append(' ');
            describe(builder, view, indent.toString());

            if (depth >= MAX_DUMP_DEPTH || !(view instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                describeTree(builder, group.getChildAt(i), depth + 1, lines);
            }
        }

        private void describe(StringBuilder builder, View view, String prefix) {
            String name = "no-id";
            if (view.getId() != View.NO_ID) {
                try {
                    name = activity.getResources().getResourceEntryName(view.getId());
                } catch (Resources.NotFoundException ignored) {
                }
            }
            int[] location = new int[2];
            view.getLocationInWindow(location);

            builder.append(prefix).append(view.getClass().getName()).append('#').append(name)
                    .append(" vis=").append(view.getVisibility())
                    .append(" at=").append(location[0]).append(',').append(location[1])
                    .append(" size=").append(view.getWidth()).append('x').append(view.getHeight());
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null) builder.append(" lp=").append(params.width).append('x').append(params.height);
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                if (margins.topMargin != 0 || margins.bottomMargin != 0) {
                    builder.append(" margin=").append(margins.topMargin).append('/').append(margins.bottomMargin);
                }
            }
            if (view.getPaddingTop() != 0 || view.getPaddingBottom() != 0) {
                builder.append(" pad=").append(view.getPaddingTop()).append('/').append(view.getPaddingBottom());
            }
            if (view.getBackground() != null) {
                builder.append(" bg=").append(view.getBackground().getClass().getSimpleName());
            }
            if (view.getScaleY() != 1 || view.getTranslationY() != 0) {
                builder.append(" sy=").append(view.getScaleY()).append(" ty=").append(view.getTranslationY());
            }
            if (view instanceof ImageView) {
                ImageView image = (ImageView) view;
                Drawable drawable = image.getDrawable();
                builder.append(" scaleType=").append(image.getScaleType());
                if (drawable != null) {
                    builder.append(" drawable=").append(drawable.getIntrinsicWidth())
                            .append('x').append(drawable.getIntrinsicHeight());
                }
            }
            if (view.getContentDescription() != null) builder.append(" described");
            builder.append('\n');
        }
    }

    /** Vertical progress bar that controls the progress bar of a reel. */
    private static final class SeamBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private WeakReference<View> target = new WeakReference<>(null);
        private float fraction;
        private boolean dragging;
        private int insetTop;
        private int insetBottom;

        SeamBar(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        void show(View scrubber, float progress, int top, int bottom) {
            if (target.get() != scrubber) target = new WeakReference<>(scrubber);
            boolean changed = insetTop != top || insetBottom != bottom;
            insetTop = top;
            insetBottom = bottom;
            // While dragging the finger decides where the bar is.
            if (!dragging && Math.abs(progress - fraction) > 0.0005f) {
                fraction = progress;
                changed = true;
            }
            if (changed) invalidate();
        }

        private float trackTop() {
            return insetTop + 8 * density;
        }

        private float trackBottom() {
            return getHeight() - insetBottom - 16 * density;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float top = trackTop();
            float bottom = trackBottom();
            if (bottom <= top) return;

            float x = getWidth() / 2f;
            paint.setStrokeWidth((dragging ? 6 : 3) * density);
            paint.setColor(0x55FFFFFF);
            canvas.drawLine(x, top, x, bottom, paint);
            paint.setColor(Color.WHITE);
            canvas.drawLine(x, top, x, top + fraction * (bottom - top), paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            View scrubber = target.get();
            float top = trackTop();
            float bottom = trackBottom();
            if (scrubber == null || bottom <= top) return false;

            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                dragging = false;
            } else if (action != MotionEvent.ACTION_MOVE) {
                return true;
            }

            fraction = Math.max(0f, Math.min(1f, (event.getY() - top) / (bottom - top)));
            invalidate();

            // A bar that was never laid out has no positions to seek to.
            if (scrubber.getWidth() == 0) return true;
            // Instagram's progress bar is horizontal, a point along this bar is the same point along that one.
            int left = scrubber.getPaddingLeft();
            float x = left + fraction * (scrubber.getWidth() - left - scrubber.getPaddingRight());
            MotionEvent forwarded = MotionEvent.obtain(event.getDownTime(), event.getEventTime(),
                    action, x, scrubber.getHeight() / 2f, 0);
            try {
                scrubber.dispatchTouchEvent(forwarded);
            } catch (Exception e) {
                Log.e(TAG, "seek failure", e);
            } finally {
                forwarded.recycle();
            }
            return true;
        }
    }

    /** Round arrow drawn in the style of the icons of the navigation rail. */
    private static final class RestartButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF circle = new RectF();
        private final float density;

        RestartButton(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(2 * density);
            paint.setColor(Color.WHITE);
            setContentDescription("Restart Instagram");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float centerX = getWidth() / 2f;
            float centerY = getHeight() / 2f;
            float radius = 9 * density;
            circle.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
            // Open circle that ends in an arrow head on the right.
            canvas.drawArc(circle, 30, 300, false, paint);

            double end = Math.toRadians(330);
            float endX = centerX + radius * (float) Math.cos(end);
            float endY = centerY + radius * (float) Math.sin(end);
            double direction = Math.atan2(Math.cos(end), -Math.sin(end));
            for (int side = -1; side <= 1; side += 2) {
                double angle = direction + side * Math.toRadians(150);
                canvas.drawLine(endX, endY, endX + 5 * density * (float) Math.cos(angle),
                        endY + 5 * density * (float) Math.sin(angle), paint);
            }
        }
    }

    /**
     * Background of a bar: one rounded pill of glass around all of its buttons.
     *
     * The selected button sits in a dark well. Under a finger the well rises into a clear lens
     * that is larger than the pill, follows the finger, stretches while it moves and sinks back
     * into a well on the button it is let go on. Every part of that moves on springs.
     *
     * The springs are moved on once for every frame of the screen, by the clock of the screen
     * itself, for as long as anything is moving. Nothing else has to cause a frame for that.
     */
    private static final class GlassBar extends Drawable {
        /** How long at most the lens waits where a finger left it for Instagram to switch tabs. */
        static final long HOLD_MS = 400;
        /** New colors along the rim take this long to replace the old ones. */
        private static final long GLOW_FADE_MS = 500;
        private static final int LEFT = 0;
        private static final int TOP = 1;
        private static final int RIGHT = 2;
        private static final int BOTTOM = 3;
        /** Springs are worked out in steps of at most this many seconds. */
        private static final float SPRING_STEP = 0.004f;

        final RectF pill = new RectF();
        private final RectF target = new RectF();
        private final RectF lens = new RectF();
        private final RectF next = new RectF();
        private final RectF inner = new RectF();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path outline = new Path();
        private final Path hole = new Path();
        private final float density;
        private Shader sheen;
        private Shader rim;
        private Shader highlight;
        private boolean highlightFailed;
        private boolean hasTarget;
        private boolean placed;
        private boolean vertical;
        boolean light;
        boolean floating;
        /** Next to the screen instead of over it: only the edge picks up its colors. */
        boolean ambient;
        /** A GlassBlur, only on Android versions that have it. */
        Object blur;

        /** Colors of the screen next to the bar from top to bottom, and the ones they replace. */
        private Bitmap glowNew;
        private Bitmap glowOld;
        private long glowSince;

        private boolean pressed;
        private boolean dragging;
        private float position;
        private long holdUntil;
        private boolean wasHeld;

        /** Edges of the highlight, where they are headed and how fast they move. */
        private final float[] edge = new float[4];
        private final float[] goal = new float[4];
        private final float[] speed = new float[4];
        /** 0 while the highlight is a well, 1 while it is a lens. */
        private float lift;
        private float liftSpeed;
        private boolean forward = true;

        /** Whether the clock of the screen is moving this bar on at the moment. */
        private boolean ticking;
        private long lastTick;
        private final Choreographer.FrameCallback ticker = this::tick;

        GlassBar(Context context) {
            density = context.getResources().getDisplayMetrics().density;
        }

        void press(float position) {
            pressed = true;
            this.position = position;
            wake();
        }

        void unpress() {
            pressed = false;
            holdUntil = SystemClock.uptimeMillis() + HOLD_MS;
            wake();
        }

        void drag(float position) {
            pressed = false;
            dragging = true;
            this.position = position;
            wake();
        }

        void release(float position) {
            dragging = false;
            this.position = position;
            holdUntil = SystemClock.uptimeMillis() + HOLD_MS;
            wake();
        }

        /** Whether the bar is being moved on frame by frame right now. */
        boolean isBusy() {
            return ticking;
        }

        /** Starts moving the bar on with every frame of the screen, until it has come to rest. */
        private void wake() {
            invalidateSelf();
            if (ticking) return;
            ticking = true;
            lastTick = 0;
            Choreographer.getInstance().postFrameCallback(ticker);
        }

        private void tick(long frameTime) {
            float elapsed = lastTick == 0 ? 1f / 120f : Math.min(0.034f, (frameTime - lastTick) / 1e9f);
            lastTick = frameTime;

            boolean moving = hasTarget && !pill.isEmpty() && animate(elapsed, isTouched() || holdUntil != 0);
            boolean fading = glowOld != null && SystemClock.uptimeMillis() - glowSince < GLOW_FADE_MS;
            invalidateSelf();
            // While a finger is down or the lens waits for Instagram, frames have to keep coming
            // even when the springs are at rest: that is how the wait is noticed to be over.
            if (moving || fading || isTouched() || holdUntil != 0) {
                Choreographer.getInstance().postFrameCallback(ticker);
            } else {
                ticking = false;
            }
        }

        /** Whether a finger is on the bar. */
        boolean isTouched() {
            return dragging || pressed;
        }

        /** Where along the bar the finger is. */
        float position() {
            return position;
        }

        void setGlow(Bitmap colors) {
            glowOld = glowNew;
            glowNew = colors;
            glowSince = SystemClock.uptimeMillis();
            wake();
        }

        void clear() {
            if (pill.isEmpty()) return;
            pill.setEmpty();
            invalidateSelf();
        }

        void layout(View bar, ViewGroup buttons, float offsetX, float offsetY) {
            float left = Float.MAX_VALUE;
            float top = Float.MAX_VALUE;
            float right = -Float.MAX_VALUE;
            float bottom = -Float.MAX_VALUE;
            View selected = null;
            int count = 0;
            for (int i = 0; i < buttons.getChildCount(); i++) {
                View child = buttons.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getWidth() == 0) continue;
                count++;
                left = Math.min(left, offsetX + child.getLeft());
                top = Math.min(top, offsetY + child.getTop());
                right = Math.max(right, offsetX + child.getRight());
                bottom = Math.max(bottom, offsetY + child.getBottom());
                if (selected == null && isSelected(child, 0)) selected = child;
            }
            if (right <= left || bottom <= top) {
                clear();
                return;
            }

            vertical = bottom - top > right - left;
            if (!vertical) {
                next.set(left + 12 * density, top + density, right - 12 * density, bottom - density);
            } else {
                float center = (left + right) / 2f;
                float half = Math.max(26 * density, (right - left) / 2f - 10 * density);
                half = Math.min(half, bar.getWidth() / 2f - 4 * density);
                next.set(center - half, top - 8 * density, center + half, bottom + 8 * density);
            }
            boolean changed = !next.equals(pill);
            pill.set(next);
            if (changed || sheen == null) {
                // Light falls on glass from above: bright upper edge, clear middle, faint glow below.
                sheen = new LinearGradient(0, pill.top, 0, pill.bottom,
                        new int[]{0x24FFFFFF, 0x08FFFFFF, 0x00FFFFFF, 0x0DFFFFFF},
                        new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP);
                rim = new LinearGradient(0, pill.top, 0, pill.bottom,
                        new int[]{0xB3FFFFFF, 0x26FFFFFF, 0x66FFFFFF},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
                highlight = null;
                if (Build.VERSION.SDK_INT >= 33 && !highlightFailed) {
                    try {
                        highlight = GlassBend.highlight(pill);
                    } catch (Throwable e) {
                        Log.e(TAG, "glass highlight failure", e);
                        highlightFailed = true;
                    }
                }
            }

            // After a finger lifts the lens stays put until Instagram has selected the button it
            // was over, which takes a frame or two, and sinks into the well there right away.
            boolean touched = isTouched();
            if (!touched && holdUntil != 0) {
                boolean landed = false;
                if (selected != null) {
                    float start = vertical ? offsetY + selected.getTop() : offsetX + selected.getLeft();
                    float end = vertical ? offsetY + selected.getBottom() : offsetX + selected.getRight();
                    landed = position >= start && position <= end;
                }
                if (landed || SystemClock.uptimeMillis() >= holdUntil) holdUntil = 0;
            }
            boolean held = touched || holdUntil != 0;
            if (held != wasHeld) wake();
            wasHeld = held;

            boolean hadTarget = hasTarget;
            hasTarget = selected != null || held;
            float inset = 4 * density;
            if (held) {
                float size = (vertical ? bottom - top : right - left) / count - 2 * inset;
                if (selected != null) size = (vertical ? selected.getHeight() : selected.getWidth()) - 2 * inset;
                float start = (vertical ? pill.top : pill.left) + inset + size / 2f;
                float end = (vertical ? pill.bottom : pill.right) - inset - size / 2f;
                float center = Math.max(start, Math.min(end, position));
                if (vertical) {
                    next.set(pill.left + inset, center - size / 2f, pill.right - inset, center + size / 2f);
                } else {
                    next.set(center - size / 2f, pill.top + inset, center + size / 2f, pill.bottom - inset);
                }
            } else if (hasTarget) {
                if (vertical) {
                    next.set(pill.left + inset, offsetY + selected.getTop() + inset / 2,
                            pill.right - inset, offsetY + selected.getBottom() - inset / 2);
                } else {
                    next.set(offsetX + selected.getLeft() + inset, pill.top + inset,
                            offsetX + selected.getRight() - inset, pill.bottom - inset);
                    next.left = Math.max(next.left, pill.left + inset);
                    next.right = Math.min(next.right, pill.right - inset);
                }
            }
            if (hasTarget) {
                changed |= !next.equals(target);
                target.set(next);
                goal[LEFT] = target.left;
                goal[TOP] = target.top;
                goal[RIGHT] = target.right;
                goal[BOTTOM] = target.bottom;
                if (!placed) {
                    System.arraycopy(goal, 0, edge, 0, 4);
                    placed = true;
                }
            }
            if (changed || hadTarget != hasTarget) wake();
        }

        private boolean isSelected(View view, int depth) {
            if (view.isSelected()) return true;
            if (depth >= 3 || !(view instanceof ViewGroup)) return false;
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (isSelected(group.getChildAt(i), depth + 1)) return true;
            }
            return false;
        }

        @Override
        public void draw(Canvas canvas) {
            if (pill.isEmpty()) return;
            float radius = Math.min(pill.width(), pill.height()) / 2f;

            // Soft shadow around the pill, kept out from under the glass itself.
            if (floating) {
                outline.rewind();
                outline.addRoundRect(pill, radius, radius, Path.Direction.CW);
                canvas.save();
                canvas.clipOutPath(outline);
                shadowPaint.setColor(0x01000000);
                shadowPaint.setShadowLayer(8 * density, 0, 2 * density, 0x40000000);
                canvas.drawRoundRect(pill, radius, radius, shadowPaint);
                canvas.restore();
            }

            boolean blurred = false;
            if (!ambient && blur != null && Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()) {
                blurred = ((GlassBlur) blur).draw(canvas, pill, radius);
            }

            // Thin tint, the blur and the highlights are what make it read as glass.
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            if (ambient) {
                // Plain dark glass. Nothing is behind this pill, a picture inside it would be made up.
                paint.setColor(light ? 0x1F000000 : 0x26FFFFFF);
                canvas.drawRoundRect(pill, radius, radius, paint);
                // Its rim catches a little of the colors of the screen next to it,
                // fading out a few pixels in.
                if (glowNew != null) {
                    glow(canvas, radius, 2.5f * density, 120);
                    glow(canvas, radius, 6f * density, 55);
                }
                paint.setColor(0);
            } else if (blurred) {
                paint.setColor(light ? 0x38FFFFFF : 0x61000000);
            } else if (floating) {
                paint.setColor(light ? 0x99FFFFFF : 0x8C1C1C1E);
            } else {
                paint.setColor(light ? 0x14000000 : 0x1FFFFFFF);
            }
            canvas.drawRoundRect(pill, radius, radius, paint);

            paint.setColor(Color.WHITE);
            paint.setShader(sheen);
            canvas.drawRoundRect(pill, radius, radius, paint);
            paint.setShader(null);

            // Outline of the pill underneath the lens, the lens rides on top of it.
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.WHITE);
            if (highlight != null) {
                // Faint outline all around, bright where the edge faces the light.
                paint.setStrokeWidth(0.75f * density);
                paint.setColor(0x1AFFFFFF);
                canvas.drawRoundRect(pill, radius, radius, paint);
                paint.setColor(Color.WHITE);
                paint.setStrokeWidth(density);
                paint.setShader(highlight);
            } else {
                paint.setStrokeWidth(1.25f * density);
                paint.setShader(rim);
            }
            canvas.drawRoundRect(pill, radius, radius, paint);
            paint.setShader(null);

            if (hasTarget) drawHighlight(canvas, blurred);
        }

        /** The well of the selected button, or the lens under a finger, or something in between. */
        private void drawHighlight(Canvas canvas, boolean blurred) {
            // How much of a lens it looks like, and how large it is: the size may swing a little
            // past either end, like something soft.
            float shown = Math.max(0f, Math.min(1f, lift));
            float grown = Math.max(-0.2f, Math.min(1.25f, lift));
            lens.set(edge[LEFT], edge[TOP], edge[RIGHT], edge[BOTTOM]);
            lens.sort();
            // The lens is wider than a button and taller than the pill.
            float along = (vertical ? lens.height() : lens.width()) * 0.16f * grown;
            float across = ((vertical ? lens.width() : lens.height()) * 0.14f + 4 * density) * grown;
            if (vertical) lens.inset(-across, -along);
            else lens.inset(-along, -across);

            if (lens.width() > 1f && lens.height() > 1f) {
                float lensRadius = Math.min(lens.width(), lens.height()) / 2f;

                // What is behind the bar shows through the lens enlarged.
                if (blurred && shown > 0.01f) {
                    ((GlassBlur) blur).lens(canvas, pill, lens, lensRadius, 1f + 0.3f * shown);
                }

                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                // Well: darker than the pill. Lens: clear with a breath of light.
                paint.setColor(Math.round((light ? 0x24 : 0x70) * (1f - shown)) << 24);
                canvas.drawRoundRect(lens, lensRadius, lensRadius, paint);
                paint.setColor((Math.round(0x1F * shown) << 24) | 0xFFFFFF);
                canvas.drawRoundRect(lens, lensRadius, lensRadius, paint);

                if (shown > 0.01f) {
                    paint.setStyle(Paint.Style.STROKE);
                    // Colors split along the edge of thick glass: cool on one side, warm on the other.
                    inner.set(lens);
                    inner.inset(-density, -density);
                    int fringe = Math.round(0x8C * shown);
                    paint.setShader(new LinearGradient(inner.left, inner.top, inner.right, inner.bottom,
                            new int[]{(fringe << 24) | 0x4D8DFF, 0x004D8DFF, 0x00FF9A3D, (fringe << 24) | 0xFF9A3D},
                            new float[]{0f, 0.35f, 0.65f, 1f}, Shader.TileMode.CLAMP));
                    paint.setColor(Color.WHITE);
                    paint.setStrokeWidth(1.5f * density);
                    canvas.drawRoundRect(inner, lensRadius + density, lensRadius + density, paint);
                    paint.setShader(null);

                    paint.setStrokeWidth(density);
                    paint.setColor((Math.round(0x99 * shown) << 24) | 0xFFFFFF);
                    canvas.drawRoundRect(lens, lensRadius, lensRadius, paint);
                }
            }
        }

        /**
         * Moves the highlight on for the time that went by and says whether it is still moving.
         *
         * Each edge is on a spring of its own. The edge in front is on a stiffer one than the edge
         * behind, so the highlight stretches while it travels and gathers itself when it arrives.
         * Under a finger the springs are stiff and do not swing, on their own they are softer and
         * swing a little past their goal.
         */
        private boolean animate(float elapsed, boolean up) {
            boolean touched = isTouched();
            float leading = touched ? 1100f : 520f;
            float trailing = touched ? 700f : 300f;
            float damping = touched ? 1f : 0.78f;
            float liftGoal = up ? 1f : 0f;
            float liftDamping = up ? 0.62f : 0.9f;

            float from = vertical ? edge[TOP] + edge[BOTTOM] : edge[LEFT] + edge[RIGHT];
            float to = vertical ? goal[TOP] + goal[BOTTOM] : goal[LEFT] + goal[RIGHT];
            if (Math.abs(to - from) > 2f) forward = to > from;

            int steps = Math.max(1, (int) Math.ceil(elapsed / SPRING_STEP));
            float step = elapsed / steps;
            for (int i = 0; i < steps; i++) {
                for (int side = 0; side < 4; side++) {
                    boolean onAxis = vertical ? side == TOP || side == BOTTOM : side == LEFT || side == RIGHT;
                    float stiffness = !onAxis ? 600f : (side >= RIGHT) == forward ? leading : trailing;
                    float friction = 2f * (onAxis ? damping : 0.9f) * (float) Math.sqrt(stiffness);
                    speed[side] += (-stiffness * (edge[side] - goal[side]) - friction * speed[side]) * step;
                    edge[side] += speed[side] * step;
                }
                float friction = 2f * liftDamping * (float) Math.sqrt(380f);
                liftSpeed += (-380f * (lift - liftGoal) - friction * liftSpeed) * step;
                lift += liftSpeed * step;
            }

            boolean moving = Math.abs(lift - liftGoal) > 0.003f || Math.abs(liftSpeed) > 0.03f;
            for (int side = 0; side < 4; side++) {
                moving |= Math.abs(edge[side] - goal[side]) > 0.25f || Math.abs(speed[side]) > 4f;
            }
            if (!moving) {
                System.arraycopy(goal, 0, edge, 0, 4);
                Arrays.fill(speed, 0f);
                lift = liftGoal;
                liftSpeed = 0f;
            }
            return moving;
        }

        /** Draws the colors of the screen in a ring of the given width along the edge of the pill. */
        private void glow(Canvas canvas, float radius, float width, int alpha) {
            inner.set(pill);
            inner.inset(width, width);
            if (inner.isEmpty()) return;
            float innerRadius = Math.min(inner.width(), inner.height()) / 2f;
            outline.rewind();
            outline.addRoundRect(pill, radius, radius, Path.Direction.CW);
            hole.rewind();
            hole.addRoundRect(inner, innerRadius, innerRadius, Path.Direction.CW);

            // New colors fade in over the old ones.
            float fade = glowOld == null ? 1f
                    : Math.min(1f, (SystemClock.uptimeMillis() - glowSince) / (float) GLOW_FADE_MS);
            canvas.save();
            canvas.clipPath(outline);
            canvas.clipOutPath(hole);
            if (fade < 1f) {
                glowPaint.setAlpha(Math.round(alpha * (1f - fade)));
                canvas.drawBitmap(glowOld, null, pill, glowPaint);
            }
            glowPaint.setAlpha(Math.round(alpha * fade));
            canvas.drawBitmap(glowNew, null, pill, glowPaint);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Blurred copy of what is behind a bar. Needs Android 12. */
    private static final class GlassBlur {
        private final RenderNode node = new RenderNode("piko-glass");
        private final Path path = new Path();
        private final float density;
        private boolean recorded;
        private boolean bendFailed;
        private int effectWidth;
        private int effectHeight;

        GlassBlur(float density) {
            this.density = density;
        }

        private RenderEffect frosted(float radius) {
            ColorMatrix colors = new ColorMatrix();
            colors.setSaturation(1.8f);
            return RenderEffect.createChainEffect(
                    RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(colors)),
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }

        /** The effect depends on the size of the pill, it is made again when that changes. */
        private void prepare(int width, int height) {
            if (width == effectWidth && height == effectHeight) return;
            effectWidth = width;
            effectHeight = height;

            // Android 13 can bend the picture along the edges like a real lens.
            if (Build.VERSION.SDK_INT >= 33 && !bendFailed) {
                try {
                    float radius = Math.min(width, height) / 2f;
                    // The middle of the pill has to stay outside the bending rim.
                    node.setRenderEffect(GlassBend.create(frosted(GLASS_CLEAR_BLUR_DP * density), width, height,
                            radius, Math.min(radius * 0.8f, GLASS_EDGE_DP * density), GLASS_BEND_DP * density));
                    return;
                } catch (Throwable e) {
                    Log.e(TAG, "glass bend failure", e);
                    bendFailed = true;
                }
            }
            node.setRenderEffect(frosted(GLASS_BLUR_DP * density));
        }

        void capture(View behind, float moveX, float moveY, int width, int height, float squeeze) {
            if (width <= 0 || height <= 0) return;
            prepare(width, height);
            node.setPosition(0, 0, width, height);
            RecordingCanvas canvas = node.beginRecording(width, height);
            try {
                canvas.translate(moveX, moveY);
                if (squeeze != 1f) canvas.scale(squeeze, 1f);
                behind.draw(canvas);
            } finally {
                node.endRecording();
            }
            recorded = true;
        }

        /** Draws the part behind the highlight again, enlarged around its center. */
        void lens(Canvas canvas, RectF pill, RectF bubble, float radius, float zoom) {
            if (!recorded) return;
            path.rewind();
            path.addRoundRect(bubble, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(path);
            canvas.scale(zoom, zoom, bubble.centerX(), bubble.centerY());
            canvas.translate(pill.left, pill.top);
            canvas.drawRenderNode(node);
            canvas.restore();
        }

        boolean draw(Canvas canvas, RectF pill, float radius) {
            if (!recorded) return false;
            path.rewind();
            path.addRoundRect(pill, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(path);
            canvas.translate(pill.left, pill.top);
            canvas.drawRenderNode(node);
            canvas.restore();
            return true;
        }
    }

    /** Messages icon that takes the place of the create button of the tab bar. */
    private static final class MessagesButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path icon = new Path();
        private final float density;
        private boolean light;

        MessagesButton(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(Color.WHITE);
            setContentDescription("Messages");

            // Paper plane on a 24 by 24 grid.
            icon.moveTo(11.698f, 20.334f);
            icon.lineTo(22f, 3.001f);
            icon.lineTo(2f, 3.001f);
            icon.lineTo(9.218f, 10.084f);
            icon.close();
            icon.moveTo(22f, 3f);
            icon.lineTo(9.218f, 10.083f);
        }

        void setLight(boolean light) {
            if (this.light == light) return;
            this.light = light;
            paint.setColor(light ? Color.BLACK : Color.WHITE);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float size = 24 * density;
            canvas.save();
            canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
            canvas.scale(density, density);
            paint.setStrokeWidth(2f);
            canvas.drawPath(icon, paint);
            canvas.restore();
        }
    }

    /**
     * Bends what is seen through a pill of glass and lights its rim. Needs Android 13.
     *
     * The shaders are taken from KMPLiquidGlass (https://github.com/Kashif-E/KMPLiquidGlass),
     * a port of AndroidLiquidGlass (https://github.com/Kyant0/AndroidLiquidGlass).
     * Copyright 2025 Kyant, Kashif Mehmood. Licensed under the Apache License, Version 2.0.
     */
    private static final class GlassBend {
        private static final String ROUNDED_RECT =
                "float radiusAt(float2 coord, float4 radii) {\n"
                + "    if (coord.x >= 0.0) {\n"
                + "        if (coord.y <= 0.0) return radii.y;\n"
                + "        else return radii.z;\n"
                + "    } else {\n"
                + "        if (coord.y <= 0.0) return radii.x;\n"
                + "        else return radii.w;\n"
                + "    }\n"
                + "}\n"
                + "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
                + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
                + "    float outside = length(max(cornerCoord, 0.0)) - radius;\n"
                + "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n"
                + "    return outside + inside;\n"
                + "}\n"
                + "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
                + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
                + "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n"
                + "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n"
                + "    } else {\n"
                + "        float gradX = step(cornerCoord.y, cornerCoord.x);\n"
                + "        return sign(coord) * float2(gradX, 1.0 - gradX);\n"
                + "    }\n"
                + "}\n";

        /** Refraction along the rim, with the colors drawn slightly apart like through real glass. */
        private static final String REFRACTION =
                "uniform shader content;\n"
                + "uniform float2 size;\n"
                + "uniform float2 offset;\n"
                + "uniform float4 cornerRadii;\n"
                + "uniform float refractionHeight;\n"
                + "uniform float refractionAmount;\n"
                + "uniform float depthEffect;\n"
                + "uniform float chromaticAberration;\n"
                + ROUNDED_RECT
                + "float circleMap(float x) {\n"
                + "    return 1.0 - sqrt(1.0 - x * x);\n"
                + "}\n"
                + "half4 main(float2 coord) {\n"
                + "    float2 halfSize = size * 0.5;\n"
                + "    float2 centeredCoord = (coord + offset) - halfSize;\n"
                + "    float radius = radiusAt(coord, cornerRadii);\n"
                + "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n"
                + "    if (-sd >= refractionHeight) {\n"
                + "        return content.eval(coord);\n"
                + "    }\n"
                + "    sd = min(sd, 0.0);\n"
                + "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n"
                + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
                + "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius)"
                + " + depthEffect * normalize(centeredCoord));\n"
                + "    float2 refractedCoord = coord + d * grad;\n"
                + "    float dispersionIntensity = chromaticAberration"
                + " * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));\n"
                + "    float2 dispersedCoord = d * grad * dispersionIntensity;\n"
                + "    half4 color = half4(0.0);\n"
                + "    half4 red = content.eval(refractedCoord + dispersedCoord);\n"
                + "    color.r += red.r / 3.5;\n"
                + "    color.a += red.a / 7.0;\n"
                + "    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));\n"
                + "    color.r += orange.r / 3.5;\n"
                + "    color.g += orange.g / 7.0;\n"
                + "    color.a += orange.a / 7.0;\n"
                + "    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));\n"
                + "    color.r += yellow.r / 3.5;\n"
                + "    color.g += yellow.g / 3.5;\n"
                + "    color.a += yellow.a / 7.0;\n"
                + "    half4 green = content.eval(refractedCoord);\n"
                + "    color.g += green.g / 3.5;\n"
                + "    color.a += green.a / 7.0;\n"
                + "    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));\n"
                + "    color.g += cyan.g / 3.5;\n"
                + "    color.b += cyan.b / 3.0;\n"
                + "    color.a += cyan.a / 7.0;\n"
                + "    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));\n"
                + "    color.b += blue.b / 3.0;\n"
                + "    color.a += blue.a / 7.0;\n"
                + "    half4 purple = content.eval(refractedCoord - dispersedCoord);\n"
                + "    color.r += purple.r / 7.0;\n"
                + "    color.b += purple.b / 3.0;\n"
                + "    color.a += purple.a / 7.0;\n"
                + "    return color;\n"
                + "}\n";

        /** Light on the parts of the outline that face it. */
        private static final String HIGHLIGHT =
                "uniform float2 size;\n"
                + "uniform float4 cornerRadii;\n"
                + "layout(color) uniform half4 color;\n"
                + "uniform float angle;\n"
                + "uniform float falloff;\n"
                + ROUNDED_RECT
                + "half4 main(float2 coord) {\n"
                + "    float2 halfSize = size * 0.5;\n"
                + "    float2 centeredCoord = coord - halfSize;\n"
                + "    float radius = radiusAt(coord, cornerRadii);\n"
                + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
                + "    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);\n"
                + "    float2 normal = float2(cos(angle), sin(angle));\n"
                + "    float d = dot(grad, normal);\n"
                + "    float intensity = pow(abs(d), falloff);\n"
                + "    return color * intensity;\n"
                + "}\n";

        static RenderEffect create(RenderEffect behind, int width, int height, float radius, float edge, float bend) {
            RuntimeShader shader = new RuntimeShader(REFRACTION);
            shader.setFloatUniform("size", width, height);
            shader.setFloatUniform("offset", 0f, 0f);
            shader.setFloatUniform("cornerRadii", radius, radius, radius, radius);
            shader.setFloatUniform("refractionHeight", Math.max(1f, edge));
            // Negative: the rim shows what lies further in.
            shader.setFloatUniform("refractionAmount", -bend);
            shader.setFloatUniform("depthEffect", 0.5f);
            shader.setFloatUniform("chromaticAberration", 0.35f);
            return RenderEffect.createChainEffect(
                    RenderEffect.createRuntimeShaderEffect(shader, "content"), behind);
        }

        static Shader highlight(RectF pill) {
            float radius = Math.min(pill.width(), pill.height()) / 2f;
            RuntimeShader shader = new RuntimeShader(HIGHLIGHT);
            shader.setFloatUniform("size", pill.width(), pill.height());
            shader.setFloatUniform("cornerRadii", radius, radius, radius, radius);
            shader.setColorUniform("color", 0x66FFFFFF);
            shader.setFloatUniform("angle", (float) Math.toRadians(45));
            // Only the parts that face the light squarely catch it.
            shader.setFloatUniform("falloff", 2.5f);
            Matrix place = new Matrix();
            place.setTranslate(pill.left, pill.top);
            shader.setLocalMatrix(place);
            return shader;
        }
    }
}
