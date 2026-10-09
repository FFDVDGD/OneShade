package io.github.opd2413.ctrlcenter;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Uses the real Oplus notification and QS controllers, without copying notifications. */
public final class DualShadeHook implements IXposedHookLoadPackage {
    private static final String TAG = "OPD2413DualShade";
    private static final String PAGER = "com.oplus.systemui.separate.OplusPanelViewPagerController";
    private DualShade shade;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.android.systemui".equals(param.packageName)
                || !"OPD2413".equals(Build.MODEL) || Build.VERSION.SDK_INT != 36) {
            return;
        }
        try {
            Class<?> pager = XposedHelpers.findClass(PAGER, param.classLoader);
            Class<?> notification = XposedHelpers.findClass(
                    "com.android.systemui.shade.NotificationPanelViewController", param.classLoader);
            Class<?> qs = XposedHelpers.findClass(
                    "com.oplus.systemui.separate.OplusSeparateQSManager", param.classLoader);
            XposedBridge.hookAllMethods(pager, "onInit", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        View view = (View) field(p.thisObject, "mView");
                        long version = view.getContext().getPackageManager()
                                .getPackageInfo("com.android.systemui", 0).getLongVersionCode();
                        if (version != 169912) {
                            log("Unsupported SystemUI version: " + version);
                            return;
                        }
                        shade = new DualShade(p.thisObject);
                        log("Attached to SystemUI 16.99.12");
                    } catch (Throwable error) {
                        fail(error);
                    }
                }
            });
            hookExpansion(pager, "updateQSExpandFraction", true);
            hookExpansion(pager, "updateNotifyExpandFraction", false);
            hookNativeFling(notification, "fling", false, 3);
            Class<?> qsAnimator = XposedHelpers.findClass(
                    "com.oplus.systemui.plugins.qs.animator.QSPanelAnimatorManager", param.classLoader);
            hookNativeFling(qsAnimator, "onFling", true, 1);
            XposedBridge.hookAllMethods(qsAnimator, "onTouchMove", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (shade == null || !shade.dispatchingQs || !shade.dualVisible) return;
                    try {
                        // This is a touch target, not an animated spring output. Start the other
                        // native collapse on the upward drag, rather than waiting for ACTION_UP.
                        if (!(boolean) p.args[2] && (float) p.args[0]
                                < (float) call(p.thisObject, "getDefaultSettingEngine")) {
                            shade.onFling(true, false);
                        }
                    } catch (Throwable error) {
                        fail(error);
                    }
                }
            });
            XC_MethodHook finishOpeningGesture = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    // Gesture completion contains a second, independent overlap-collapse policy.
                    if (shade != null && shade.active() && (boolean) p.args[1]) {
                        shade.expansionCallbackDepth++;
                        p.setObjectExtra(TAG, shade);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    DualShade current = (DualShade) p.getObjectExtra(TAG);
                    if (current != null) current.expansionCallbackDepth--;
                }
            };
            XposedBridge.hookAllMethods(pager, "notifySeparateQSEndMotionEvent", finishOpeningGesture);
            XposedBridge.hookAllMethods(pager, "notifyNTEndMotionEvent", finishOpeningGesture);
            XC_MethodHook preventOverlapCollapse = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (shade != null && shade.active() && shade.expansionCallbackDepth > 0) {
                        p.setResult(null);
                    }
                }
            };
            XposedBridge.hookAllMethods(notification, "collapseNotifPanelWithoutAnimate", preventOverlapCollapse);
            XposedBridge.hookAllMethods(qs, "collapseQSPanel", preventOverlapCollapse);
            XposedBridge.hookAllMethods(pager, "getDownOnQsArea", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    // Open either side through notifications; start QS's own native animation alongside it.
                    // New gestures in an already open shade are routed directly to their pane below.
                    if (shade != null && shade.active()) p.setResult(false);
                }
            });
            Class<?> qsHost = XposedHelpers.findClass(qs.getName() + "$SeparateQSHost", param.classLoader);
            XposedBridge.hookAllMethods(qsHost, "getNtIsFullCollapsed", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    // QS otherwise intercepts every tile tap when notifications are expanded.
                    if (shade != null && shade.dispatchingQs && shade.active() && shade.dualVisible) {
                        p.setResult(true);
                    }
                }
            });
            XposedBridge.hookAllMethods(notification, "updateVisibility$9", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (shade != null) shade.refreshSafely();
                }
            });
            XposedBridge.hookAllMethods(pager, "updateNotificationPanelAlpha", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (shade != null && shade.active() && shade.dualVisible) p.args[1] = 1f;
                }
            });
            Class<?> touch = XposedHelpers.findClass(PAGER + "$TouchHandler", param.classLoader);
            XposedBridge.hookAllMethods(touch, "onInterceptTouchEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    // Both native page roots cover the screen, even where they draw nothing.
                    // Let our touch handler choose the pane instead of the topmost page root.
                    if (shade == null) return;
                    if (((MotionEvent) p.args[0]).getActionMasked() == MotionEvent.ACTION_DOWN) {
                        // Finish the opening gesture natively before routing new gestures.
                        shade.routingTouch = shade.active() && shade.dualVisible;
                    }
                    if (shade.routingTouch && shade.active()) p.setResult(true);
                }
            });
            XposedBridge.hookAllMethods(touch, "onTouchEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (shade == null || !shade.routingTouch || !shade.active()) return;
                    MotionEvent event = (MotionEvent) p.args[0];
                    DualShade current = shade;
                    try {
                        p.setResult(current.dispatch(event));
                    } catch (Throwable error) {
                        fail(error);
                    } finally {
                        if (event.getActionMasked() == MotionEvent.ACTION_UP
                                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                            current.routingTouch = false;
                        }
                    }
                }
            });
            log("Hooks installed");
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void hookNativeFling(Class<?> type, String name, boolean qs, int expandArgument) {
        XposedBridge.hookAllMethods(type, name, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (shade == null) return;
                try {
                    shade.onFling(qs, (boolean) p.args[expandArgument]);
                } catch (Throwable error) {
                    fail(error);
                }
            }
        });
    }

    private void hookExpansion(Class<?> pager, String name, boolean qs) {
        XposedBridge.hookAllMethods(pager, name, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (shade != null && shade.active()) {
                    shade.expansionCallbackDepth++;
                    p.setObjectExtra(TAG, shade);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                DualShade current = (DualShade) p.getObjectExtra(TAG);
                if (current == null) return;
                current.expansionCallbackDepth--;
                try {
                    current.onExpansion(qs, (float) p.args[0]);
                } catch (Throwable error) {
                    fail(error);
                }
            }
        });
    }

    private void fail(Throwable error) {
        log(Log.getStackTraceString(error));
        if (shade != null) {
            shade.disabled = true;
            shade.restore();
            shade = null;
        }
    }

    private static Object field(Object object, String name) {
        return XposedHelpers.getObjectField(object, name);
    }

    private static Object call(Object object, String name, Object... args) {
        return XposedHelpers.callMethod(object, name, args);
    }

    private static View findView(View root, String name) {
        if (root == null) return null;
        int id = root.getResources().getIdentifier(name, "id", "com.android.systemui");
        return root.findViewById(id);
    }

    private static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }

    private final class DualShade {
        private final Object controller;
        private final Object notificationController;
        private final Object qsManager;
        private final View notificationView;
        private final View content;
        private final View stack;
        private View duplicateIcons;
        private final View pagerView;
        private final Class<?> notificationRow;
        private final float originalTranslation;
        private float originalIconsAlpha;
        private int leader; // 0: closed, 1: notifications, 2: QS
        private int expansionCallbackDepth;
        private float notifyFraction;
        private float qsFraction;
        private boolean syncing;
        private boolean closing;
        private boolean dualVisible;
        private boolean touchOnQs;
        private boolean routingTouch;
        private boolean dispatchingQs;
        private boolean disabled;

        DualShade(Object controller) {
            this.controller = controller;
            notificationController = field(controller, "notificationPanelViewController");
            qsManager = field(controller, "separateQSManager");
            notificationView = (View) field(controller, "notificationPanelView");
            pagerView = (View) field(controller, "mView");
            content = findView(notificationView, "notification_container_parent");
            stack = findView(notificationView, "notification_stack_scroller");
            notificationRow = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                    notificationView.getClass().getClassLoader());
            if (content == null || stack == null) throw new IllegalStateException("Notification views missing");
            originalTranslation = content.getTranslationX();
            content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> refreshSafely());
        }

        boolean active() {
            return !disabled
                    && pagerView.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                    && !(boolean) call(controller, "isKeyguardVisible$1")
                    && (boolean) call(call(field(controller, "separateNotificationAndQSState"),
                            "getEnableSeparateNotificationAndQS"), "getValue");
        }

        void onExpansion(boolean qs, float fraction) {
            if (qs) qsFraction = fraction;
            else notifyFraction = fraction;
            if (syncing || !active()) return;
            // Native expand emits a zero-height update before its global-layout fling.
            if (!qs && fraction <= 0f && (boolean) field(notificationController, "mInstantExpanding")) return;
            if (fraction > 0f && !dualVisible) onFling(qs, true);
            if (dualVisible && notifyFraction <= 0f && qsFraction <= 0f) {
                dualVisible = false;
                closing = false;
                leader = 0;
                log("Both native panes collapsed");
            }
            // Do not feed either spring's output into the other animator.
            refresh();
        }

        void onFling(boolean qs, boolean expand) {
            if (syncing || !active() || expansionCallbackDepth > 0) return;
            if (expand ? dualVisible && !closing : !dualVisible || closing) return;
            leader = qs ? 2 : 1;
            dualVisible = true;
            closing = !expand;
            syncing = true;
            try {
                if (qs) {
                    if (expand) call(notificationController, "expand", true);
                    else call(notificationController, "collapse", false, 1f, "dual shade native sync");
                } else {
                    if (expand) call(qsManager, "expandQSPanel", true);
                    else call(qsManager, "collapseQSPanel", true, true, false, null);
                }
                log("Native " + (expand ? "expand" : "collapse") + "; leader=" + leader);
            } finally {
                syncing = false;
            }
        }

        void refreshSafely() {
            try {
                refresh();
            } catch (Throwable error) {
                fail(error);
            }
        }

        void refresh() {
            if (!active()) {
                boolean wasDualVisible = dualVisible;
                int previousLeader = leader;
                restore();
                dualVisible = false;
                closing = false;
                leader = 0;
                if (wasDualVisible) {
                    if (previousLeader == 2 && !(boolean) call(controller, "isKeyguardVisible$1")) {
                        call(notificationController, "collapseNotifPanelWithoutAnimate");
                    } else {
                        call(qsManager, "collapseQSPanel", false, true, false, null);
                    }
                }
                return;
            }
            if (dualVisible) {
                notificationView.setVisibility(View.VISIBLE);
                call(notificationView, "setTransitionAlpha", 1f);
                notificationView.setTranslationX(0f);
                View qs = (View) field(controller, "qsPanelView");
                if (qs != null) {
                    call(qs, "setTransitionAlpha", 1f);
                    qs.setTranslationX(0f);
                }
            }
            // Center the native-width cards in the space before the first painted QS column.
            float targetLeft = (rightColumnLeft() - content.getWidth()) * 0.5f;
            content.setTranslationX(targetLeft - layoutLeft(content));
            // The simple header is inflated after the pager's onInit; scope to it, not keyguard/QS.
            if (duplicateIcons == null) {
                duplicateIcons = findView(findView(notificationView, "simple_qs_footer"),
                        "quick_qs_status_icons");
                if (duplicateIcons != null) {
                    originalIconsAlpha = duplicateIcons.getAlpha();
                    log("Left status icons attached");
                }
            }
            if (duplicateIcons != null) duplicateIcons.setAlpha(0f);
        }

        float rightColumnLeft() {
            View column = findView((View) field(controller, "qsPanelView"), "personal_tiles_container");
            if (column == null || column.getWidth() == 0) return pagerView.getWidth();
            return layoutLeft(column) + column.getPaddingLeft();
        }

        float layoutLeft(View view) {
            // Screen coordinates include the native closing scale/translation; layout coordinates do not.
            float left = 0f;
            while (view != pagerView) {
                left += view.getLeft();
                if (!(view.getParent() instanceof View)) break;
                view = (View) view.getParent();
                left -= view.getScrollX();
            }
            return left;
        }

        boolean notificationContentAt(MotionEvent event) {
            int[] location = new int[2];
            stack.getLocationOnScreen(location);
            View child = (View) call(stack, "getChildAtPosition", event.getRawX() - location[0],
                    false, false, event.getRawY() - location[1]);
            if (child != null) {
                if (notificationRow.isInstance(child)) return true;
                if (interactiveAt(child, event)) return true;
            }
            return interactiveAt(content, event);
        }

        boolean interactiveAt(View view, MotionEvent event) {
            // Row/scroll hit-testing is handled above, not by the full-height stack's touchability.
            if (view == stack || !view.isShown() || view.getAlpha() == 0f) return false;
            Rect bounds = new Rect();
            if (!view.getGlobalVisibleRect(bounds)
                    || !bounds.contains((int) event.getRawX(), (int) event.getRawY())) return false;
            if (view.isEnabled() && (view.isClickable() || view.isLongClickable())) return true;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    if (interactiveAt(group.getChildAt(i), event)) return true;
                }
            }
            return false;
        }

        boolean dispatch(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // QS already implements native blank-space tap and upward-swipe collapse.
                touchOnQs = event.getRawX() >= rightColumnLeft() || !notificationContentAt(event);
                leader = touchOnQs ? 2 : 1;
            }
            if (touchOnQs) {
                dispatchingQs = true;
                try {
                    return (boolean) call(qsManager, "dispatchTouchEvent", event);
                } finally {
                    dispatchingQs = false;
                }
            }
            // Bypass the panel's old, centered QS hit region, preserving native row gestures.
            int[] location = new int[2];
            content.getLocationOnScreen(location);
            MotionEvent translated = MotionEvent.obtain(event);
            translated.setLocation(event.getRawX() - location[0], event.getRawY() - location[1]);
            try {
                return content.dispatchTouchEvent(translated);
            } finally {
                translated.recycle();
            }
        }

        void restore() {
            content.setTranslationX(originalTranslation);
            if (duplicateIcons != null) duplicateIcons.setAlpha(originalIconsAlpha);
        }
    }
}
