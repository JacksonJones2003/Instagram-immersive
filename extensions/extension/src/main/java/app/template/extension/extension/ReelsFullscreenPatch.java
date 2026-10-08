package app.template.extension.extension;

import android.app.Activity;
import android.app.Application;
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

import java.util.ArrayList;
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

    private static final int EDGE_TO_EDGE_FLAGS =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
    // Insets are whole pixels, allow for rounding done by Instagram.
    private static final int TOLERANCE_PX = 2;

    private static final Map<Activity, State> STATES = new WeakHashMap<>();
    private static boolean registered;
    private static boolean hideTabBar;

    private ReelsFullscreenPatch() {
    }

    /** Injection point. Added by the "Hide tab bar in Reels" patch. */
    public static void enableHideTabBar() {
        hideTabBar = true;
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

        /** Undo actions for everything changed while Reels is on screen. */
        private final List<Runnable> undo = new ArrayList<>();
        private final Map<View, Boolean> adjusted = new WeakHashMap<>();

        private boolean applying;
        private boolean active;
        private boolean dumped;
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
                drawBehindStatusBar(decor);
            }
            if (hideTabBar) hideTabBar(tabBar);
            expand(reels, decor, top, bottom);

            if (!dumped) {
                dumped = true;
                dump(reels, decor, top, bottom);
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

        private void drawBehindStatusBar(View decor) {
            final Window window = activity.getWindow();

            final int visibility = decor.getSystemUiVisibility();
            if ((visibility & EDGE_TO_EDGE_FLAGS) != EDGE_TO_EDGE_FLAGS) {
                decor.setSystemUiVisibility(visibility | EDGE_TO_EDGE_FLAGS);
                undo.add(() -> {
                    View view = window.getDecorView();
                    int missing = EDGE_TO_EDGE_FLAGS & ~visibility;
                    view.setSystemUiVisibility(view.getSystemUiVisibility() & ~missing);
                });
            }

            final int color = window.getStatusBarColor();
            if (color != Color.TRANSPARENT) {
                window.setStatusBarColor(Color.TRANSPARENT);
                undo.add(() -> window.setStatusBarColor(color));
            }
        }

        private void hideTabBar(View tabBar) {
            if (tabBar == null || tabBar.getVisibility() != View.VISIBLE) return;
            tabBar.setVisibility(View.GONE);
            tabBarHidden = true;
        }

        private void showTabBar(View tabBar) {
            // Only undo our own change, Instagram hides the tab bar by itself at times.
            if (tabBarHidden && tabBar != null && tabBar.getVisibility() == View.GONE) {
                tabBar.setVisibility(View.VISIBLE);
            }
            tabBarHidden = false;
        }

        /**
         * Walks from the Reels view up to the decor view and removes the padding and margins
         * that keep it clear of the status bar, the navigation bar and the tab bar.
         */
        private void expand(View reels, View decor, int top, int bottom) {
            View child = reels;
            while (child != decor) {
                ViewParent viewParent = child.getParent();
                if (!(viewParent instanceof ViewGroup)) break;
                ViewGroup parent = (ViewGroup) viewParent;

                if (!adjusted.containsKey(child)) {
                    boolean changed = removeMargins(child, top, bottom);
                    changed |= removePadding(parent, child, top, bottom);
                    if (changed) adjusted.put(child, Boolean.TRUE);
                }
                child = parent;
            }
        }

        private boolean removeMargins(final View view, int top, int bottom) {
            if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return false;
            final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            final int topMargin = params.topMargin;
            final int bottomMargin = params.bottomMargin;

            boolean removeTop = isTopInset(topMargin, top);
            boolean removeBottom = isBottomInset(bottomMargin, bottom);
            if (!removeTop && !removeBottom) return false;

            if (removeTop) params.topMargin = 0;
            if (removeBottom) params.bottomMargin = 0;
            view.setLayoutParams(params);

            undo.add(() -> {
                params.topMargin = topMargin;
                params.bottomMargin = bottomMargin;
                view.setLayoutParams(params);
            });
            return true;
        }

        private boolean removePadding(final ViewGroup parent, View pathChild, int top, int bottom) {
            final int left = parent.getPaddingLeft();
            final int right = parent.getPaddingRight();
            final int paddingTop = parent.getPaddingTop();
            final int paddingBottom = parent.getPaddingBottom();

            final int removedTop = isTopInset(paddingTop, top) ? paddingTop : 0;
            final int removedBottom = isBottomInset(paddingBottom, bottom) ? paddingBottom : 0;
            if (removedTop == 0 && removedBottom == 0) return false;

            parent.setPadding(left, paddingTop - removedTop, right, paddingBottom - removedBottom);
            undo.add(() -> parent.setPadding(left, paddingTop, right, paddingBottom));

            // Headers and other overlays next to the Reels view keep their old position,
            // otherwise they would end up underneath the status bar clock.
            int middle = parent.getHeight() / 2;
            for (int i = 0; i < parent.getChildCount(); i++) {
                final View sibling = parent.getChildAt(i);
                if (sibling == pathChild) continue;

                boolean lowerHalf = sibling.getTop() + sibling.getHeight() / 2 > middle;
                int shift = lowerHalf ? -removedBottom : removedTop;
                if (shift == 0) continue;

                final float translation = sibling.getTranslationY();
                sibling.setTranslationY(translation + shift);
                undo.add(() -> sibling.setTranslationY(translation));
            }
            return true;
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
            for (int i = undo.size() - 1; i >= 0; i--) {
                undo.get(i).run();
            }
            undo.clear();
            adjusted.clear();
            showTabBar(tabBarId == 0 ? null : decor.findViewById(tabBarId));
            active = false;
            dumped = false;
        }

        /** Logs the view chain above the Reels view, useful when a new Instagram version moves things around. */
        private void dump(View reels, View decor, int top, int bottom) {
            Resources resources = activity.getResources();
            StringBuilder builder = new StringBuilder();
            builder.append("insets top=").append(top).append(" bottom=").append(bottom)
                    .append(" tabBarHeight=").append(tabBarHeight).append('\n');

            View view = reels;
            while (view != null) {
                String name = "no-id";
                if (view.getId() != View.NO_ID) {
                    try {
                        name = resources.getResourceEntryName(view.getId());
                    } catch (Resources.NotFoundException ignored) {
                    }
                }
                builder.append(view.getClass().getName()).append('#').append(name)
                        .append(" bounds=").append(view.getLeft()).append(',').append(view.getTop())
                        .append(',').append(view.getRight()).append(',').append(view.getBottom())
                        .append(" padding=").append(view.getPaddingTop()).append('/').append(view.getPaddingBottom());
                if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
                    builder.append(" margin=").append(params.topMargin).append('/').append(params.bottomMargin);
                }
                builder.append('\n');

                if (view == decor || !(view.getParent() instanceof View)) break;
                view = (View) view.getParent();
            }
            Log.d(TAG, builder.toString());
        }
    }
}
