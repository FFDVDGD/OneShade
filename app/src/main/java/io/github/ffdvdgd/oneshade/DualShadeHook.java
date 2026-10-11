package io.github.ffdvdgd.oneshade;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Uses the real Oplus notification and QS controllers, without copying notifications. */
public final class DualShadeHook extends XposedModule {
    private static final String TAG = "OPD2413DualShade";
    private static final String PAGER = "com.oplus.systemui.separate.OplusPanelViewPagerController";
    private static final Map<Class<?>, Map<String, Field>> FIELDS = new HashMap<>();
    private static final Map<Class<?>, Map<String, Method>> METHODS = new HashMap<>();
    private final List<XposedInterface.HookHandle> hooks = new ArrayList<>();
    private DualShade shade;

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!"com.android.systemui".equals(param.getPackageName())
                || !"OPD2413".equals(Build.MODEL)) {
            return;
        }
        try {
            ClassLoader loader = param.getClassLoader();
            Class<?> pager = Class.forName(PAGER, false, loader);
            Class<?> notification = Class.forName(
                    "com.android.systemui.shade.NotificationPanelViewController", false, loader);
            Class<?> qs = Class.forName(
                    "com.oplus.systemui.separate.OplusSeparateQSManager", false, loader);
            hookAllMethods(pager, "onInit", chain -> {
                Object result = chain.proceed();
                try {
                    View view = (View) field(chain.getThisObject(), "mView");
                    long version = view.getContext().getPackageManager()
                            .getPackageInfo("com.android.systemui", 0).getLongVersionCode();
                    shade = new DualShade(chain.getThisObject());
                    log("Attached to SystemUI " + version);
                } catch (Throwable error) {
                    fail(error);
                }
                return result;
            });
            hookExpansion(pager, "updateQSExpandFraction", true);
            hookExpansion(pager, "updateNotifyExpandFraction", false);
            hookNativeFling(notification, "fling", false, 3);
            Class<?> qsAnimator = Class.forName(
                    "com.oplus.systemui.plugins.qs.animator.QSPanelAnimatorManager", false, loader);
            hookNativeFling(qsAnimator, "onFling", true, 1);
            hookAllMethods(qsAnimator, "onTouchMove", chain -> {
                if (shade != null && shade.dispatchingQs && shade.dualVisible) {
                    try {
                        // This is a touch target, not an animated spring output. Start the other
                        // native collapse on the upward drag, rather than waiting for ACTION_UP.
                        if (!(boolean) chain.getArg(2) && (float) chain.getArg(0)
                                < (float) call(chain.getThisObject(), "getDefaultSettingEngine")) {
                            shade.onFling(true, false);
                        }
                    } catch (Throwable error) {
                        fail(error);
                    }
                }
                return chain.proceed();
            });
            XposedInterface.Hooker finishOpeningGesture = chain -> {
                // Gesture completion contains a second, independent overlap-collapse policy.
                DualShade current = shade;
                boolean entered = current != null && current.active() && (boolean) chain.getArg(1);
                if (entered) current.expansionCallbackDepth++;
                try {
                    return chain.proceed();
                } finally {
                    if (entered) current.expansionCallbackDepth--;
                }
            };
            hookAllMethods(pager, "notifySeparateQSEndMotionEvent", finishOpeningGesture);
            hookAllMethods(pager, "notifyNTEndMotionEvent", finishOpeningGesture);
            XposedInterface.Hooker preventOverlapCollapse = chain -> {
                if (shade != null && shade.active() && shade.expansionCallbackDepth > 0) return null;
                return chain.proceed();
            };
            hookAllMethods(notification, "collapseNotifPanelWithoutAnimate", preventOverlapCollapse);
            hookAllMethods(qs, "collapseQSPanel", preventOverlapCollapse);
            hookAllMethods(pager, "getDownOnQsArea", chain -> {
                // Open either side through notifications; start QS's own native animation alongside it.
                // New gestures in an already open shade are routed directly to their pane below.
                return shade != null && shade.active() ? false : chain.proceed();
            });
            Class<?> qsHost = Class.forName(qs.getName() + "$SeparateQSHost", false, loader);
            hookAllMethods(qsHost, "getNtIsFullCollapsed", chain -> {
                // QS otherwise intercepts every tile tap when notifications are expanded.
                if (shade != null && shade.dispatchingQs && shade.active() && shade.dualVisible) return true;
                return chain.proceed();
            });
            hookAllMethods(notification, "updateVisibility$9", chain -> {
                Object result = chain.proceed();
                if (shade != null) shade.refreshSafely();
                return result;
            });
            hookAllMethods(pager, "updateNotificationPanelAlpha", chain -> {
                if (shade != null && shade.active() && shade.dualVisible) {
                    Object[] args = chain.getArgs().toArray();
                    args[1] = 1f;
                    return chain.proceed(args);
                }
                return chain.proceed();
            });
            Class<?> touch = Class.forName(PAGER + "$TouchHandler", false, loader);
            hookAllMethods(touch, "onInterceptTouchEvent", chain -> {
                // Both native page roots cover the screen, even where they draw nothing.
                // Let our touch handler choose the pane instead of the topmost page root.
                if (shade != null) {
                    if (((MotionEvent) chain.getArg(0)).getActionMasked() == MotionEvent.ACTION_DOWN) {
                        // Finish the opening gesture natively before routing new gestures.
                        shade.routingTouch = shade.active() && shade.dualVisible;
                    }
                    if (shade.routingTouch && shade.active()) return true;
                }
                return chain.proceed();
            });
            hookAllMethods(touch, "onTouchEvent", chain -> {
                if (shade == null || !shade.routingTouch || !shade.active()) return chain.proceed();
                MotionEvent event = (MotionEvent) chain.getArg(0);
                DualShade current = shade;
                try {
                    return current.dispatch(event);
                } catch (Throwable error) {
                    fail(error);
                } finally {
                    if (event.getActionMasked() == MotionEvent.ACTION_UP
                            || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        current.routingTouch = false;
                    }
                }
                return chain.proceed();
            });
            log("Hooks installed; API=" + getApiVersion() + "; framework=" + getFrameworkVersion());
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void hookAllMethods(Class<?> type, String name, XposedInterface.Hooker hooker) {
        int count = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                hooks.add(hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(hooker));
                count++;
            }
        }
        if (count == 0) throw new NoSuchMethodError(type.getName() + "#" + name);
    }

    private void hookNativeFling(Class<?> type, String name, boolean qs, int expandArgument) {
        hookAllMethods(type, name, chain -> {
            if (shade != null) {
                try {
                    shade.onFling(qs, (boolean) chain.getArg(expandArgument));
                } catch (Throwable error) {
                    fail(error);
                }
            }
            return chain.proceed();
        });
    }

    private void hookExpansion(Class<?> pager, String name, boolean qs) {
        hookAllMethods(pager, name, chain -> {
            DualShade current = shade;
            boolean entered = current != null && current.active();
            if (entered) current.expansionCallbackDepth++;
            try {
                return chain.proceed();
            } finally {
                if (entered) {
                    current.expansionCallbackDepth--;
                    try {
                        current.onExpansion(qs, (float) chain.getArg(0));
                    } catch (Throwable error) {
                        fail(error);
                    }
                }
            }
        });
    }

    private void fail(Throwable error) {
        log(Log.getStackTraceString(error));
        DualShade current = shade;
        shade = null;
        for (XposedInterface.HookHandle handle : hooks) handle.unhook();
        hooks.clear();
        if (current != null) {
            current.disabled = true;
            current.restore();
        }
    }

    private static Object field(Object object, String name) {
        Map<String, Field> fields = FIELDS.computeIfAbsent(object.getClass(), type -> new HashMap<>());
        Field field = fields.get(name);
        try {
            if (field == null) {
                for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
                    try {
                        field = type.getDeclaredField(name);
                        field.setAccessible(true);
                        fields.put(name, field);
                        break;
                    } catch (NoSuchFieldException ignored) {
                        // Private fields may belong to a superclass.
                    }
                }
                if (field == null) throw new NoSuchFieldException(object.getClass().getName() + "#" + name);
            }
            return field.get(object);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot read " + name, error);
        }
    }

    private static Object call(Object object, String name, Object... args) {
        Map<String, Method> methods = METHODS.computeIfAbsent(object.getClass(), type -> new HashMap<>());
        Method method = methods.get(name);
        try {
            if (method == null) {
                for (Class<?> type = object.getClass(); type != null && method == null; type = type.getSuperclass()) {
                    for (Method candidate : type.getDeclaredMethods()) {
                        if (!candidate.getName().equals(name) || candidate.getParameterCount() != args.length) continue;
                        Class<?>[] types = candidate.getParameterTypes();
                        boolean matches = true;
                        for (int i = 0; i < types.length; i++) {
                            if (!(args[i] == null && !types[i].isPrimitive()
                                    || types[i].isInstance(args[i])
                                    || types[i] == boolean.class && args[i] instanceof Boolean
                                    || types[i] == float.class && args[i] instanceof Float)) {
                                matches = false;
                                break;
                            }
                        }
                        if (matches) {
                            method = candidate;
                            method.setAccessible(true);
                            methods.put(name, method);
                            break;
                        }
                    }
                }
                if (method == null) throw new NoSuchMethodException(object.getClass().getName() + "#" + name);
            }
            return method.invoke(object, args);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot call " + name, error);
        }
    }

    private static View findView(View root, String name) {
        if (root == null) return null;
        int id = root.getResources().getIdentifier(name, "id", "com.android.systemui");
        return root.findViewById(id);
    }

    private void log(String message) {
        Log.i(TAG, message);
        log(Log.INFO, TAG, message);
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
            try {
                notificationRow = Class.forName(
                    "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                    false, notificationView.getClass().getClassLoader());
            } catch (ClassNotFoundException error) {
                throw new IllegalStateException("Notification row class missing", error);
            }
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
