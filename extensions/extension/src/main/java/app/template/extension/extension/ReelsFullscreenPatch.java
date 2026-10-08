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
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
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
                View tabBar = lookup(decor, tabBarId, lastTabBar);
                if (tabBar != null && tabBar != lastTabBar.get()) lastTabBar = new WeakReference<>(tabBar);
                if (tabBar != null && tabBar.isShown() && tabBar.getWidth() > 0 && tabBar.getHeight() > 0) {
                    View content = lookup(decor, contentId, lastContent);
                    if (content != null && content != lastContent.get()) lastContent = new WeakReference<>(content);
                    if (tabGlass == null) tabGlass = new GlassBar(activity);
                    floatContent(content, tabBar, decor);
                    styleBar(tabGlass, tabBar, tabGlass.floating ? content : null);
                    if (tabGlass.floating) floatLift = tabBar.getHeight();
                }

                View rail = findNavigationRail(decor);
                if (rail != null && rail.isShown() && rail.getWidth() > 0) {
                    if (railGlass == null) railGlass = new GlassBar(activity);
                    styleBar(railGlass, rail, null);
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

        private void styleBar(GlassBar glass, View bar, View behind) {
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
                ((GlassBlur) glass.blur).capture(behind,
                        behindLocation[0] - barLocation[0] - glass.pill.left,
                        behindLocation[1] - barLocation[1] - glass.pill.top,
                        Math.round(glass.pill.width()), Math.round(glass.pill.height()));
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

        /** A reel runs underneath the floating tab bar, its caption and buttons stay above it. */
        private void lift(ViewGroup content) {
            float pageScale = content.getScaleY();
            if (pageScale <= 0) return;
            float shift = -floatLift / pageScale;

            View card = findCard(content);
            if (card instanceof ViewGroup) {
                ViewGroup cardGroup = (ViewGroup) card;
                View mediaBranch = childContaining(cardGroup, content.findViewById(mediaId));
                for (int i = 0; i < cardGroup.getChildCount(); i++) {
                    View child = cardGroup.getChildAt(i);
                    Integer seen = enlarged.get(child);
                    // Expanded photos already place these.
                    if (child == mediaBranch || (seen != null && seen == pass)) continue;
                    child.setTranslationY(shift);
                    enlarged.put(child, pass);
                }
            }
            View buttons = buttonsId == 0 ? null : content.findViewById(buttonsId);
            if (buttons != null && buttons.getParent() == content) {
                buttons.setTranslationY(shift);
                enlarged.put(buttons, pass);
            }
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
        private final float density;
        private boolean hasTarget;
        private boolean bubblePlaced;
        boolean light;
        boolean floating;
        /** A GlassBlur, only on Android versions that have it. */
        Object blur;

        GlassBar(Context context) {
            density = context.getResources().getDisplayMetrics().density;
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
                next.set(left + 12 * density, top + 5 * density, right - 12 * density, bottom - 5 * density);
            } else {
                float center = (left + right) / 2f;
                float half = Math.max(26 * density, (right - left) / 2f - 10 * density);
                half = Math.min(half, bar.getWidth() / 2f - 4 * density);
                next.set(center - half, top - 8 * density, center + half, bottom + 8 * density);
            }
            boolean changed = !next.equals(pill);
            pill.set(next);

            boolean hadTarget = hasTarget;
            hasTarget = selected != null;
            if (hasTarget) {
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

            boolean blurred = false;
            if (blur != null && Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()) {
                blurred = ((GlassBlur) blur).draw(canvas, pill, radius);
            }

            paint.setStyle(Paint.Style.FILL);
            if (blurred) {
                paint.setColor(light ? 0x73FFFFFF : 0x59141414);
            } else if (floating) {
                paint.setColor(light ? 0xE6FFFFFF : 0xE61C1C1E);
            } else {
                paint.setColor(light ? 0x14000000 : 0x1FFFFFFF);
            }
            canvas.drawRoundRect(pill, radius, radius, paint);

            if (hasTarget) {
                // Glide a part of the remaining way on every frame.
                float moved = glide();
                paint.setColor(light ? 0x1F000000 : 0x33FFFFFF);
                float bubbleRadius = Math.min(bubble.width(), bubble.height()) / 2f;
                canvas.drawRoundRect(bubble, bubbleRadius, bubbleRadius, paint);
                if (moved > 0.5f) invalidateSelf();
            }

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(density);
            paint.setColor(light ? 0x66FFFFFF : 0x33FFFFFF);
            canvas.drawRoundRect(pill, radius, radius, paint);
        }

        private float glide() {
            float left = (target.left - bubble.left) * 0.3f;
            float top = (target.top - bubble.top) * 0.3f;
            float right = (target.right - bubble.right) * 0.3f;
            float bottom = (target.bottom - bubble.bottom) * 0.3f;
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
            node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }

        void capture(View behind, float moveX, float moveY, int width, int height) {
            if (width <= 0 || height <= 0) return;
            node.setPosition(0, 0, width, height);
            RecordingCanvas canvas = node.beginRecording(width, height);
            try {
                canvas.translate(moveX, moveY);
                behind.draw(canvas);
            } finally {
                node.endRecording();
            }
            recorded = true;
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
}
