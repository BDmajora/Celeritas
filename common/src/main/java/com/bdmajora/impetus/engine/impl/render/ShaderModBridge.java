package com.bdmajora.impetus.engine.impl.render;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

// Reflective bridge from the engine down to whatever shader mod is installed
// The engine module is compiled without a dependency on the shader subsystem, so everything here goes through
// reflection: the bridge works the same whether the shader side is our own Umbra pipeline or a real third-party
// Iris build sitting next to us
public class ShaderModBridge {
    // Bound to the shader API instance at class-init and reused every call; null means "no shader mod present"
    private static final MethodHandle SHADERS_ENABLED;
    private static final MethodHandle SHADERS_OPEN_SCREEN;
    private static final MethodHandle NVIDIUM_ENABLED;

    static {
        MethodHandle shadersEnabled = null, shaderOpenScreen = null;
        try {
            // The Iris public API contract: class net.irisshaders.iris.api.v0.IrisApi with a static getInstance()
            // Our own shader subsystem is named Umbra internally but still SHIPS this exact class, in this exact
            // package, with these exact method names, because that is what everything else in the ecosystem probes
            // for. Renaming any of the three strings below to match our internal naming makes this lookup miss,
            // isShaderModPresent() go false, and the shader option pages silently vanish from the video settings.
            Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Method instanceGetter = irisApiClass.getDeclaredMethod("getInstance");
            // Singleton, so it is fetched once here and the handles are permanently bound to it below
            Object irisApiInstance = instanceGetter.invoke(null);
            shadersEnabled = MethodHandles.lookup().unreflect(irisApiClass.getDeclaredMethod("isShaderPackInUse")).bindTo(irisApiInstance);
            // Takes and returns Object rather than GuiScreen so this module never has to name a client class
            shaderOpenScreen =  MethodHandles.lookup().unreflect(irisApiClass.getDeclaredMethod("openMainIrisScreenObj", Object.class)).bindTo(irisApiInstance);
        } catch (NoSuchMethodException e) {
            // The class was found but a method was not: an API version we do not understand, worth printing
            e.printStackTrace();
        } catch (Throwable ignored) {
            // Everything else — ClassNotFoundException above all — is the ordinary "no shader mod installed" case
        }
        SHADERS_ENABLED = shadersEnabled;
        SHADERS_OPEN_SCREEN = shaderOpenScreen;
        MethodHandle nvidiumEnabled = null;
        try {
            // Nvidium exposes a plain static boolean field instead of an API object, so this is a getter handle
            Class<?> nvidiumClass = Class.forName("me.cortex.nvidium.Nvidium");
            nvidiumEnabled = MethodHandles.lookup().findStaticGetter(nvidiumClass, "IS_ENABLED", boolean.class);
        } catch (Throwable ignored) {
        }
        NVIDIUM_ENABLED = nvidiumEnabled;
    }

    // invokeExact needs the call site's descriptor to match the handle's type exactly, hence the explicit cast
    // Any failure answers false so a broken shader mod degrades to "no shaders" instead of taking down the caller
    public static boolean isNvidiumEnabled() {
        if(NVIDIUM_ENABLED != null) {
            try {
                return (boolean)NVIDIUM_ENABLED.invokeExact();
            } catch(Throwable e) {
                return false;
            }
        } else {
            return false;
        }
    }

    // Whether a shader pack is actually loaded RIGHT NOW, not merely whether the mod is installed
    // Callers use this per-frame to pick between the vanilla and shader render paths
    public static boolean areShadersEnabled() {
        if(SHADERS_ENABLED != null) {
            try {
                return (boolean)SHADERS_ENABLED.invokeExact();
            } catch (Throwable e) {
                return false;
            }
        } else {
            return false;
        }
    }

    // Whether the API class resolved at class-init, i.e. a shader mod exists at all
    // The video options screen gates the shader pack pages on this, so a failed lookup above shows up to the user
    // as two missing tabs and nothing else
    public static boolean isShaderModPresent() {
        return SHADERS_ENABLED != null;
    }

    // Builds the shader mod's own pack selection screen with the given screen as its parent
    // invoke (not invokeExact) because the handle is typed against the API's own return, which we take as Object
    // Returns null when there is no shader mod or the call blew up; the caller is expected to just not navigate
    public static Object openShaderScreen(Object parentScreen) {
        if(SHADERS_OPEN_SCREEN != null) {
            try {
                return SHADERS_OPEN_SCREEN.invoke(parentScreen);
            } catch(Throwable e) {
                e.printStackTrace();
            }
        }
        return null;
    }
}
