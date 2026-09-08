package com.bdmajora.extras.client.particle;

import com.bdmajora.extras.mixin.particle.ParticleManagerAccessor;
import net.minecraft.client.particle.IParticleFactory;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The set of particle classes seen so far, and which of them the user has switched off.
 *
 * <p>Sodium Extra can enumerate particles from the {@code ParticleType} registry. 1.12.2 has no such
 * registry — {@code ParticleManager} holds an {@code int -> IParticleFactory} map and the concrete
 * {@link Particle} subclass a factory produces is not recoverable from its type. So discovery runs
 * from three places, in decreasing order of authority:
 *
 * <ol>
 *   <li>{@link #scanFactories(ParticleManager)} — reflection over the registered factories, run once
 *       per {@code effectRenderer} by {@link ParticleDiscoveryHandler}. Resolves vanilla and any mod
 *       factory whose shape gives the particle class away.</li>
 *   <li>{@link #recordClass(Class)} — recorded when a particle actually spawns. The only thing that
 *       works for factories registered as lambdas or anonymous classes.</li>
 *   <li>{@link #registerFactoryMod(IParticleFactory, String)} — the owning mod captured at
 *       {@code registerParticle} time, used to group the toggles by mod.</li>
 * </ol>
 *
 * <p>Discovered classes are a cache: persisted so previously seen particles have toggles from the
 * next launch, and reconciled against what still loads. Only {@link #disabledClasses} is user data.
 */
public final class ParticleClassRegistry {
    private static final ParticleClassRegistry INSTANCE = new ParticleClassRegistry();

    /** Fully-qualified name to display name; the display name is never empty. */
    private final ConcurrentHashMap<String, String> discoveredClasses = new ConcurrentHashMap<>();

    /** Fully-qualified name to owning mod id. */
    private final ConcurrentHashMap<String, String> classModIds = new ConcurrentHashMap<>();

    /** Factory instance to owning mod id, captured while {@code registerParticle} runs. */
    private final Map<IParticleFactory, String> factoryModIds =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** The user's switched-off set. The only authoritative persisted state here. */
    private final Set<String> disabledClasses = ConcurrentHashMap.newKeySet();

    /** Per-session identity guard, so the spawn path does real work at most once per class. */
    private final Set<Class<?>> seenClasses = ConcurrentHashMap.newKeySet();

    private volatile boolean dirty;

    /** Mod source jar/directory to mod id, built on first attribution. */
    private volatile Map<File, String> sourceToModId;

    private ParticleClassRegistry() {
    }

    public static ParticleClassRegistry getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------------------------------

    /** Records a class seen at spawn time, where there is no factory to attribute it with. */
    public void recordClass(Class<?> clazz) {
        recordClass(clazz, null);
    }

    /**
     * Records a discovered particle class.
     *
     * <p>Identity-guarded: the name and attribution work happens at most once per class per session,
     * which is what makes this cheap enough to call for every particle spawned.
     */
    public void recordClass(Class<?> clazz, IParticleFactory factory) {
        if (clazz == null) {
            return;
        }

        boolean firstSeen = seenClasses.add(clazz);
        String fullName = clazz.getName();

        if (firstSeen && discoveredClasses.putIfAbsent(fullName, simpleNameOf(clazz)) == null) {
            dirty = true;
        }

        if (!firstSeen && (factory == null || classModIds.containsKey(fullName))) {
            return;
        }

        String modId = resolveModId(clazz, factory);
        if (modId != null) {
            classModIds.putIfAbsent(fullName, modId);
        }
    }

    /** Captures which mod registered a factory. The strongest attribution signal available. */
    public void registerFactoryMod(IParticleFactory factory, String modId) {
        if (factory != null && modId != null) {
            factoryModIds.put(factory, modId);
        }
    }

    /**
     * Walks {@code ParticleManager}'s registered factories looking for the classes they produce.
     *
     * <p>Two shapes are resolvable: an inner-class factory (whose enclosing class is the particle,
     * e.g. {@code ParticleFlame.Factory}), and a factory declaring a covariant return type. Lambda
     * and anonymous factories match neither and are left to the spawn-time path.
     */
    public void scanFactories(ParticleManager particleManager) {
        if (particleManager == null) {
            return;
        }

        Map<Integer, IParticleFactory> factories;
        try {
            factories = ((ParticleManagerAccessor) particleManager).impetus$getParticleTypes();
        } catch (Throwable t) {
            return;
        }

        if (factories == null) {
            return;
        }

        for (Map.Entry<Integer, IParticleFactory> entry : factories.entrySet()) {
            IParticleFactory factory = entry.getValue();
            if (factory == null) {
                continue;
            }

            Class<?> factoryClass = factory.getClass();
            try {
                Class<?> enclosing = factoryClass.getEnclosingClass();
                if (enclosing != null && Particle.class.isAssignableFrom(enclosing)) {
                    recordClass(enclosing, factory);
                    continue;
                }

                for (Method method : factoryClass.getDeclaredMethods()) {
                    Class<?> returnType = method.getReturnType();
                    if (returnType != Particle.class && Particle.class.isAssignableFrom(returnType)) {
                        recordClass(returnType, factory);
                        break;
                    }
                }
            } catch (Throwable t) {
                // One unreflectable factory must not abort the scan for all the others.
            }
        }
    }

    /**
     * Reconciles the persisted cache against what currently loads: drops entries whose mod is gone,
     * keeps entries that exist but cannot be linked right now, and back-fills mod attribution.
     */
    public void pruneDiscoveredCache() {
        ClassLoader loader = ParticleClassRegistry.class.getClassLoader();

        for (String name : new ArrayList<>(discoveredClasses.keySet())) {
            Class<?> clazz;
            try {
                clazz = Class.forName(name, false, loader);
            } catch (ClassNotFoundException e) {
                discoveredClasses.remove(name);
                classModIds.remove(name);
                dirty = true;
                continue;
            } catch (Throwable t) {
                // Present but not linkable at the moment; not evidence the mod was removed.
                continue;
            }

            if (!classModIds.containsKey(name)) {
                String modId = resolveModId(clazz, null);
                if (modId != null) {
                    classModIds.put(name, modId);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Mod attribution
    // ------------------------------------------------------------------------------------------

    private String resolveModId(Class<?> clazz, IParticleFactory factory) {
        String name = clazz.getName();
        if (name.startsWith("net.minecraft.")) {
            return "minecraft";
        }

        if (factory != null) {
            String captured = factoryModIds.get(factory);
            if (captured != null) {
                return captured;
            }
        }

        return modIdFromCodeSource(clazz);
    }

    private String modIdFromCodeSource(Class<?> clazz) {
        try {
            CodeSource source = clazz.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }

            URL location = source.getLocation();
            if (location == null) {
                return null;
            }

            File file;
            try {
                file = new File(location.toURI());
            } catch (Exception e) {
                file = new File(location.getPath());
            }

            return sourceMap().get(canonical(file));
        } catch (Throwable t) {
            return null;
        }
    }

    private Map<File, String> sourceMap() {
        Map<File, String> map = sourceToModId;
        if (map == null) {
            map = new HashMap<>();
            try {
                for (ModContainer container : Loader.instance().getActiveModList()) {
                    File source = container.getSource();
                    if (source != null) {
                        map.put(canonical(source), container.getModId());
                    }
                }
            } catch (Throwable t) {
                // Best effort; keep whatever was collected.
            }
            sourceToModId = map;
        }
        return map;
    }

    /** The owning mod id, {@code "minecraft"} for vanilla classes, or null when unattributable. */
    public String getModId(String fullClassName) {
        String modId = classModIds.get(fullClassName);
        if (modId != null) {
            return modId;
        }
        return fullClassName.startsWith("net.minecraft.") ? "minecraft" : null;
    }

    // ------------------------------------------------------------------------------------------
    // The disabled set
    // ------------------------------------------------------------------------------------------

    public boolean isClassDisabled(String fullClassName) {
        return disabledClasses.contains(fullClassName);
    }

    /** True when nothing is filtered, so the spawn path can skip the lookup entirely. */
    public boolean isEmptyDisabled() {
        return disabledClasses.isEmpty();
    }

    public void setClassEnabled(String fullClassName, boolean enabled) {
        boolean changed = enabled
                ? disabledClasses.remove(fullClassName)
                : disabledClasses.add(fullClassName);
        if (changed) {
            dirty = true;
        }
    }

    public void loadDisabledClasses(String[] classes) {
        disabledClasses.clear();
        for (String name : classes) {
            if (name != null && !name.isEmpty()) {
                disabledClasses.add(name);
            }
        }
    }

    public String[] getDisabledClassesArray() {
        return disabledClasses.stream().sorted().toArray(String[]::new);
    }

    // ------------------------------------------------------------------------------------------
    // Discovered-class cache
    // ------------------------------------------------------------------------------------------

    /** An unmodifiable {@code fullClassName -> displayName} view. */
    public Map<String, String> getDiscoveredClasses() {
        return Collections.unmodifiableMap(discoveredClasses);
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    /** Reads back {@code "fullName|simpleName"} entries so toggles exist before anything spawns. */
    public void loadDiscoveredClasses(String[] entries) {
        for (String entry : entries) {
            if (entry == null || entry.isEmpty()) {
                continue;
            }

            int separator = entry.indexOf('|');
            String fullName = separator > 0 ? entry.substring(0, separator) : entry;
            String simpleName = separator > 0 ? entry.substring(separator + 1) : "";

            if (fullName.isEmpty()) {
                continue;
            }

            if (simpleName.isEmpty()) {
                simpleName = toSimpleName(fullName);
            }
            if (simpleName.isEmpty()) {
                simpleName = fullName;
            }

            discoveredClasses.putIfAbsent(fullName, simpleName);
        }
    }

    public String[] getDiscoveredClassesArray() {
        return discoveredClasses.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "|" + entry.getValue())
                .toArray(String[]::new);
    }

    // ------------------------------------------------------------------------------------------
    // Naming
    // ------------------------------------------------------------------------------------------

    /** {@code com.foo.Bar$Baz -> Baz}, {@code com.foo.Bar$1 -> 1}. */
    private static String toSimpleName(String fullName) {
        String name = fullName.substring(fullName.lastIndexOf('.') + 1);
        int dollar = name.lastIndexOf('$');
        return dollar >= 0 ? name.substring(dollar + 1) : name;
    }

    /**
     * A display name that is always usable, even for synthetic classes whose
     * {@link Class#getSimpleName()} is empty or throws.
     */
    private static String simpleNameOf(Class<?> clazz) {
        String name;
        try {
            name = clazz.getSimpleName();
        } catch (Throwable t) {
            name = "";
        }

        if (name == null || name.isEmpty()) {
            name = toSimpleName(clazz.getName());
        }
        if (name.isEmpty()) {
            name = clazz.getName();
        }

        return name;
    }

    private static File canonical(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            return file.getAbsoluteFile();
        }
    }
}
