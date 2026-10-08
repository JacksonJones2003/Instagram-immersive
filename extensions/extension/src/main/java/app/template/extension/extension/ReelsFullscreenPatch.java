package app.template.extension.extension;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
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
import java.util.ArrayList;
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
    private static final int MAX_BUTTONS_DEPTH = 4;
    private static final int MIN_BUTTONS = 3;
    private static final int GLASS_BLUR_DP = 20;
    private static final String MESSAGES_LINK = "instagram://direct-inbox";
    // Line Instagram draws along the top of the tab bar.
    private static final String TAB_BAR_LINE_ID = "tab_bar_shadow";
    // How much the button under the finger grows while sliding over the tab bar.
    private static final float HOVER_SCALE = 1.18f;
    // The tab bar also takes touches this far above itself.
    private static final int BAR_TOUCH_EXTRA_DP = 16;
    private static final int MAX_LIFT_DEPTH = 8;
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
        private BarTouch barTouch;
        private WeakReference<View> lastTabBarLine = new WeakReference<>(null);
        private boolean hovering;
        private int barTouchExtra;
        /** Parts of a reel that were moved above the floating tab bar, with their last pass. */
        private final Map<View, Integer> lifted = new WeakHashMap<>();
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
            railShown = seamScrubber && navigationRailShown(decor);
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
                    if (tabGlass.floating) {
                        floatLift = tabBar.getHeight();
                        insetLists(content, tabBar);
                    } else {
                        restoreLists();
                    }

                    View line = lookup(decor, tabBarLineId, lastTabBarLine);
                    if (line != null) {
                        if (line != lastTabBarLine.get()) lastTabBarLine = new WeakReference<>(line);
                        line.setAlpha(0f);
                    }
                    placeBarTouch(tabBar);
                    hover(tabBar);
                } else {
                    restoreLists();
                    if (barTouch != null) barTouch.setVisibility(View.GONE);
                }

                // Nothing runs underneath the rail, its glass shows the colors of the screen next to it.
                View rail = findNavigationRail(decor);
                if (rail != null && rail.isShown() && rail.getWidth() > 0) {
                    if (railGlass == null) railGlass = new GlassBar(activity);
                    styleBar(railGlass, rail, content, true);
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

        /** Puts the view that takes the touches of the tab bar on top of it. */
        private void placeBarTouch(View tabBar) {
            if (!(tabBar.getParent() instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) tabBar.getParent();
            if (barTouch == null) barTouch = new BarTouch(activity, this);
            if (barTouch.getParent() != parent) {
                if (barTouch.getParent() instanceof ViewGroup) ((ViewGroup) barTouch.getParent()).removeView(barTouch);
                parent.addView(barTouch, new ViewGroup.LayoutParams(tabBar.getWidth(), tabBar.getHeight()));
            } else if (parent.getChildAt(parent.getChildCount() - 1) != barTouch) {
                barTouch.bringToFront();
            }
            barTouchExtra = Math.round(BAR_TOUCH_EXTRA_DP * activity.getResources().getDisplayMetrics().density);
            ViewGroup.LayoutParams params = barTouch.getLayoutParams();
            if (params.width != tabBar.getWidth() || params.height != tabBar.getHeight() + barTouchExtra) {
                params.width = tabBar.getWidth();
                params.height = tabBar.getHeight() + barTouchExtra;
                barTouch.setLayoutParams(params);
            }
            barTouch.setTranslationX(tabBar.getLeft() + tabBar.getTranslationX());
            barTouch.setTranslationY(tabBar.getTop() + tabBar.getTranslationY() - barTouchExtra);
            barTouch.setVisibility(View.VISIBLE);
        }

        /** Button of the tab bar at a horizontal position inside it. */
        private View buttonAt(View tabBar, float x) {
            ViewGroup buttons = findButtons(tabBar);
            if (buttons == null) return null;
            float left = 0;
            for (View view = buttons; view != tabBar; view = (View) view.getParent()) left += view.getLeft();
            for (int i = 0; i < buttons.getChildCount(); i++) {
                View child = buttons.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                if (x >= left + child.getLeft() && x < left + child.getRight()) return child;
            }
            return null;
        }

        private boolean isCreateButton(View tabBar, View button) {
            ViewGroup buttons = messagesTab ? findButtons(tabBar) : null;
            return buttons != null && button != null && findCreateButton(buttons, true) == button;
        }

        /** The button under a sliding finger grows, like under a lens. */
        private void hover(View tabBar) {
            boolean dragging = tabGlass != null && tabGlass.dragging;
            if (!dragging && !hovering) return;
            hovering = dragging;

            ViewGroup buttons = findButtons(tabBar);
            if (buttons == null) return;
            View under = dragging ? buttonAt(tabBar, tabGlass.dragX) : null;
            for (int i = 0; i < buttons.getChildCount(); i++) {
                View child = buttons.getChildAt(i);
                float scale = child == under ? HOVER_SCALE : 1f;
                child.setScaleX(scale);
                child.setScaleY(scale);
            }
            if (messagesView != null) {
                float scale = under != null && isCreateButton(tabBar, under) ? HOVER_SCALE : 1f;
                messagesView.setScaleX(scale);
                messagesView.setScaleY(scale);
            }
        }

        void barDrag(float x) {
            if (tabGlass != null) tabGlass.drag(x);
        }

        void barRelease(float x, boolean select) {
            if (tabGlass != null) tabGlass.release();
            if (select) barTap(x);
        }

        /** Presses the button at a position the way a finger would. */
        void barTap(float x) {
            try {
                View tabBar = lastTabBar.get();
                View button = tabBar == null ? null : buttonAt(tabBar, x);
                if (button == null) return;
                if (messagesView != null && messagesView.getVisibility() == View.VISIBLE
                        && isCreateButton(tabBar, button)) {
                    openMessages();
                    return;
                }

                // Most buttons take a plain click, the others get a touch in their middle.
                if (button.performClick()) return;

                float centerX = button.getWidth() / 2f;
                float centerY = button.getHeight() / 2f;
                for (View view = button; view != tabBar; view = (View) view.getParent()) {
                    centerX += view.getLeft();
                    centerY += view.getTop();
                }
                long now = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, centerX, centerY, 0);
                MotionEvent up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, centerX, centerY, 0);
                down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try {
                    tabBar.dispatchTouchEvent(down);
                    tabBar.dispatchTouchEvent(up);
                } finally {
                    down.recycle();
                    up.recycle();
                }
            } catch (Exception e) {
                Log.e(TAG, "tab press failure", e);
            }
        }

        /** Hands a touch on to Instagram's tab bar unchanged, used for long presses. */
        void barForward(MotionEvent event) {
            View tabBar = lastTabBar.get();
            if (tabBar == null) return;
            MotionEvent copy = MotionEvent.obtain(event);
            // The touch area starts above the tab bar.
            copy.offsetLocation(0, -barTouchExtra);
            try {
                tabBar.dispatchTouchEvent(copy);
            } catch (Exception e) {
                Log.e(TAG, "tab touch failure", e);
            } finally {
                copy.recycle();
            }
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
                View create = buttons == null ? null : findCreateButton(buttons, true);
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
                messagesView.setTranslationX(left);
                messagesView.setTranslationY(top);
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
            }

            ViewGroup buttons = findButtons(bar);
            if (buttons == null) {
                glass.clear();
                return;
            }
            float left = 0;
            float top = 0;
            for (View view = buttons; view != bar; view = (View) view.getParent()) {
                left += view.getLeft();
                top += view.getTop();
            }
            glass.layout(bar, buttons, left, top);

            if (behind == null || blurFailed || Build.VERSION.SDK_INT < 31 || glass.pill.isEmpty()) {
                glass.blur = null;
                return;
            }
            try {
                if (glass.blur == null) {
                    glass.blur = new GlassBlur(GLASS_BLUR_DP * activity.getResources().getDisplayMetrics().density);
                }
                int[] barLocation = new int[2];
                int[] behindLocation = new int[2];
                bar.getLocationInWindow(barLocation);
                behind.getLocationInWindow(behindLocation);
                // Next to the screen instead of over it: its whole width is squeezed into the pill,
                // every row of the pill takes its colors from the row of the screen beside it.
                float squeeze = ambient && behind.getWidth() > 0 ? glass.pill.width() / behind.getWidth() : 1f;
                ((GlassBlur) glass.blur).capture(behind,
                        ambient ? 0 : behindLocation[0] - barLocation[0] - glass.pill.left,
                        behindLocation[1] - barLocation[1] - glass.pill.top,
                        Math.round(glass.pill.width()), Math.round(glass.pill.height()), squeeze);
            } catch (Throwable e) {
                // Not every device or view can be recorded like this, go without blur then.
                Log.e(TAG, "blur failure", e);
                blurFailed = true;
                glass.blur = null;
            }
        }

        /** The buttons of a bar are the first group of at least three views inside it. */
        private ViewGroup findButtons(View bar) {
            if (!(bar instanceof ViewGroup)) return null;
            List<ViewGroup> level = new ArrayList<>();
            level.add((ViewGroup) bar);
            for (int depth = 0; depth < MAX_BUTTONS_DEPTH && !level.isEmpty(); depth++) {
                List<ViewGroup> next = new ArrayList<>();
                for (ViewGroup group : level) {
                    int shown = 0;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        View child = group.getChildAt(i);
                        if (child.getVisibility() != View.VISIBLE || child.getWidth() == 0) continue;
                        shown++;
                        if (child instanceof ViewGroup) next.add((ViewGroup) child);
                    }
                    if (shown >= MIN_BUTTONS) return group;
                }
                level = next;
            }
            return null;
        }

        /**
         * A reel runs underneath the floating tab bar. Everything in the lower part of the page
         * that is not the video itself, like the caption and the buttons, moves up above the bar.
         */
        private void lift(ViewGroup content) {
            float pageScale = content.getScaleY();
            if (pageScale <= 0 || content.getHeight() == 0) return;
            liftChildren(content, 0, content.getHeight(), -floatLift / pageScale, 0);
        }

        private void liftChildren(ViewGroup group, float groupTop, int pageHeight, float shift, int depth) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getHeight() == 0) continue;
                // Expanded photos size and place their own parts.
                if (child.getScaleY() != 1f) continue;

                float top = groupTop + child.getTop();
                float bottom = top + child.getHeight();
                // Tall views and views that start high up hold the video or other parts.
                boolean holder = child.getHeight() > 0.6f * pageHeight || top < 0.4f * pageHeight;
                if (!holder) {
                    child.setTranslationY(shift);
                    lifted.put(child, pass);
                } else if (child instanceof ViewGroup && depth < MAX_LIFT_DEPTH && bottom > pageHeight - floatLift) {
                    liftChildren((ViewGroup) child, top, pageHeight, shift, depth + 1);
                }
            }
        }

        /** Lists end underneath the floating tab bar, they get room to scroll their end clear of it. */
        private void insetLists(View content, View tabBar) {
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
                        if (child.getPaddingBottom() != own + tabBar.getHeight()) {
                            child.setPadding(child.getPaddingLeft(), child.getPaddingTop(),
                                    child.getPaddingRight(), own + tabBar.getHeight());
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
            View root = decor.findViewById(android.R.id.content);
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
            // Spacing Instagram keeps below the reel pages.
            if (reels.getPaddingBottom() > 0) {
                rememberPadding(reels, false, true);
                reels.setPadding(reels.getPaddingLeft(), reels.getPaddingTop(), reels.getPaddingRight(), 0);
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
                if (floatLift > 0) lift((ViewGroup) content);
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

            View root = decor.findViewById(android.R.id.content);
            if (!(root instanceof ViewGroup)) return;
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

            for (Iterator<Map.Entry<View, Integer>> iterator = lifted.entrySet().iterator(); iterator.hasNext(); ) {
                Map.Entry<View, Integer> entry = iterator.next();
                if (!all && entry.getValue() == pass) continue;
                if (entry.getKey() != null) entry.getKey().setTranslationY(0f);
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
     * Background of a bar: one rounded pill around all of its buttons with a highlight that
     * glides to the selected button.
     */
    private static final class GlassBar extends Drawable {
        final RectF pill = new RectF();
        private final RectF target = new RectF();
        private final RectF bubble = new RectF();
        private final RectF next = new RectF();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outline = new Path();
        private final float density;
        private Shader sheen;
        private Shader rim;
        private boolean hasTarget;
        private boolean bubblePlaced;
        boolean light;
        boolean floating;
        boolean dragging;
        float dragX;
        /** A GlassBlur, only on Android versions that have it. */
        Object blur;

        GlassBar(Context context) {
            density = context.getResources().getDisplayMetrics().density;
        }

        void drag(float x) {
            dragging = true;
            dragX = x;
            invalidateSelf();
        }

        void release() {
            dragging = false;
            invalidateSelf();
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
            for (int i = 0; i < buttons.getChildCount(); i++) {
                View child = buttons.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getWidth() == 0) continue;
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

            boolean horizontal = right - left > bottom - top;
            if (horizontal) {
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
                        new int[]{0x47FFFFFF, 0x0DFFFFFF, 0x00FFFFFF, 0x1AFFFFFF},
                        new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP);
                rim = new LinearGradient(0, pill.top, 0, pill.bottom,
                        new int[]{0xB3FFFFFF, 0x26FFFFFF, 0x66FFFFFF},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            }

            boolean hadTarget = hasTarget;
            hasTarget = selected != null || (dragging && horizontal);
            if (dragging && horizontal) {
                // The highlight follows the finger instead of the selected button.
                float inset = 4 * density;
                float width = selected != null ? selected.getWidth() - 2 * inset : pill.width() / 5f;
                float center = Math.max(pill.left + inset + width / 2f,
                        Math.min(pill.right - inset - width / 2f, dragX));
                next.set(center - width / 2f, pill.top + inset, center + width / 2f, pill.bottom - inset);
                changed |= !next.equals(target);
                target.set(next);
                if (!bubblePlaced) {
                    bubble.set(target);
                    bubblePlaced = true;
                }
            } else if (hasTarget) {
                float inset = 4 * density;
                if (horizontal) {
                    next.set(offsetX + selected.getLeft() + inset, pill.top + inset,
                            offsetX + selected.getRight() - inset, pill.bottom - inset);
                } else {
                    next.set(pill.left + inset, offsetY + selected.getTop() + inset / 2,
                            pill.right - inset, offsetY + selected.getBottom() - inset / 2);
                }
                next.left = Math.max(next.left, pill.left + inset);
                next.right = Math.min(next.right, pill.right - inset);
                changed |= !next.equals(target);
                target.set(next);
                if (!bubblePlaced) {
                    bubble.set(target);
                    bubblePlaced = true;
                }
            }
            if (changed || hadTarget != hasTarget) invalidateSelf();
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
                shadowPaint.setShadowLayer(10 * density, 0, 3 * density, 0x59000000);
                canvas.drawRoundRect(pill, radius, radius, shadowPaint);
                canvas.restore();
            }

            boolean blurred = false;
            if (blur != null && Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()) {
                blurred = ((GlassBlur) blur).draw(canvas, pill, radius);
            }

            // Thin tint, the blur and the highlights are what make it read as glass.
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            if (blurred) {
                paint.setColor(light ? 0x38FFFFFF : 0x2E000000);
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

            if (hasTarget) {
                // Glide a part of the remaining way on every frame.
                float moved = glide();
                float bubbleRadius = Math.min(bubble.width(), bubble.height()) / 2f;
                // The highlight is a lens: what is behind the bar shows through it enlarged.
                if (blurred) {
                    ((GlassBlur) blur).lens(canvas, pill, bubble, bubbleRadius, dragging ? 1.35f : 1.12f);
                }
                if (dragging) {
                    paint.setColor(light ? 0x2E000000 : 0x4DFFFFFF);
                } else {
                    paint.setColor(light ? 0x24000000 : 0x38FFFFFF);
                }
                canvas.drawRoundRect(bubble, bubbleRadius, bubbleRadius, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth((dragging ? 1.5f : 1f) * density);
                paint.setColor(dragging ? 0x99FFFFFF : 0x59FFFFFF);
                canvas.drawRoundRect(bubble, bubbleRadius, bubbleRadius, paint);
                if (moved > 0.5f) invalidateSelf();
            }

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.25f * density);
            paint.setColor(Color.WHITE);
            paint.setShader(rim);
            canvas.drawRoundRect(pill, radius, radius, paint);
            paint.setShader(null);
        }

        private float glide() {
            // Stay close to a finger, ease over to a tapped button.
            float pace = dragging ? 0.55f : 0.3f;
            float left = (target.left - bubble.left) * pace;
            float top = (target.top - bubble.top) * pace;
            float right = (target.right - bubble.right) * pace;
            float bottom = (target.bottom - bubble.bottom) * pace;
            float moved = Math.abs(left) + Math.abs(top) + Math.abs(right) + Math.abs(bottom);
            if (moved <= 0.5f) {
                bubble.set(target);
            } else {
                bubble.set(bubble.left + left, bubble.top + top, bubble.right + right, bubble.bottom + bottom);
            }
            return moved;
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
        private boolean recorded;

        GlassBlur(float radius) {
            ColorMatrix colors = new ColorMatrix();
            colors.setSaturation(1.8f);
            node.setRenderEffect(RenderEffect.createChainEffect(
                    RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(colors)),
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)));
        }

        void capture(View behind, float moveX, float moveY, int width, int height, float squeeze) {
            if (width <= 0 || height <= 0) return;
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
     * Sits on top of the tab bar and takes its touches: a tap presses the button under it,
     * a slide moves the highlight along with the finger and presses the button it is let go on.
     */
    private static final class BarTouch extends View {
        private final State state;
        private final int slop;
        private float downX;
        private boolean dragging;
        private boolean forwarding;
        private MotionEvent downEvent;
        private final Runnable longPress = () -> {
            // Instagram has its own long presses, on the profile button for example.
            if (dragging || downEvent == null) return;
            forwarding = true;
            BarTouch.this.state.barForward(downEvent);
        };

        BarTouch(Context context, State state) {
            super(context);
            this.state = state;
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = x;
                    dragging = false;
                    forwarding = false;
                    if (downEvent != null) downEvent.recycle();
                    downEvent = MotionEvent.obtain(event);
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (forwarding) {
                        state.barForward(event);
                        return true;
                    }
                    if (!dragging && Math.abs(x - downX) > slop) {
                        dragging = true;
                        removeCallbacks(longPress);
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) state.barDrag(x);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress);
                    boolean lifted = event.getActionMasked() == MotionEvent.ACTION_UP;
                    if (forwarding) {
                        state.barForward(event);
                    } else if (dragging) {
                        state.barRelease(x, lifted);
                    } else if (lifted) {
                        state.barTap(x);
                    }
                    dragging = false;
                    forwarding = false;
                    return true;
                default:
                    return true;
            }
        }
    }
}
