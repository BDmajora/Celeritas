package com.bdmajora.extras;

import com.bdmajora.extras.client.particle.ParticleClassRegistry;
import com.github.bsideup.jabel.Desugar;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.common.config.Configuration;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Every setting the Extras page owns, persisted to {@code config/impetus-extras.cfg}.
 *
 * <p>Unlike {@code FulgorConfig} and {@code CoartatioConfig}, nothing here is read during coremod
 * setup — every Extras mixin consults its switch at call time, so the switches are live and this can
 * be a normal Forge {@link Configuration} rather than a {@link java.util.Properties} file that
 * {@link net.minecraft.launchwrapper.Launch} can read before Minecraft exists.
 *
 * <p>Simple booleans and bounded integers are declared in the {@link BooleanProperty} and
 * {@link IntProperty} tables so load and save stay in lockstep; enums (stored by ordinal) and the
 * particle-class lists are handled explicitly in {@link #loadFrom(Configuration)} and
 * {@link #writeChanges()}. <b>Enum constant order is part of the on-disk format</b> — appending is
 * safe, reordering silently changes what a saved config means.
 */
public final class ExtrasConfig {
    private static final String CAT_ANIMATION = "animation";
    private static final String CAT_PARTICLE = "particle";
    private static final String CAT_PARTICLE_CLASSES = "particle_classes";
    private static final String CAT_DETAIL = "detail";
    private static final String CAT_RENDER = "render";
    private static final String CAT_EXTRA = "extra";

    public final AnimationSettings animation = new AnimationSettings();
    public final ParticleSettings particle = new ParticleSettings();
    public final DetailSettings detail = new DetailSettings();
    public final RenderSettings render = new RenderSettings();
    public final ExtraSettings extra = new ExtraSettings();

    private final List<BooleanProperty> booleans = Arrays.asList(
            // --- Animations -------------------------------------------------------------------
            bool(CAT_ANIMATION, "animation", true, "Master switch for all texture animations",
                    v -> animation.all = v, () -> animation.all),
            bool(CAT_ANIMATION, "water", true, "Animate water textures",
                    v -> animation.water = v, () -> animation.water),
            bool(CAT_ANIMATION, "lava", true, "Animate lava textures",
                    v -> animation.lava = v, () -> animation.lava),
            bool(CAT_ANIMATION, "fire", true, "Animate fire textures",
                    v -> animation.fire = v, () -> animation.fire),
            bool(CAT_ANIMATION, "portal", true, "Animate nether portal textures",
                    v -> animation.portal = v, () -> animation.portal),
            bool(CAT_ANIMATION, "blockAnimations", true, "Animate other block textures",
                    v -> animation.blockAnimations = v, () -> animation.blockAnimations),
            // OptiFine splits these out of the general block-animation switch.
            bool(CAT_ANIMATION, "redstone", true, "Animate redstone textures",
                    v -> animation.redstone = v, () -> animation.redstone),
            bool(CAT_ANIMATION, "explosion", true, "Animate explosion textures",
                    v -> animation.explosion = v, () -> animation.explosion),
            bool(CAT_ANIMATION, "flame", true, "Animate flame textures",
                    v -> animation.flame = v, () -> animation.flame),
            bool(CAT_ANIMATION, "smoke", true, "Animate smoke textures",
                    v -> animation.smoke = v, () -> animation.smoke),
            bool(CAT_ANIMATION, "sculkSensor", true, "Animate sculk-sensor-style textures (modded only on 1.12.2)",
                    v -> animation.sculkSensor = v, () -> animation.sculkSensor),

            // --- Particles --------------------------------------------------------------------
            bool(CAT_PARTICLE, "particles", true, "Master switch for all particles",
                    v -> particle.all = v, () -> particle.all),
            bool(CAT_PARTICLE, "rainSplash", true, "Rain splash particles",
                    v -> particle.rainSplash = v, () -> particle.rainSplash),
            bool(CAT_PARTICLE, "blockBreak", true, "Block break particles",
                    v -> particle.blockBreak = v, () -> particle.blockBreak),
            bool(CAT_PARTICLE, "blockBreaking", true, "Block breaking (mining) particles",
                    v -> particle.blockBreaking = v, () -> particle.blockBreaking),
            bool(CAT_PARTICLE, "voidParticles", true, "Void particles near the world bottom",
                    v -> particle.voidParticles = v, () -> particle.voidParticles),
            bool(CAT_PARTICLE, "waterParticles", true, "Water drip, splash and suspended particles",
                    v -> particle.waterParticles = v, () -> particle.waterParticles),
            bool(CAT_PARTICLE, "portalParticles", true, "Nether portal particles",
                    v -> particle.portalParticles = v, () -> particle.portalParticles),
            bool(CAT_PARTICLE, "potionParticles", true, "Potion effect particles",
                    v -> particle.potionParticles = v, () -> particle.potionParticles),
            bool(CAT_PARTICLE, "drippingWaterLava", true, "Dripping water and lava particles",
                    v -> particle.drippingWaterLava = v, () -> particle.drippingWaterLava),
            bool(CAT_PARTICLE, "fireworkParticles", true, "Firework spark particles",
                    v -> particle.fireworkParticles = v, () -> particle.fireworkParticles),

            // --- Details ----------------------------------------------------------------------
            bool(CAT_DETAIL, "sky", true, "Render the sky box",
                    v -> detail.sky = v, () -> detail.sky),
            bool(CAT_DETAIL, "stars", true, "Render stars",
                    v -> detail.stars = v, () -> detail.stars),
            bool(CAT_DETAIL, "sun", true, "Render the sun",
                    v -> detail.sun = v, () -> detail.sun),
            bool(CAT_DETAIL, "moon", true, "Render the moon",
                    v -> detail.moon = v, () -> detail.moon),
            bool(CAT_DETAIL, "rainSnow", true, "Render falling rain and snow",
                    v -> detail.rainSnow = v, () -> detail.rainSnow),
            bool(CAT_DETAIL, "biomeColors", true, "Biome-specific grass, foliage and water tint",
                    v -> detail.biomeColors = v, () -> detail.biomeColors),
            bool(CAT_DETAIL, "skyColors", true, "Biome-specific sky colour",
                    v -> detail.skyColors = v, () -> detail.skyColors),
            bool(CAT_DETAIL, "swampColors", true, "Swamp's darkened grass and foliage tint",
                    v -> detail.swampColors = v, () -> detail.swampColors),
            bool(CAT_DETAIL, "voidFog", true, "Void fog near the world bottom",
                    v -> detail.voidFog = v, () -> detail.voidFog),
            bool(CAT_DETAIL, "showCapes", true, "Render player capes",
                    v -> detail.showCapes = v, () -> detail.showCapes),
            bool(CAT_DETAIL, "heldItemTooltips", true, "Show the item name popup when switching hotbar slots",
                    v -> detail.heldItemTooltips = v, () -> detail.heldItemTooltips),

            // --- Render -----------------------------------------------------------------------
            bool(CAT_RENDER, "fog", true, "Render atmospheric fog",
                    v -> render.fog = v, () -> render.fog),
            bool(CAT_RENDER, "lightUpdates", true, "Process client-side light updates",
                    v -> render.lightUpdates = v, () -> render.lightUpdates),
            bool(CAT_RENDER, "itemFrames", true, "Render item frames",
                    v -> render.itemFrames = v, () -> render.itemFrames),
            bool(CAT_RENDER, "armorStands", true, "Render armor stands",
                    v -> render.armorStands = v, () -> render.armorStands),
            bool(CAT_RENDER, "paintings", true, "Render paintings",
                    v -> render.paintings = v, () -> render.paintings),
            bool(CAT_RENDER, "pistons", true, "Render moving pistons",
                    v -> render.pistons = v, () -> render.pistons),
            bool(CAT_RENDER, "beacons", true, "Render beacon beams",
                    v -> render.beacons = v, () -> render.beacons),
            bool(CAT_RENDER, "limitBeaconBeamHeight", false, "Stop beacon beams at the world ceiling",
                    v -> render.limitBeaconBeamHeight = v, () -> render.limitBeaconBeamHeight),
            bool(CAT_RENDER, "enchantingTableBooks", true, "Render the enchanting table's floating book",
                    v -> render.enchantingTableBooks = v, () -> render.enchantingTableBooks),
            bool(CAT_RENDER, "playerNameTag", true, "Render player name tags",
                    v -> render.playerNameTag = v, () -> render.playerNameTag),
            bool(CAT_RENDER, "itemFrameNameTag", true, "Render item frame name tags",
                    v -> render.itemFrameNameTag = v, () -> render.itemFrameNameTag),
            bool(CAT_RENDER, "droppedItemsFancy", true,
                    "Stack up to five models per dropped item pile instead of always drawing one",
                    v -> render.droppedItemsFancy = v, () -> render.droppedItemsFancy),
            bool(CAT_RENDER, "preventShaders", false, "Block the vanilla post-processing shader pipeline",
                    v -> render.preventShaders = v, () -> render.preventShaders),
            bool(CAT_RENDER, "profileEntityRendering", false,
                    "Break entity and block-entity rendering out by type in the F3 profiler graph",
                    v -> render.profileEntityRendering = v, () -> render.profileEntityRendering),

            // --- Extra ------------------------------------------------------------------------
            bool(CAT_EXTRA, "showFps", false, "Show the FPS overlay",
                    v -> extra.showFps = v, () -> extra.showFps),
            bool(CAT_EXTRA, "showFPSExtended", true, "Include average, 1% low and 0.1% low in the FPS overlay",
                    v -> extra.showFpsExtended = v, () -> extra.showFpsExtended),
            bool(CAT_EXTRA, "showCoords", false, "Show the coordinates overlay",
                    v -> extra.showCoords = v, () -> extra.showCoords),
            bool(CAT_EXTRA, "ignoreReducedDebugInfo", false,
                    "Show coordinates even when the server sets the reducedDebugInfo game rule",
                    v -> extra.ignoreReducedDebugInfo = v, () -> extra.ignoreReducedDebugInfo),
            bool(CAT_EXTRA, "steadyDebugHud", true, "Throttle how often the F3 overlay text is rebuilt",
                    v -> extra.steadyDebugHud = v, () -> extra.steadyDebugHud),
            bool(CAT_EXTRA, "useAdaptiveSync", false, "Use adaptive VSync (swap interval -1) when supported",
                    v -> extra.useAdaptiveSync = v, () -> extra.useAdaptiveSync),
            bool(CAT_EXTRA, "toasts", true, "Master switch for toast pop-ups",
                    v -> extra.toasts = v, () -> extra.toasts),
            bool(CAT_EXTRA, "toastAdvancement", true, "Advancement toasts",
                    v -> extra.toastAdvancement = v, () -> extra.toastAdvancement),
            bool(CAT_EXTRA, "toastRecipe", true, "Recipe unlock toasts",
                    v -> extra.toastRecipe = v, () -> extra.toastRecipe),
            bool(CAT_EXTRA, "toastTutorial", true, "Tutorial toasts",
                    v -> extra.toastTutorial = v, () -> extra.toastTutorial),
            bool(CAT_EXTRA, "toastSystem", true, "System toasts",
                    v -> extra.toastSystem = v, () -> extra.toastSystem),
            bool(CAT_EXTRA, "modNameTooltip", false, "Append the source mod's name to item tooltips",
                    v -> extra.modNameTooltip = v, () -> extra.modNameTooltip),
            bool(CAT_EXTRA, "paniniProjection", false, "Apply a Panini projection post-effect to widen the view",
                    v -> extra.paniniProjection = v, () -> extra.paniniProjection)
    );

    private final List<IntProperty> integers = Arrays.asList(
            new IntProperty(CAT_DETAIL, "totalStars", DetailSettings.STARS_DEFAULT,
                    DetailSettings.STARS_MIN, DetailSettings.STARS_MAX, "Number of stars to generate",
                    v -> detail.totalStars = v, () -> detail.totalStars),
            new IntProperty(CAT_RENDER, "fogStart", 100, 0, 200,
                    "Fog start distance as a percentage of the fog end (100 = vanilla)",
                    v -> render.fogStart = v, () -> render.fogStart),
            new IntProperty(CAT_RENDER, "fogDistance", 0, 0, 32,
                    "Fog distance in chunks (0 = follow render distance)",
                    v -> render.fogDistance = v, () -> render.fogDistance),
            new IntProperty(CAT_RENDER, "cloudScale", RenderSettings.CLOUD_SCALE_VANILLA,
                    RenderSettings.CLOUD_SCALE_MIN, RenderSettings.CLOUD_SCALE_MAX,
                    "Cloud scale in quarter steps (1 = 0.25x, 4 = vanilla, 16 = 4.00x)",
                    v -> render.cloudScale = v, () -> render.cloudScale),
            new IntProperty(CAT_RENDER, "itemFrameLodDistance", 0, 0, 256,
                    "Distance in blocks past which framed items lose their side faces (0 = off)",
                    v -> render.itemFrameLodDistance = v, () -> render.itemFrameLodDistance),
            new IntProperty(CAT_EXTRA, "steadyDebugHudRefreshInterval",
                    ExtraSettings.STEADY_HUD_REFRESH_DEFAULT, ExtraSettings.STEADY_HUD_REFRESH_MIN,
                    ExtraSettings.STEADY_HUD_REFRESH_MAX, "F3 overlay rebuild interval in ticks",
                    v -> extra.steadyDebugHudRefreshInterval = v, () -> extra.steadyDebugHudRefreshInterval),
            new IntProperty(CAT_EXTRA, "paniniProjectionStrength", 25, 0, 100,
                    "Panini projection strength as a percentage",
                    v -> extra.paniniProjectionStrength = v, () -> extra.paniniProjectionStrength),
            new IntProperty(CAT_EXTRA, "autosaveInterval", ExtraSettings.AUTOSAVE_VANILLA_TICKS,
                    ExtraSettings.AUTOSAVE_MIN_TICKS, ExtraSettings.AUTOSAVE_MAX_TICKS,
                    "Singleplayer autosave interval in ticks (900 = vanilla)",
                    v -> extra.autosaveInterval = v, () -> extra.autosaveInterval)
    );

    private Configuration config;

    /**
     * Reads {@code file}, adding any keys it does not yet contain.
     *
     * <p>A read failure yields a fresh defaults instance that is deliberately <em>not</em> written
     * back: overwriting a config we failed to parse would destroy the user's settings along with
     * whatever confused us.
     */
    public static ExtrasConfig load(File file) {
        ExtrasConfig options = new ExtrasConfig();
        Configuration config = new Configuration(file);

        try {
            config.load();
            options.config = config;
            options.loadFrom(config);
            if (config.hasChanged()) {
                config.save();
            }
            return options;
        } catch (Exception e) {
            Extras.LOGGER.error("Could not read {}, falling back to defaults", file, e);
            ExtrasConfig defaults = new ExtrasConfig();
            defaults.config = config;
            return defaults;
        }
    }

    private void loadFrom(Configuration config) {
        booleans.forEach(property -> property.load(config));
        integers.forEach(property -> property.load(config));

        render.cloudTranslucency = readEnum(config, CAT_RENDER, "cloudTranslucency",
                CloudTranslucency.values(), CloudTranslucency.DEFAULT,
                "Cloud translucency mode (0 = Default, 1 = Always, 2 = Never)");
        render.fogShape = readEnum(config, CAT_RENDER, "fogShape",
                FogShape.values(), FogShape.VANILLA,
                "Terrain fog shape (0 = Vanilla, 1 = Cylindrical, 2 = Radial, 3 = Planar)");
        extra.overlayCorner = readEnum(config, CAT_EXTRA, "overlayCorner",
                OverlayCorner.values(), OverlayCorner.TOP_LEFT,
                "Overlay corner (0 = Top Left, 1 = Top Right, 2 = Bottom Left, 3 = Bottom Right)");
        extra.textContrast = readEnum(config, CAT_EXTRA, "textContrast",
                TextContrast.values(), TextContrast.SHADOW,
                "Overlay text contrast (0 = None, 1 = Background, 2 = Shadow)");
        extra.timeOverride = readEnum(config, CAT_EXTRA, "timeOverride",
                TimeOverride.values(), TimeOverride.DEFAULT,
                "Client-side time of day (0 = Default, 1 = Day only, 2 = Night only). Creative/cheats only.");
        extra.weatherOverride = readEnum(config, CAT_EXTRA, "weatherOverride",
                WeatherOverride.values(), WeatherOverride.DEFAULT,
                "Client-side weather (0 = Default, 1 = Clear, 2 = Rain, 3 = Thunder). Creative/cheats only.");

        ParticleClassRegistry registry = ParticleClassRegistry.getInstance();
        registry.loadDisabledClasses(config.getStringList("disabledClasses", CAT_PARTICLE_CLASSES,
                new String[0], "Particle classes the user has switched off"));
        registry.loadDiscoveredClasses(config.getStringList("discoveredClasses", CAT_PARTICLE_CLASSES,
                new String[0], "Cache of discovered particle classes; rebuilt automatically"));
    }

    /** Flushes every setting back to disk. */
    public void writeChanges() {
        if (config == null) {
            return;
        }

        booleans.forEach(property -> property.save(config));
        integers.forEach(property -> property.save(config));

        config.get(CAT_RENDER, "cloudTranslucency", 0).set(render.cloudTranslucency.ordinal());
        config.get(CAT_RENDER, "fogShape", 0).set(render.fogShape.ordinal());
        config.get(CAT_EXTRA, "overlayCorner", 0).set(extra.overlayCorner.ordinal());
        config.get(CAT_EXTRA, "textContrast", TextContrast.SHADOW.ordinal()).set(extra.textContrast.ordinal());
        config.get(CAT_EXTRA, "timeOverride", 0).set(extra.timeOverride.ordinal());
        config.get(CAT_EXTRA, "weatherOverride", 0).set(extra.weatherOverride.ordinal());

        ParticleClassRegistry registry = ParticleClassRegistry.getInstance();
        config.get(CAT_PARTICLE_CLASSES, "disabledClasses", new String[0])
                .set(registry.getDisabledClassesArray());
        config.get(CAT_PARTICLE_CLASSES, "discoveredClasses", new String[0])
                .set(registry.getDiscoveredClassesArray());

        config.save();
        registry.markClean();
    }

    private static <T extends Enum<T>> T readEnum(Configuration config, String category, String key,
                                                  T[] values, T fallback, String comment) {
        int ordinal = config.getInt(key, category, fallback.ordinal(), 0, values.length - 1, comment);
        return values[ordinal];
    }

    private BooleanProperty bool(String category, String key, boolean defaultValue, String comment,
                                 Consumer<Boolean> setter, Supplier<Boolean> getter) {
        return new BooleanProperty(category, key, defaultValue, comment, setter, getter);
    }

    // ----------------------------------------------------------------------------------------
    // Enums. Persisted by ordinal — append only.
    // ----------------------------------------------------------------------------------------

    /** Where the FPS/coordinate overlay is anchored. */
    public enum OverlayCorner implements Localized {
        TOP_LEFT("impetus.options.extras.overlay_corner.top_left"),
        TOP_RIGHT("impetus.options.extras.overlay_corner.top_right"),
        BOTTOM_LEFT("impetus.options.extras.overlay_corner.bottom_left"),
        BOTTOM_RIGHT("impetus.options.extras.overlay_corner.bottom_right");

        private final String key;

        OverlayCorner(String key) {
            this.key = key;
        }

        @Override
        public String translationKey() {
            return this.key;
        }

        public boolean isBottom() {
            return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
        }

        public boolean isRight() {
            return this == TOP_RIGHT || this == BOTTOM_RIGHT;
        }
    }

    /** How overlay text is made readable against the world behind it. */
    public enum TextContrast implements Localized {
        NONE("impetus.options.extras.text_contrast.none"),
        BACKGROUND("impetus.options.extras.text_contrast.background"),
        SHADOW("impetus.options.extras.text_contrast.shadow");

        private final String key;

        TextContrast(String key) {
            this.key = key;
        }

        @Override
        public String translationKey() {
            return this.key;
        }
    }

    /** When clouds fade to translucent. */
    public enum CloudTranslucency implements Localized {
        DEFAULT("impetus.options.extras.cloud_translucency.default"),
        ALWAYS("impetus.options.extras.cloud_translucency.always"),
        NEVER("impetus.options.extras.cloud_translucency.never");

        private final String key;

        CloudTranslucency(String key) {
            this.key = key;
        }

        @Override
        public String translationKey() {
            return this.key;
        }
    }

    /**
     * The distance metric the terrain shader fogs by.
     *
     * <p>Same four names Sodium Extra uses, but not the same four formulas: Sodium's baseline is
     * cylindrical, so its "Radial" is the spherical one. Impetus already fogs spherically (which is
     * what fixed-function GL does, and therefore what entities and particles do), so VANILLA is the
     * spherical case here and RADIAL is the genuinely different horizontal-only one. Picking any
     * non-VANILLA shape means terrain and entities no longer agree at the same distance.
     *
     * <p>The ordinals are the {@code u_FogShape} values consumed by {@code fog.glsl}.
     */
    public enum FogShape implements Localized {
        VANILLA("impetus.options.extras.fog_shape.vanilla"),
        CYLINDRICAL("impetus.options.extras.fog_shape.cylindrical"),
        RADIAL("impetus.options.extras.fog_shape.radial"),
        PLANAR("impetus.options.extras.fog_shape.planar");

        private final String key;

        FogShape(String key) {
            this.key = key;
        }

        @Override
        public String translationKey() {
            return this.key;
        }

        /** The {@code u_FogShape} uniform value; see {@code assets/impetus/shaders/include/fog.glsl}. */
        public int shaderIndex() {
            return this.ordinal();
        }
    }

    /** Client-side time-of-day lock. */
    public enum TimeOverride implements Localized {
        DEFAULT("impetus.options.extras.time.default"),
        DAY("impetus.options.extras.time.day"),
        NIGHT("impetus.options.extras.time.night");

        private final String key;

        TimeOverride(String key) {
            this.key = key;
        }

        @Override
        public String translationKey() {
            return this.key;
        }
    }

    /** Client-side weather lock. */
    public enum WeatherOverride implements Localized {
        DEFAULT("impetus.options.extras.weather.default"),
        CLEAR("impetus.options.extras.weather.clear"),
        RAIN("impetus.options.extras.weather.rain"),
        THUNDER("impetus.options.extras.weather.thunder");

        private final String key;

        WeatherOverride(String key) {
            this.key = key;
        }

        @Override
        public String translationKey() {
            return this.key;
        }
    }

    /** Vertical sync mode, folding adaptive sync in beside the vanilla on/off pair. */
    public enum VerticalSync implements Localized {
        OFF("options.off"),
        ON("options.on"),
        ADAPTIVE("impetus.options.extras.vertical_sync.adaptive");

        private final String key;

        VerticalSync(String key) {
            this.key = key;
        }

        @Override
        public String translationKey() {
            return this.key;
        }
    }

    /** Something with a lang key; lets the option page build cycling controls generically. */
    public interface Localized {
        String translationKey();

        default String localizedName() {
            return I18n.format(this.translationKey());
        }
    }

    // ----------------------------------------------------------------------------------------
    // Setting groups
    // ----------------------------------------------------------------------------------------

    /**
     * Texture animation switches. {@link #all} gates every other field here; the finer switches
     * below {@link #blockAnimations} are the ones OptiFine breaks out separately.
     */
    public static final class AnimationSettings {
        public boolean all = true;
        public boolean water = true;
        public boolean lava = true;
        public boolean fire = true;
        public boolean portal = true;
        public boolean blockAnimations = true;
        public boolean redstone = true;
        public boolean explosion = true;
        public boolean flame = true;
        public boolean smoke = true;
        public boolean sculkSensor = true;
    }

    /**
     * Particle switches. {@link #all} gates everything; the named switches cover the effects
     * OptiFine and Sodium Extra expose, and anything else is reachable through the per-class
     * toggles built from {@link ParticleClassRegistry}.
     */
    public static final class ParticleSettings {
        public boolean all = true;
        public boolean rainSplash = true;
        public boolean blockBreak = true;
        public boolean blockBreaking = true;
        public boolean voidParticles = true;
        public boolean waterParticles = true;
        public boolean portalParticles = true;
        public boolean potionParticles = true;
        public boolean drippingWaterLava = true;
        public boolean fireworkParticles = true;
    }

    /** Celestial and environmental detail switches. */
    public static final class DetailSettings {
        public static final int STARS_MIN = 500;
        public static final int STARS_DEFAULT = 1500;
        public static final int STARS_MAX = 32000;

        public boolean sky = true;
        public boolean stars = true;
        public int totalStars = STARS_DEFAULT;
        public boolean sun = true;
        public boolean moon = true;
        public boolean rainSnow = true;
        public boolean biomeColors = true;
        public boolean skyColors = true;
        public boolean swampColors = true;
        public boolean voidFog = true;
        public boolean showCapes = true;
        public boolean heldItemTooltips = true;
    }

    /**
     * World-render switches and the fog/cloud tuning values.
     *
     * <p>Cloud <em>height</em> and <em>distance</em> are deliberately absent: Impetus already owns
     * both on its Quality page, where its own cloud renderer consumes them. Adding a second copy
     * here would give the user two sliders for one value and no way to tell which one wins.
     */
    public static final class RenderSettings {
        public static final int CLOUD_SCALE_MIN = 1;
        /** The internal scale value that reproduces vanilla's 1.00x cloud size. */
        public static final int CLOUD_SCALE_VANILLA = 4;
        public static final int CLOUD_SCALE_MAX = 16;

        public boolean fog = true;
        public int fogStart = 100;
        public int fogDistance = 0;
        public FogShape fogShape = FogShape.VANILLA;
        public int cloudScale = CLOUD_SCALE_VANILLA;
        public CloudTranslucency cloudTranslucency = CloudTranslucency.DEFAULT;
        public boolean lightUpdates = true;
        public boolean itemFrames = true;
        public int itemFrameLodDistance = 0;
        public boolean itemFrameNameTag = true;
        public boolean armorStands = true;
        public boolean paintings = true;
        public boolean pistons = true;
        public boolean beacons = true;
        public boolean limitBeaconBeamHeight = false;
        public boolean enchantingTableBooks = true;
        public boolean playerNameTag = true;
        public boolean droppedItemsFancy = true;
        public boolean preventShaders = false;
        public boolean profileEntityRendering = false;
    }

    /** Overlay, toast and quality-of-life settings. */
    public static final class ExtraSettings {
        public static final int STEADY_HUD_REFRESH_MIN = 1;
        public static final int STEADY_HUD_REFRESH_DEFAULT = 1;
        public static final int STEADY_HUD_REFRESH_MAX = 20;
        /** {@code MinecraftServer.tick} autosaves every 900 ticks. */
        public static final int AUTOSAVE_VANILLA_TICKS = 900;
        public static final int AUTOSAVE_MIN_TICKS = 0;
        public static final int AUTOSAVE_MAX_TICKS = 36000;

        public boolean showFps = false;
        public boolean showFpsExtended = true;
        public boolean showCoords = false;
        public boolean ignoreReducedDebugInfo = false;
        public OverlayCorner overlayCorner = OverlayCorner.TOP_LEFT;
        public TextContrast textContrast = TextContrast.SHADOW;
        public boolean steadyDebugHud = true;
        public int steadyDebugHudRefreshInterval = STEADY_HUD_REFRESH_DEFAULT;
        public boolean useAdaptiveSync = false;
        public boolean toasts = true;
        public boolean toastAdvancement = true;
        public boolean toastRecipe = true;
        public boolean toastTutorial = true;
        public boolean toastSystem = true;
        public boolean modNameTooltip = false;
        public boolean paniniProjection = false;
        public int paniniProjectionStrength = 25;
        public TimeOverride timeOverride = TimeOverride.DEFAULT;
        public WeatherOverride weatherOverride = WeatherOverride.DEFAULT;
        public int autosaveInterval = AUTOSAVE_VANILLA_TICKS;
    }

    // ----------------------------------------------------------------------------------------
    // Declarative property bindings
    // ----------------------------------------------------------------------------------------

    /** A boolean config entry bound to its in-memory field, so load and save cannot drift apart. */
    @Desugar
    private record BooleanProperty(String category, String key, boolean defaultValue, String comment,
                                   Consumer<Boolean> setter, Supplier<Boolean> getter) {
        void load(Configuration config) {
            setter.accept(config.getBoolean(key, category, defaultValue, comment));
        }

        void save(Configuration config) {
            config.get(category, key, defaultValue).set(getter.get());
        }
    }

    /** As {@link BooleanProperty}, with an inclusive range {@link Configuration} clamps to on load. */
    @Desugar
    private record IntProperty(String category, String key, int defaultValue, int min, int max, String comment,
                               Consumer<Integer> setter, Supplier<Integer> getter) {
        void load(Configuration config) {
            setter.accept(config.getInt(key, category, defaultValue, min, max, comment));
        }

        void save(Configuration config) {
            config.get(category, key, defaultValue).set(getter.get());
        }
    }
}
