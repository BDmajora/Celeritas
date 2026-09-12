package com.bdmajora.impetus.booter.service;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraftforge.fml.relauncher.CoreModManager;
import net.minecraftforge.fml.relauncher.libraries.Artifact;
import net.minecraftforge.fml.relauncher.libraries.LibraryManager;
import net.minecraftforge.fml.relauncher.libraries.Repository;
import org.apache.commons.io.IOUtils;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.launch.GlobalProperties;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.service.MixinService;
import org.spongepowered.asm.util.Constants.ManifestAttributes;
import com.bdmajora.impetus.booter.Tags;
import com.bdmajora.impetus.booter.util.Environment;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

// Discovers all mods present in the game directory at coremod init time independently of
// net.minecraftforge.fml.common.Loader which is not available early. Builds a bidirectional mod-id and file
// mapping used for dependency checking
public final class ModDiscoverer {

    private static final ILogger LOGGER = MixinService.getService().getLogger(Tags.MOD_NAME);
    private static final String FORCE_LOAD_AS_MOD = "ForceLoadAsMod";
    private static final String COREMOD_CONTAINS_FML_MOD = "FMLCorePluginContainsFMLMod";
    private static final String FML_CORE_PLUGIN = "FMLCorePlugin";
    private static final String TWEAK_CLASSES = "TweakClasses";
    private static final SetMultimap<String, File> modIdToFiles = HashMultimap.create();
    private static final SetMultimap<File, String> fileToModIds = LinkedHashMultimap.create();
    private static final Set<File> manifestMixinJars = new HashSet<>();
    private static final Set<File> manifestMixinModDirJars = new HashSet<>();
    private static final Set<String> forceLoadAsModFiles = new HashSet<>();
    private static final Set<String> forceReparseableFiles = new HashSet<>();
    private static final Map<File, String> droppedCoremods = new LinkedHashMap<>();
    private static final List<String> rescuedTweakClasses = new ArrayList<>();
    private static final Collection<String> ALLOWED_TWEAKERS = Arrays.asList(
            "org.spongepowered.asm.launch.MixinTweaker",
            "com.replaymod.core.tweaker.ReplayModTweaker"
    );

    private static boolean discovered = false;

    private ModDiscoverer() { }

    // Returns whether any discovered jar declares the given mod id
    public static boolean isModPresent(String modId) {
        return modIdToFiles.containsKey(modId);
    }

    // Returns the ids of every mod discovered across all scanned jars
    public static Set<String> getPresentMods() {
        return Collections.unmodifiableSet(modIdToFiles.keySet());
    }

    // Returns the jars that declare the given mod id. A single mod id may be shipped by more than one jar (e.g.
    // duplicate installs), hence a set
    public static Set<File> getModSources(String modId) {
        return Collections.unmodifiableSet(modIdToFiles.get(modId));
    }

    // Returns the id of the mod owning the given jar. Use #getModsFromSource(File) instead if you need every
    // declared id
    public static String getModFromSource(File source) {
        Set<String> ids = getModsFromSource(source);
        return ids.isEmpty() ? null : ids.iterator().next();
    }

    // Returns the ids of the mod(s) owning the given jar, resolved during #discover() from its mcmod.info or,
    // when that declares none, its @Mod annotation. A jar may declare more than one mod (via mcmod.info's
    // modList), hence the set
    public static Set<String> getModsFromSource(File source) {
        return Collections.unmodifiableSet(fileToModIds.get(source.getAbsoluteFile()));
    }

    // Honors the ForceLoadAsMod manifest key for every jar collected during #discover(). Replicating the only
    // behavior the removed MixinPlatformAgentFMLLegacy still provided which was un-ignoring jars so Forge would
    // load it as mods. Forge unconditionally adds cascading-tweaker jars to its ignored list and never reaches
    // its own FMLCorePluginContainsFMLMod handling for them. Without this functionality those jars would
    // silently fail to load as mods. Must run after CoreModManager#discoverCoreMods has fully populated the
    // ignored list, i.e. from injectData and not from the plugin constructor
    public static void applyForceLoadAsMod() {
        if (forceLoadAsModFiles.isEmpty()) {
            return;
        }
        List<String> ignored = CoreModManager.getIgnoredMods();
        List<String> reparseable = CoreModManager.getReparseableCoremods();
        for (String name : forceLoadAsModFiles) {
            ignored.remove(name);
        }
        for (String name : forceReparseableFiles) {
            if (!reparseable.contains(name)) {
                reparseable.add(name);
            }
        }
    }

    // Loads the FMLCorePlugin of every jar that also declares a TweakClass, which Forge's
    // CoreModManager#discoverCoreMods skips. The removed MixinPlatformAgentFMLLegacy re-injected these via
    // CoreModManager.loadCoreMod and this replicates that. This must run from CoremodsRescuer's constructor
    // while LaunchWrapper is iterating TweakClasses, because rescued coremods such as Sledgehammer may add
    // directly to Tweaks. Any TweakClasses added during rescue are captured and replayed later to avoid
    // corrupting LaunchWrapper's active TweakClasses iterator
    public static void rescueDroppedCoremods() {
        if (droppedCoremods.isEmpty()) {
            return;
        }
        Method loadCoreMod;
//        Field loadPlugins, location;
        try {
            loadCoreMod = CoreModManager.class.getDeclaredMethod("loadCoreMod", LaunchClassLoader.class, String.class, File.class);
            loadCoreMod.setAccessible(true);
//            loadPlugins = CoreModManager.class.getDeclaredField("loadPlugins");
//            loadPlugins.setAccessible(true);
//            location = Class.forName("net.minecraftforge.fml.relauncher.CoreModManager$FMLPluginWrapper", true, Launch.classLoader).getDeclaredField("location")
//            location.setAccessible(true);
        } catch (Throwable t) {
            LOGGER.error("Unable to access crucial internals. Coremods declared alongside a TweakClass will not be loaded.", t);
            return;
        }
        Map<String, File> coremods = new HashMap<>();
        for (Map.Entry<File, String> entry : droppedCoremods.entrySet()) {
            File jar = entry.getKey();
            String coremod = entry.getValue();
            if (coremods.containsKey(coremod)) {
                continue;
            }
            try {
                Launch.classLoader.addURL(jar.toURI().toURL());
                coremods.put(coremod, jar);
            } catch (MalformedURLException e) {
                LOGGER.error("Failed to manually load coremod {} from {}.", coremod, jar.getName(), e);
            }
        }

        List<String> launchTweakClasses = getLaunchTweakClasses();
        CapturedTweakClasses shadowTweakClasses = new CapturedTweakClasses(launchTweakClasses);
        setLaunchTweakClasses(shadowTweakClasses);
        try {
            for (Map.Entry<String, File> entry : coremods.entrySet()) {
                String coremod = entry.getKey();
                File jar = entry.getValue();
                try {
                    Object wrapper = loadCoreMod.invoke(null, Launch.classLoader, coremod, jar);
                    if (wrapper != null) {
                        LOGGER.warn("{} declares both a TweakClass and FMLCorePlugin. Forge skips the coremod in this case and {} was loaded manually. Ship it as a normal coremod without a TweakClass.", jar.getName(), coremod);
                    } else {
                        LOGGER.error("Failed to manually load coremod {} from {}.", coremod, jar.getName());
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to manually load coremod {} from {}.", coremod, jar.getName(), e);
                }
            }
        } finally {
            setLaunchTweakClasses(launchTweakClasses);
        }
        rescuedTweakClasses.addAll(shadowTweakClasses.getCapturedTweakClasses());
    }

    // Replays TweakClasses that rescued coremods added, now that LaunchWrapper is ready for them
    public static void flushRescuedTweakClasses() {
        if (rescuedTweakClasses.isEmpty()) {
            return;
        }
        List<String> tweakClasses = getLaunchTweakClasses();
        if (tweakClasses == null) {
            LOGGER.error("Unable to retrieve rescued tweak classes because LaunchWrapper's TweakClasses list is unavailable.");
            rescuedTweakClasses.clear();
            return;
        }
        for (String tweakClass : rescuedTweakClasses) {
            if (!tweakClasses.contains(tweakClass)) {
                LOGGER.warn("Retrieving tweak class {} added by a rescued coremod.", tweakClass);
                tweakClasses.add(tweakClass);
            }
        }
        rescuedTweakClasses.clear();
    }

    // The live TweakClasses list from the blackboard
    @SuppressWarnings("unchecked")
    private static List<String> getLaunchTweakClasses() {
        return (List<String>) Launch.blackboard.get(TWEAK_CLASSES);
    }

    // Replaces the blackboard's list, used to install the capturing wrapper
    private static void setLaunchTweakClasses(Object tweakClasses) {
        Launch.blackboard.put(TWEAK_CLASSES, tweakClasses);
        GlobalProperties.put(GlobalProperties.Keys.of(TWEAK_CLASSES), tweakClasses);
    }
    // Gathers the same candidate set FML resolves in CoreModManager#discoverCoreMods (the flat mods/ and mods/
    // directories, command-line --mods, and contained dependencies extracted into memory_repo or the libraries
    // directory) and supplements it with classpath entries already on the LaunchClassLoader for the dev
    // environment. Must be called once before FML's own mod discovery runs
    public static void discover() {
        if (discovered) {
            return;
        }
        discovered = true;

        long startTime = System.currentTimeMillis();
        Gson gson;
        try {
            gson = new GsonBuilder().setLenient().create();
        } catch (NoSuchMethodError e) {
            // Older Gson bundled on 1.8.x lacks setLenient()
            gson = new GsonBuilder().create();
        }

        for (File candidate : gatherCandidates()) {
            if (candidate.isFile() && candidate.getName().endsWith(".jar")) {
                scanJar(gson, candidate, true);
            }
        }

        // Secondary: classloader URLs
        for (URL url : Launch.classLoader.getURLs()) {
            try {
                File file = new File(url.toURI());
                if (file.isFile() && file.getName().endsWith(".jar") && !fileToModIds.containsKey(file)) {
                    scanJar(gson, file, false);
                }
            } catch (URISyntaxException ignored) { }
        }

        LOGGER.info("Finished gathering {} mods, took {} ms.", modIdToFiles.keySet().size(), System.currentTimeMillis() - startTime);
        LOGGER.debug("Mods gathered: {}", String.join(", ", modIdToFiles.keySet()));
    }

    // Internal usage, files with mixin config/connector entries declared in its manifest
    static Set<File> manifestMixinJars() {
        return manifestMixinJars;
    }

    // Internal usage, whether the given manifest mixin jar was gathered as a mods-folder (or maven artifact)
    // candidate rather than purely from the dev classloader
    static boolean isModDirMixinJar(File jar) {
        return manifestMixinModDirJars.contains(jar);
    }

    // Builds the candidate jar set FML resolves in CoreModManager#discoverCoreMods. The legacy candidates
    // (LibraryManager#gatherLegacyCanidates which are from the mods/ and mods/ directories plus command-line
    // --mods) merged with the maven artifacts (LibraryManager#flattenLists which are the contained dependencies
    // extracted into memory_repo or the libraries directory). Both are read-only and already invoked by FML
    // before this runs, so querying them again here is safe
    private static List<File> gatherCandidates() {
        File mcDir = Launch.minecraftHome != null ? Launch.minecraftHome : new File(".");
        if ("1.12.2".equals(Environment.minecraftVersion())) {
            List<File> candidates = LibraryManager.gatherLegacyCanidates(mcDir);
            for (Artifact artifact : LibraryManager.flattenLists(mcDir)) {
                Artifact resolved = Repository.resolveAll(artifact);
                if (resolved == null) {
                    continue;
                }
                File target = resolved.getFile();
                if (target != null && !candidates.contains(target)) {
                    candidates.add(target);
                }
            }
            return candidates;
        }
        List<File> candidates = new ArrayList<>();
        File modsDir = new File(mcDir, "mods");
        if (modsDir.exists() && modsDir.isDirectory()) {
            File[] mods = modsDir.listFiles();
            if (mods != null) {
                candidates.addAll(Arrays.asList(mods));
            }
        }
        File versionDir = new File(modsDir, Environment.minecraftVersion());
        if (versionDir.exists() && versionDir.isDirectory()) {
            File[] mods = versionDir.listFiles();
            if (mods != null) {
                candidates.addAll(Arrays.asList(mods));
            }
        }
        return candidates;
    }

    // Reads a jar's manifest and mcmod.info, recording mod ids and mixin manifest entries
    private static void scanJar(Gson gson, File jar, boolean modDirCandidate) {
        try (JarFile jarFile = new JarFile(jar)) {
            Manifest manifest = jarFile.getManifest();
            if (manifest != null) {
                Attributes attributes = manifest.getMainAttributes();
                // Skip Cleanroom mods
                if (ManifestAttributes.CLEANROOMMODTYPE.equals(attributes.getValue(ManifestAttributes.MODTYPE))) {
                    LOGGER.info("Skipping {} as it is a Cleanroom mod.", jar.getName());
                    return;
                }
                if (attributes.getValue(ManifestAttributes.MIXINCONFIGS) != null || attributes.getValue(ManifestAttributes.MIXINCONNECTOR) != null) {
                    manifestMixinJars.add(jar);
                    if (modDirCandidate) {
                        manifestMixinModDirJars.add(jar);
                    }
                }
                resolveLegacyBehaviour(jar, attributes, modDirCandidate);
                // OptiFine special-case
                if ("optifine.OptiFineForgeTweaker".equals(attributes.getValue(ManifestAttributes.TWEAKER))) {
                    recordMod("optifine", jar);
                    return;
                }
                if ("net.jan.moddirector.launchwrapper.ModDirectorTweaker".equals(attributes.getValue(ManifestAttributes.TWEAKER))) {
                    recordMod("moddirector", jar);
                    return;
                }
                if ("git.jbredwards.jsonpaintings.mod.asm.ASMHandler".equals(attributes.getValue(FML_CORE_PLUGIN))) {
                    recordMod("jsonpaintings", jar);
                    return;
                }
            }
            ZipEntry entry = jarFile.getEntry("mcmod.info");
            Set<String> modIds = new HashSet<>();
            if (entry != null) {
                parseMcmodInfo(gson, jarFile.getInputStream(entry), modIds);
            }
            String modAnnotationId = scanModAnnotation(jarFile);
            if (modAnnotationId != null) {
                modIds.add(modAnnotationId);
            }
            for (String modId : modIds) {
                recordMod(modId, jar);
            }
            checkIfJarBundlesMixin(jar, jarFile, modIds);
        } catch (IOException e) {
            LOGGER.error("Failed to read mod metadata from {}", jar.getName(), e);
        }
    }

    // Maintains both directions of the id-to-file mapping
    private static void recordMod(String modId, File source) {
        File abs = source.getAbsoluteFile();
        modIdToFiles.put(modId, abs);
        fileToModIds.put(abs, modId);
    }

    // Logs if a mod jar bundles its own Mixin engine of any fork variety
    private static void checkIfJarBundlesMixin(File jar, JarFile jarFile, Set<String> modIds) {
        if (modIds.isEmpty() || modIds.contains("mixinbooter")) {
            return;
        }
        if (jarFile.getEntry("org/spongepowered/asm/launch/MixinBootstrap.class") != null) {
            LOGGER.warn("{} bundles its own Mixins and this may cause issues. It should depend on MixinBooter instead.", jar.getName());
        }
    }

    // Handles both the bare array and the modList object form of mcmod.info
    private static void parseMcmodInfo(Gson gson, InputStream stream, Set<String> ids) {
        try {
            JsonElement root = gson.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonElement.class);
            if (root.isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray()) {
                    if (element.isJsonObject()) {
                        ids.add(element.getAsJsonObject().get("modid").getAsString());
                    }
                }
            } else {
                for (JsonElement element : root.getAsJsonObject().get("modList").getAsJsonArray()) {
                    if (element.isJsonObject()) {
                        ids.add(element.getAsJsonObject().get("modid").getAsString());
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to parse mcmod.info", t);
        } finally {
            IOUtils.closeQuietly(stream);
        }
    }

    // Scans the jar's classes for the first @Mod annotation and returns its modid or null if none declares one.
    // Reads bytecode only and unreadable entries are skipped, the walk stops at the first match
    private static String scanModAnnotation(JarFile jar) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                continue;
            }
            try (InputStream in = jar.getInputStream(entry)) {
                ModAnnotationVisitor visitor = new ModAnnotationVisitor();
                try {
                    new ClassReader(in).accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                } catch (ExitVisitException ignored) { }
                if (visitor.modId != null) {
                    return visitor.modId;
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    // Records a jar that requests Mixin's ForceLoadAsMod manifest key so it can be honored later by
    // #applyForceLoadAsMod(). The actual mutation of Forge's coremod lists is deferred because this runs while
    // CoreModManager#discoverCoreMods is still populating relevant lists. And if the jar has declared a Mixin
    // bootstrap TweakClass and an FMLCorePlugin, the TweakClass will be cascaded and the FMLCorePlugin will
    // never be instantiated. ReplayMod uses its own bootstrap tweaker but relies on its coremod to remove the
    // jar from Forge's ignored list
    private static void resolveLegacyBehaviour(File jar, Attributes attributes, boolean modDirCandidate) {
        if ("true".equalsIgnoreCase(attributes.getValue(FORCE_LOAD_AS_MOD))) {
            forceLoadAsModFiles.add(jar.getName());
            if (modDirCandidate && attributes.getValue(COREMOD_CONTAINS_FML_MOD) != null) {
                forceReparseableFiles.add(jar.getName());
            }
        }
        String coremod = attributes.getValue(FML_CORE_PLUGIN);
        String tweaker = attributes.getValue(ManifestAttributes.TWEAKER);
        if (coremod != null && ALLOWED_TWEAKERS.contains(tweaker)) {
            droppedCoremods.put(jar.getAbsoluteFile(), coremod);
        }
    }


    private static final class CapturedTweakClasses extends ArrayList<String> {

        private final List<String> capturedTweakClasses = new ArrayList<>();

        private CapturedTweakClasses(List<String> tweakClasses) {
            super(tweakClasses == null ? Collections.emptyList() : tweakClasses);
        }

        // Captures instead of forwarding, so rescued coremods' tweaks can be replayed in order
        @Override
        public boolean add(String tweakClass) {
            this.capturedTweakClasses.add(tweakClass);
            return super.add(tweakClass);
        }

        // Captures; index is preserved for the replay
        @Override
        public void add(int index, String tweakClass) {
            this.capturedTweakClasses.add(tweakClass);
            super.add(index, tweakClass);
        }

        // Captures the whole batch
        @Override
        public boolean addAll(Collection<? extends String> tweakClasses) {
            this.capturedTweakClasses.addAll(tweakClasses);
            return super.addAll(tweakClasses);
        }

        // Captures the whole batch at the given index
        @Override
        public boolean addAll(int index, Collection<? extends String> tweakClasses) {
            this.capturedTweakClasses.addAll(tweakClasses);
            return super.addAll(index, tweakClasses);
        }

        // Drains what was captured since the wrapper was installed
        private List<String> getCapturedTweakClasses() {
            return this.capturedTweakClasses;
        }
    }

    // Reads modid from a class's @Mod annotation. An annotation element is always a compile-time constant, so
    // the value is read straight from the bytecode without loading the class
    private static class ModAnnotationVisitor extends ClassVisitor {

        private static final String MOD_ANNOTATION = "Lnet/minecraftforge/fml/common/Mod;";

        private String modId;

        private ModAnnotationVisitor() {
            super(Opcodes.ASM5);
        }

        // Only descends into the @Mod annotation
        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (!MOD_ANNOTATION.equals(descriptor)) {
                return null;
            }
            return new AnnotationVisitor(Opcodes.ASM5) {
                // Picks the modid value out of the annotation
                @Override
                public void visit(String name, Object value) {
                    if ("modid".equals(name) && value instanceof String) {
                        ModAnnotationVisitor.this.modId = (String) value;
                        throw new ExitVisitException();
                    }
                }
            };
        }
    }

    // Thrown to abort ClassReader#accept the moment a modid is read so the rest of the class is not visited.
    // Carries no stacktrace, for control flow and not a real exception
    private static class ExitVisitException extends RuntimeException {

        private ExitVisitException() {
            super(null, null, false, false);
        }

    }

}
