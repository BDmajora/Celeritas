package com.bdmajora.impetus.iris.gl;

import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL13;
import net.minecraft.client.renderer.GlStateManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The single place that is allowed to move the texture-unit selector or bind a 2D texture on behalf of the pipeline.
 *
 * <h2>Why this class exists</h2>
 * MC 1.12.2's {@link GlStateManager} caches texture state, and it caches it against its <em>own</em> idea of which
 * unit is selected:
 * <pre>{@code
 * public static void bindTexture(int texture) {
 *     if (texture != textureState[activeTextureUnit].textureName) {  // reads the CACHED unit
 *         textureState[activeTextureUnit].textureName = texture;     // records against the CACHED unit
 *         GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);           // affects the REAL unit
 *     }
 * }
 * }</pre>
 * There are therefore two distinct ways to desync it, and both have bitten this port:
 * <ol>
 *   <li><b>Selector desync</b> — raw {@code glActiveTexture} moves real GL without telling the cache. Because
 *       {@code setActiveTexture} is <em>itself</em> cached, the obvious repair
 *       ({@code GlStateManager.setActiveTexture(GL_TEXTURE0)}) can be swallowed as a no-op, leaving real GL parked on
 *       a high unit forever.</li>
 *   <li><b>Binding desync</b> — raw {@code glBindTexture} on a unit inside the cache's range changes the real binding
 *       without updating the record. Every later {@code GlStateManager.bindTexture} of the value the cache still
 *       believes is bound then no-ops, and the unit keeps the wrong texture indefinitely.</li>
 * </ol>
 * Either one makes {@code Framebuffer.bindFramebufferTexture()} — the fullscreen blit that puts the world on screen —
 * silently do nothing, so the blit samples whatever genuinely occupies the unit. A depth or shadow texture read as
 * colour is 1.0 across the board: a white screen every frame until a restart. That is not hypothetical; it is the F2
 * white-screen bug, and vanilla's {@code ScreenShotHelper.createScreenshot} is one of the cached binds that trips it.
 *
 * <h2>How the reference implementations avoid it</h2>
 * Neither reference ever lets the cache diverge:
 * <ul>
 *   <li><b>OptiFine</b> (same MC version) enlarges {@code GlStateManager.textureState} from 8 slots to 32 and routes
 *       <em>every</em> unit through {@code GlStateManager.setActiveTexture} — 48 call sites, zero raw
 *       {@code glActiveTexture} anywhere in its shader code. Its tail idiom is
 *       {@code GlStateManager.setActiveTexture(GL_TEXTURE0)}, i.e. finish on unit 0, which is safe there precisely
 *       because its cache is never out of step.</li>
 *   <li><b>Iris</b> raw-binds for speed but keeps the cache authoritative through a {@code GlStateManagerAccessor}
 *       mixin: read the active unit from the cache, raw-bind, write the binding back into the cache, restore.</li>
 * </ul>
 * We cannot cheaply take OptiFine's route — {@code GlStateManager.TextureState} is package-private with a private
 * constructor, so enlarging the array from a mixin means either constructing inaccessible instances or a
 * {@code @ModifyConstant} on {@code <clinit>} that would rewrite every matching literal. So this class takes the same
 * position by a different means: units below {@link #CACHED_UNITS} always go through {@code GlStateManager}, units at
 * or above it are raw (the cache cannot represent them at all), and every raw sequence is handed back through
 * {@link #releaseScratch()}, which lands the cache and real GL on unit 0 <em>together</em>.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Never call {@code LWJGL.glActiveTexture} outside this class. Use {@link #selectScratch(int)} /
 *       {@link #releaseScratch()}.</li>
 *   <li>Never call {@code LWJGL.glBindTexture(GL_TEXTURE_2D, …)} on a unit below {@link #CACHED_UNITS}, and never on
 *       "whatever unit happens to be selected". Use {@link #bindTexture2D(int, int)}, or do the work on a scratch
 *       unit.</li>
 *   <li>Binding a non-2D target (3D, array, cube) raw is fine at any unit: {@code GlStateManager} only tracks 2D, so
 *       the 2D record for that unit stays true. The <em>selector</em> still has to be handed back properly.</li>
 * </ul>
 */
public final class GlTextureUnits {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Iris");

    /**
     * Length of {@code GlStateManager.textureState} on vanilla 1.12.2. Indexing the cache at or beyond this throws,
     * which is why high units must be driven raw. (OptiFine raises this to 32 in its own patched GlStateManager; we
     * run against the unpatched one.)
     */
    public static final int CACHED_UNITS = 8;

    private GlTextureUnits() {
    }

    /**
     * Returns the selector to unit 0 with GlStateManager's cache in agreement, whatever either held on entry.
     * <p>
     * Stepping through unit 1 first is what makes this reliable: it guarantees the second call actually issues instead
     * of being swallowed by the cache. Call 1 leaves the cache at 1 whether or not it issued, so call 2 always sees
     * {@code 1 != 0} and issues — real GL and the cache both end on unit 0 for every possible entry state.
     */
    public static void resetToUnit0() {
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + 1);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
    }

    /**
     * Selects {@code unit} for a run of raw GL work (mipmap generation, {@code glGetTexImage},
     * {@code glCopyTexSubImage2D}, non-2D binds). Pair every call with {@link #releaseScratch()}, ideally in a
     * {@code finally}.
     * <p>
     * Prefer a unit at or above {@link #CACHED_UNITS} so the work cannot disturb a cached slot at all. Every current
     * caller does ({@code MIPMAP_SCRATCH_UNIT} 32, {@code DEPTH_COPY_SCRATCH_UNIT} 33, {@code RESIZE_SCRATCH_UNIT} 32,
     * {@code TEXTURE_SETUP_UNIT} 31, custom textures/images from unit 24 up), but a unit inside the cached range is
     * handled correctly rather than silently desyncing the selector — note that a raw <em>bind</em> on such a unit is
     * still the caller's problem, so use {@link #bindTexture2D(int, int)} there.
     */
    public static void selectScratch(int unit) {
        if (unit < 0) {
            // Unit allocation returns -1 when a pack exhausts the programmable units. Every current caller filters
            // that out before binding, but routing it here would set GlStateManager's activeTextureUnit to -1, and
            // the crash would then surface at some unrelated bindTexture as an ArrayIndexOutOfBounds. Fail here,
            // where the cause is visible, instead of corrupting the selector for everything downstream.
            LOGGER.error("[Iris] Ignoring texture-unit selection for invalid unit {}", unit);
            return;
        }
        if (unit < CACHED_UNITS) {
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + unit);
        } else {
            LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        }
    }

    /** Ends a {@link #selectScratch(int)} sequence, restoring a selector state the cache agrees with. */
    public static void releaseScratch() {
        resetToUnit0();
    }

    /**
     * Binds a 2D texture to {@code unit} and leaves the selector on unit 0, with the cache correct either way.
     * <p>
     * Use this instead of a raw {@code glActiveTexture} + {@code glBindTexture} pair. For units the cache can hold it
     * goes through {@code GlStateManager} so the record is written; for units beyond the cache it binds raw, which is
     * correct because no cached slot describes those units.
     */
    public static void bindTexture2D(int unit, int texture) {
        if (unit < CACHED_UNITS) {
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + unit);
            GlStateManager.bindTexture(texture);
            resetToUnit0();
        } else {
            selectScratch(unit);
            LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            releaseScratch();
        }
    }
}
