package com.bdmajora.impetus.booter.service;

import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.MixinService;
import com.bdmajora.impetus.booter.Tags;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

// Advances the mixin environment to Phase.INIT at the one safe and complete point in boot: inside FMLDeobfTweaker#injectIntoClassLoader, after the deobf and access transformers register and before Loader initializes
public final class InitPhaseTrigger implements InvocationHandler {

    private static final String DEOBF_TWEAKER = "net.minecraftforge.fml.common.launcher.FMLDeobfTweaker";
    private static final String FML_LOG = "net.minecraftforge.fml.common.FMLLog";
    private static final String MESSAGE = "Validating minecraft";

    private static InitPhaseTrigger installed;

    private final Field field;
    private final Logger delegate;

    private boolean fired;

    private InitPhaseTrigger(Field field, Logger delegate) {
        this.field = field;
        this.delegate = delegate;
    }

    // Proxies Forge's logger field so the first log call from ModClassLoader advances Mixin to INIT
    static void install() {
        if (installed != null) {
            return;
        }
        try {
            ClassLoader loader = Launch.classLoader.loadClass(DEOBF_TWEAKER).getClassLoader();
            Field field = Class.forName(FML_LOG, true, loader).getDeclaredField("log");

            Field modifiers = Field.class.getDeclaredField("modifiers");
            modifiers.setAccessible(true);
            modifiers.setInt(field, field.getModifiers() & ~Modifier.FINAL);
            field.setAccessible(true);

            Logger delegate = (Logger) field.get(null);
            if (delegate == null) {
                logger().warn("'{}#log' is null, the INIT phase will not begin until the DEFAULT transition.", FML_LOG);
                return;
            }
            InitPhaseTrigger trigger = new InitPhaseTrigger(field, delegate);
            setStatic(field, Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[] { Logger.class }, trigger));
            installed = trigger;
            logger().debug("Hooked '{}#log' as defined by {}, the INIT phase will begin within FMLDeobfTweaker.", FML_LOG, loader);
        } catch (Throwable t) {
            logger().warn("Unable to hook '{}#log', the INIT phase will not begin until the DEFAULT transition.", FML_LOG, t);
        }
    }

    // Restores the real logger once INIT has been reached
    static void uninstall() {
        InitPhaseTrigger trigger = installed;
        if (trigger == null) {
            return;
        }
        installed = null;
        if (!trigger.fired) {
            logger().warn("'{}' was never seen on '{}#log' ({}), so INIT-phase configs were only staged at the DEFAULT transition",
                    MESSAGE, FML_LOG, trigger.delegate.getClass().getName());
        }
        try {
            setStatic(trigger.field, trigger.delegate);
        } catch (Throwable t) {
            logger().warn("Unable to restore '{}#log', FML logging remains proxied.", FML_LOG, t);
        }
    }

    // Fires the phase change on first use, then forwards every call to the real logger
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (!this.fired && args != null && args.length > 0 && MESSAGE.equals(args[0]) && "debug".equals(method.getName())) {
            this.fired = true;
            IMixinService service = MixinService.getService();
            if (service instanceof MixinBooterService) {
                logger().debug("'{}' reached, advancing the mixin environment to INIT.", MESSAGE);
                ((MixinBooterService) service).gotoInitPhase();
            }
        }
        try {
            return method.invoke(this.delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // Writes a static final via a handle, since the target field is final
    private static void setStatic(Field field, Object value) throws Throwable {
        field.set(null, value);
        if (field.get(null) != value) {
            throw new IllegalStateException("Unable to write '" + field.getDeclaringClass().getName() + "#" + field.getName() + "'");
        }
    }

    // The booter's own logger, resolved lazily
    private static ILogger logger() {
        return MixinService.getService().getLogger(Tags.MOD_NAME);
    }

}
