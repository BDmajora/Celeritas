package com.bdmajora.impetus.booter.service;

import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.relauncher.CoreModManager;
import org.spongepowered.asm.launch.platform.container.ContainerHandleURI;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.obfuscation.mapping.remap.CleanroomRemapper;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.mojang.AbstractMixinServiceLaunchWrapper;
import org.spongepowered.asm.service.mojang.MixinAuditFile;
import com.bdmajora.impetus.booter.Tags;
import com.bdmajora.impetus.booter.util.Environment;
import com.bdmajora.impetus.booter.util.Srg2NotchRemapper;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MixinBooterService extends AbstractMixinServiceLaunchWrapper {

    public static final String AUDIT_PROPERTY = Tags.MOD_ID + ".auditTrail";

    private static final MixinAuditFile AUDIT_FILE = new MixinAuditFile(Tags.MOD_ID + ".log", AUDIT_PROPERTY);

    private final ClassProvider classProvider = new ClassProvider();
    private final TransformerProvider transformerProvider = new TransformerProvider();
    private final ClassLoaderUtil classLoaderUtil = new ClassLoaderUtil();
    private final BytecodeProvider bytecodeProvider = new BytecodeProvider(this.transformerProvider, this.getReEntranceLock(), this.classLoaderUtil);

    private boolean initialized;

    // The shared mixin log, also written to by ClassLoadTracer and the teeing
    // org.spongepowered.asm.service.mojang.Log4j2AuditingAdapter
    public static MixinAuditFile auditFile() {
        return AUDIT_FILE;
    }

    // Shown in Mixin's startup banner as the service name
    @Override
    public String getName() {
        return Tags.MOD_NAME;
    }

    // Dev when launched through GradleStart; changes logging and export defaults
    @Override
    protected boolean isDevelopment() {
        return Environment.inDev();
    }

    // CLIENT or SERVER, from the primary tweaker
    @Override
    public String getSideName() {
        return Environment.side();
    }

    // The mirrored mixin log, or null when disabled in config
    @Override
    protected MixinAuditFile createAuditLog() {
        return AUDIT_FILE;
    }

    // Class lookup through LaunchClassLoader
    @Override
    public IClassProvider getClassProvider() {
        return this.classProvider;
    }

    // Bytecode lookup with optional transformer application
    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return this.bytecodeProvider;
    }

    // The LaunchWrapper transformers Mixin should delegate to
    @Override
    public ITransformerProvider getTransformerProvider() {
        return this.transformerProvider;
    }

    // Tracks loaded and invalid classes against LaunchClassLoader's own sets
    @Override
    public IClassTracker getClassTracker() {
        return this.classLoaderUtil;
    }

    // Hook for cache invalidation; nothing here needs it
    @Override
    protected void onRefresh() {
        this.transformerProvider.refreshDelegatedTransformers();
    }

    // Installs the INIT trigger once PREINIT starts, and uninstalls it after
    @Override
    public void beginPhase() {
        super.beginPhase();
        if (MixinEnvironment.Phase.INIT.hasReached()) {
            InitPhaseTrigger.uninstall();
        }
    }

    // Advances to MixinEnvironment.Phase#INIT INIT, called by InitPhaseTrigger from within FMLDeobfTweaker. A
    // no-op if the environment has already moved past it
    void gotoInitPhase() {
        if (this.phaseTransitioner != null) {
            this.phaseTransitioner.accept(MixinEnvironment.Phase.INIT);
        }
    }

    // Registers the platform agent and prepares the service for the first transformation
    @Override
    public void init() {
        if (this.initialized) {
            return;
        }
        this.initialized = true;
        super.init();
        this.getTransformerProvider().addTransformerExclusion("com.bdmajora.impetus.booter.service.ClassLoadTracer");
        MixinEnvironment.getDefaultEnvironment().getRemappers().add(new CleanroomRemapper<>(new Srg2NotchRemapper()));
        if (Environment.inDev()) { // RFG
            Mixins.addConfiguration("mixin.mixinbooter.init.json");
        }
    }

    // Every jar with a MixinConfigs or MixinConnector manifest entry, found by ModDiscoverer
    @Override
    public Collection<IContainerHandle> getMixinContainers() {
        List<IContainerHandle> containers = new ArrayList<>();
        Set<File> jars = ModDiscoverer.manifestMixinJars();
        if (jars.isEmpty()) {
            return containers;
        }
        ILogger logger = getLogger(Tags.MOD_NAME);
        Set<String> existingJars = new HashSet<>(CoreModManager.getIgnoredMods());
        existingJars.addAll(CoreModManager.getReparseableCoremods());
        for (File jar : jars) {
            containers.add(new ContainerHandleURI(jar.toURI()));
            if (existingJars.contains(jar.getName())) {
                continue;
            }
            try {
                Launch.classLoader.addURL(jar.toURI().toURL());
                if (ModDiscoverer.isModDirMixinJar(jar)) {
                    CoreModManager.getReparseableCoremods().add(jar.getName());
                }
                logger.info("Added {} to the classloader to process its mixin manifest attributes.", jar.getName());
            } catch (Exception e) {
                logger.error("Failed to add {} to the classloader to process its mixin manifest attributes.", jar.getName(), e);
            }
        }
        return containers;
    }

    // Maps a jar URI to the mod id that owns it, for log lines and crash reports
    @Override
    protected String resolveSourceId(URI source) {
        if ("file".equals(source.getScheme())) {
            try {
                return ModDiscoverer.getModFromSource(new File(source));
            } catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    // The Impetus jar itself
    @Override
    public IContainerHandle getPrimaryContainer() {
        InitPhaseTrigger.install();
        return super.getPrimaryContainer();
    }

}
