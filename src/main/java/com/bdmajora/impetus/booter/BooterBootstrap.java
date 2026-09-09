package com.bdmajora.impetus.booter;

import net.minecraft.launchwrapper.Launch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

// Decides whether Impetus supplies the Mixin subsystem itself or stands down for a real MixinBooter.
//
// Deliberately references no org.spongepowered.asm type. It runs before any Mixin implementation is
// guaranteed to exist, so touching one here would fail to link on a vanilla-Forge install. Everything
// Mixin-facing lives in BooterCore and is only reached once this class reports MIXIN_OWNED.
public final class BooterBootstrap {

    // A real MixinBooter (or any other service provider) already owns Mixin; do nothing at all.
    public static final int DEFERRED = 0;
    // No Mixin on the classpath; we extracted our own copy and are responsible for booting it.
    public static final int MIXIN_OWNED = 1;

    // Probed as resources rather than Class.forName so that detection never triggers static init
    // on a foreign booter, which would bootstrap Mixin as a side effect of us merely looking.
    private static final String FOREIGN_BOOTER = "zone/rong/mixinbooter/MixinBooterPlugin.class";
    private static final String MIXIN_MARKER = "org/spongepowered/asm/launch/MixinBootstrap.class";

    // CleanMix + MixinExtras, shaded at build time and embedded unextracted. Kept as a nested jar
    // rather than loose classes so that on installs which do have MixinBooter we contribute zero
    // org.spongepowered.asm classes to the classpath, and the duplicate-class race cannot happen.
    private static final String NESTED_LIBS = "/com/bdmajora/impetus/booter/impetus-booter-libs.jar";

    private static int state = -1;

    private BooterBootstrap() { }

    public static int state() {
        return state;
    }

    public static int initialize() {
        if (state != -1) {
            return state;
        }
        state = resolve();
        return state;
    }

    private static int resolve() {
        // Someone set mixin.service before us, so a service provider is already committed.
        String service = System.getProperty("mixin.service");
        if (service != null && !service.isEmpty() && !service.startsWith("com.bdmajora.impetus.booter")) {
            log("Deferring to the Mixin service already selected by " + service);
            return DEFERRED;
        }

        if (resource(FOREIGN_BOOTER) != null) {
            log("MixinBooter detected on the classpath; standing down and using it.");
            return DEFERRED;
        }

        // Mixin present without MixinBooter, e.g. the dev runtime or a Sponge install. Nothing to add.
        if (resource(MIXIN_MARKER) != null) {
            log("An existing Mixin implementation was found; standing down.");
            return DEFERRED;
        }

        if (!extractAndAttachLibs()) {
            // Left un-owned on purpose: better to let the mod fail loudly on a missing Mixin than to
            // half-boot a subsystem other coremods will build on.
            log("No Mixin implementation available and the bundled copy could not be attached.");
            return DEFERRED;
        }

        log("No MixinBooter found; Impetus is supplying Mixin " + Tags.CLEANMIX_VERSION + " itself.");
        return MIXIN_OWNED;
    }

    private static boolean extractAndAttachLibs() {
        InputStream in = BooterBootstrap.class.getResourceAsStream(NESTED_LIBS);
        if (in == null) {
            log("Bundled Mixin libraries are missing from the Impetus jar.");
            return false;
        }
        try {
            File target = new File(gameDir(), ".impetus/impetus-booter-libs-" + Tags.VERSION + ".jar");
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                log("Could not create " + parent);
                return false;
            }
            // Re-extract every launch; a truncated jar from a previous crashed boot is worse than the copy cost.
            copy(in, target);
            URL url = target.toURI().toURL();
            Launch.classLoader.addURL(url);
            addToAppClassLoader(url);
            return resource(MIXIN_MARKER) != null;
        } catch (Throwable t) {
            log("Failed to attach the bundled Mixin libraries: " + t);
            return false;
        } finally {
            closeQuietly(in);
        }
    }

    // Mixin's service layer resolves through the AppClassLoader, mirroring MixinBooter's own
    // injectSelfIntoAppClassLoader; without this the ServiceLoader lookup will not see our jar.
    private static void addToAppClassLoader(URL url) {
        ClassLoader appClassLoader = ClassLoader.getSystemClassLoader();
        if (!(appClassLoader instanceof URLClassLoader)) {
            return;
        }
        try {
            Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            addURL.setAccessible(true);
            addURL.invoke(appClassLoader, url);
        } catch (Throwable t) {
            throw new RuntimeException("Unable to add the bundled Mixin libraries to the parent ClassLoader", t);
        }
    }

    private static URL resource(String path) {
        URL url = Launch.classLoader.getResource(path);
        return url != null ? url : ClassLoader.getSystemClassLoader().getResource(path);
    }

    private static File gameDir() {
        File home = Launch.minecraftHome;
        return home != null ? home : new File(".");
    }

    private static void copy(InputStream in, File target) throws IOException {
        OutputStream out = new FileOutputStream(target);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } finally {
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (IOException ignored) {
            // nothing useful to do this early in boot
        }
    }

    // Mixin's logger does not exist yet at this point, so this is the only channel available.
    private static void log(String message) {
        System.out.println("[" + Tags.MOD_NAME + "] " + message);
    }

}
