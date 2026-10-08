package app.template.extension.extension;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Toast;

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
    // Stop adjusting when Instagram keeps putting its spacing back on every layout pass.
    private static final int MAX_CHURN = 50;
    private static final int MAX_DUMP_DEPTH = 9;
    private static final int MAX_DUMP_LINES = 300;
    private static final long DUMP_DELAY_MS = 3000;

    private static final Map<Activity, State> STATES = new WeakHashMap<>();
    private static boolean registered;
    private static boolean hideTabBar;
    private static boolean debug;

    private ReelsFullscreenPatch() {
    }

    /** Injection point. Added by the "Hide tab bar in Reels" patch. */
    public static void enableHideTabBar() {
        hideTabBar = true;
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
            window.getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(state);
        } catch (Exception e) {
            Log.e(TAG, "attach failure", e);
        }
    }

    private static final class State implements ViewTreeObserver.OnGlobalLayoutListener {
        private final Activity activity;
        private final int[] reelsViewIds;
        private final int tabBarId;
        private final int navigationRailId;

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

        private boolean applying;
        private boolean active;
        private boolean dumped;
        private boolean tabBarHidden;
        private int tabBarHeight;
        /** Height of the Reels view before it was expanded. */
        private int baseHeight;

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
            View reels = findReelsView(decor);

            if (reels == null) {
                if (active) restore(decor);
                return;
            }

            WindowInsets insets = decor.getRootWindowInsets();
            if (insets == null) return;
            int top = insets.getStableInsetTop();
            int bottom = insets.getStableInsetBottom();

            View tabBar = tabBarId == 0 ? null : decor.findViewById(tabBarId);
            if (tabBar != null && tabBar.getVisibility() == View.VISIBLE && tabBar.getHeight() > 0) {
                tabBarHeight = tabBar.getHeight();
            }

            if (!active) {
                active = true;
                baseHeight = reels.getHeight();
            }
            // Folding, unfolding and rotating make Instagram apply its spacing again,
            // so everything is checked on every pass instead of once.
            if (navigationRailShown(decor)) tabBarHidden = false;
            drawBehindStatusBar(decor);
            if (hideTabBar) hideTabBar(tabBar);
            if (churn <= MAX_CHURN) {
                changed = false;
                expand(reels, decor, top, bottom);
                churn = changed ? churn + 1 : 0;
            }
            fill(reels);

            if (!dumped && debug) {
                dumped = true;
                final View dumpReels = reels;
                final View dumpDecor = decor;
                final int dumpTop = top;
                final int dumpBottom = bottom;
                // Wait for the expanded layout to settle.
                decor.postDelayed(() -> {
                    try {
                        dump(dumpReels, dumpDecor, dumpTop, dumpBottom);
                    } catch (Exception e) {
                        Log.e(TAG, "dump failure", e);
                    }
                }, DUMP_DELAY_MS);
            }
        }

        private View findReelsView(View decor) {
            for (int id : reelsViewIds) {
                if (id == 0) continue;
                View view = decor.findViewById(id);
                if (view != null && view.isShown() && view.getWidth() > 0) return view;
            }
            return null;
        }

        private boolean navigationRailShown(View decor) {
            View rail = navigationRailId == 0 ? null : decor.findViewById(navigationRailId);
            return rail != null && rail.isShown() && rail.getWidth() > 0;
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
            for (int i = 0; i < pages.getChildCount(); i++) {
                View content = findContent(pages.getChildAt(i), 0);
                if (content instanceof ViewGroup) scale((ViewGroup) content);
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

            if (addedUiFlags != 0) {
                decor.setSystemUiVisibility(decor.getSystemUiVisibility() & ~addedUiFlags);
                addedUiFlags = 0;
            }
            if (originalStatusBarColor != null) {
                activity.getWindow().setStatusBarColor(originalStatusBarColor);
                originalStatusBarColor = null;
            }

            showTabBar(decor);
            baseHeight = 0;
            churn = 0;
            active = false;
            dumped = false;
            // The remembered spacing can be from before a fold or rotation, let Instagram redo it.
            decor.requestApplyInsets();
        }

        /**
         * Describes the views around and inside the Reels view, logs it and copies it to the
         * clipboard. Useful when a new Instagram version or screen layout moves things around.
         */
        private void dump(View reels, View decor, int top, int bottom) {
            StringBuilder builder = new StringBuilder();
            builder.append("insets top=").append(top).append(" bottom=").append(bottom)
                    .append(" tabBarHeight=").append(tabBarHeight)
                    .append(" tabBarHidden=").append(tabBarHidden)
                    .append(" baseHeight=").append(baseHeight)
                    .append(" decor=").append(decor.getWidth()).append('x').append(decor.getHeight())
                    .append('\n');

            builder.append("== ancestors and their children\n");
            View view = reels;
            while (view != null) {
                describe(builder, view, "");
                if (view != reels && view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        describe(builder, group.getChildAt(i), "    - ");
                    }
                }
                if (view == decor || !(view.getParent() instanceof View)) break;
                view = (View) view.getParent();
            }

            builder.append("== inside reels view\n");
            int[] lines = {0};
            describeTree(builder, reels, 0, lines);

            String text = builder.toString();
            Log.d(TAG, text);

            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Reels layout", text));
                Toast.makeText(activity, "Reels layout copied to clipboard", Toast.LENGTH_LONG).show();
            }
        }

        private void describeTree(StringBuilder builder, View view, int depth, int[] lines) {
            if (lines[0]++ >= MAX_DUMP_LINES) return;
            StringBuilder indent = new StringBuilder();
            for (int i = 0; i < depth; i++) indent.append("  ");
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
                    .append(" size=").append(view.getWidth()).append('x').append(view.getHeight())
                    .append(" pad=").append(view.getPaddingTop()).append('/').append(view.getPaddingBottom());
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null) {
                builder.append(" lp=").append(params.width).append('x').append(params.height);
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                    builder.append(" margin=").append(margins.topMargin).append('/').append(margins.bottomMargin);
                }
            }
            if (view.getTranslationY() != 0 || view.getScaleY() != 1) {
                builder.append(" ty=").append(view.getTranslationY()).append(" sy=").append(view.getScaleY());
            }
            builder.append('\n');
        }
    }
}
