package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

public class StorySeen {
    private static final String STORY_SEEN_ROUTE = "media/seen/?reel=%s&live_vod=0";
    private static final String USER_SESSION_CLASS = "com.instagram.common.session.UserSession";
    private static final String MEDIA_CLASS = "com.instagram.feed.media.Media";

    public void handleStorySeenBlock(DexKitBridge bridge) {
        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings(STORY_SEEN_ROUTE)));

            if (methods.isEmpty()) {
                methods = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().usingStrings("media/seen/")));
            }

            if (methods.isEmpty()) {
                XposedBridge.log("(InstaEclipse | StoryBlock): ❌ No methods found containing the story seen route.");
                return;
            }

            for (MethodData method : methods) {
                Method reflectMethod;
                try {
                    reflectMethod = method.getMethodInstance(Module.hostClassLoader);
                } catch (Throwable e) {
                    continue;
                }

                if (!isStorySeenRequestBuilder(method, reflectMethod)) {
                    continue;
                }

                try {
                    Class<?> trackerClass = reflectMethod.getDeclaringClass();
                    boolean accumulatorHooked = hookStoryAccumulatorMethods(trackerClass);
                    boolean requestHooked = hookStoryRequestBuilder(reflectMethod);

                    if (accumulatorHooked || requestHooked) {
                        XposedBridge.log("(InstaEclipse | StoryBlock): ✅ Hooked (dynamic check): " +
                                method.getClassName() + "." + method.getName());
                        FeatureStatusTracker.setHooked("GhostStories");
                        return;
                    }
                } catch (Throwable e) {
                    XposedBridge.log("(InstaEclipse | StoryBlock): ❌ Hook error: " + e.getMessage());
                }
            }

            XposedBridge.log("(InstaEclipse | StoryBlock): ❌ Found story seen candidates, but none were hookable.");

        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | StoryBlock): ❌ Exception: " + t.getMessage());
        }
    }

    private boolean isStorySeenRequestBuilder(MethodData methodData, Method reflectMethod) {
        ClassDataList paramTypes = methodData.getParamTypes();
        String returnType = String.valueOf(methodData.getReturnType());

        return paramTypes.size() == 1
                && USER_SESSION_CLASS.equals(paramTypes.get(0).getName())
                && !returnType.contains("void")
                && reflectMethod.getDeclaringClass() != null;
    }

    private boolean hookStoryAccumulatorMethods(Class<?> trackerClass) {
        boolean hooked = false;

        for (Method candidate : trackerClass.getDeclaredMethods()) {
            Class<?>[] paramTypes = candidate.getParameterTypes();
            if (candidate.getReturnType() != Void.TYPE || paramTypes.length != 3) {
                continue;
            }
            if (!USER_SESSION_CLASS.equals(paramTypes[0].getName())
                    || !MEDIA_CLASS.equals(paramTypes[1].getName())
                    || paramTypes[2] != String.class) {
                continue;
            }

            try {
                candidate.setAccessible(true);
                XposedBridge.hookMethod(candidate, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (FeatureFlags.isGhostStory) {
                            param.setResult(null);
                        }
                    }
                });
                XposedBridge.log("(InstaEclipse | StoryBlock): ✅ Hooked accumulator: "
                        + trackerClass.getName() + "." + candidate.getName());
                hooked = true;
            } catch (Throwable e) {
                XposedBridge.log("(InstaEclipse | StoryBlock): ❌ Failed accumulator hook "
                        + trackerClass.getName() + "." + candidate.getName() + ": " + e.getMessage());
            }
        }

        return hooked;
    }

    private boolean hookStoryRequestBuilder(Method requestMethod) {
        requestMethod.setAccessible(true);
        XposedBridge.hookMethod(requestMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.isGhostStory) {
                    return;
                }
                clearStorySeenPayload(param.thisObject);
            }
        });
        XposedBridge.log("(InstaEclipse | StoryBlock): ✅ Hooked request builder: "
                + requestMethod.getDeclaringClass().getName() + "." + requestMethod.getName());
        return true;
    }

    private void clearStorySeenPayload(Object tracker) {
        if (tracker == null) {
            return;
        }

        for (Field field : tracker.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(tracker);
                if (value instanceof Map<?, ?> map) {
                    map.clear();
                } else if (value instanceof Collection<?> collection) {
                    collection.clear();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
