package com.bdmajora.impetus.umbra.gl;

import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL13;
import net.minecraft.client.renderer.GlStateManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// The one place allowed to move the texture-unit selector or bind a 2D texture for the pipeline
// GlStateManager caches texture state against its own idea of the selected unit; a raw glActiveTexture or
// glBindTexture desyncs it, after which cached binds no-op and the fullscreen blit samples a depth texture: the F2
// white-screen bug. Units below CACHED_UNITS go through GlStateManager, higher ones are raw, and releaseScratch
// lands cache and real GL back on unit 0 together
// Never call glActiveTexture or glBindTexture(GL_TEXTURE_2D) outside this class
public final class GlTextureUnits {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Umbra");

    // Length of GlStateManager.textureState on vanilla 1.12.2
    // Indexing the cache at or beyond this THROWS, which is the concrete reason high units have to be driven raw
    // OptiFine raises it to 32 in its own patched GlStateManager; this port runs against the unpatched one
    public static final int CACHED_UNITS = 8;

    private GlTextureUnits() {
    }

    // Returns the selector to unit 0 with the cache in agreement, whatever either of them held on entry
    // Stepping through unit 1 FIRST is what makes this reliable, and is not redundant: setActiveTexture is itself
    // cached, so a direct call to 0 can be swallowed as a no-op while real GL sits on a high unit
    // Call one leaves the cache at 1 whether or not it issued, so call two always sees 1 != 0 and genuinely issues.
    // Real GL and the cache therefore both end on unit 0 from every possible entry state
    public static void resetToUnit0() {
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + 1);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
    }

    // Selects a unit for a run of raw GL work: mipmap generation, glGetTexImage, glCopyTexSubImage2D, non-2D binds
    // Every call must be paired with releaseScratch(), ideally in a finally — an un-released scratch selector is
    // exactly the white-screen desync this class exists to prevent
    // Prefer a unit at or above CACHED_UNITS so the work cannot disturb a cached slot at all. Every current caller
    // does: mipmaps on 32, depth copy on 33, resize on 32, texture setup on 31, custom textures and images from 24
    // up
    // A unit inside the cached range is still handled correctly here rather than silently desyncing the selector —
    // but a raw BIND on such a unit remains the caller's problem, so use bindTexture2D there
    public static void selectScratch(int unit) {
        if (unit < 0) {
            // Unit allocation returns -1 when a pack exhausts the programmable units. Every current caller filters
            // that out before binding, but routing it here would set GlStateManager's activeTextureUnit to -1, and
            // the crash would then surface at some unrelated bindTexture as an ArrayIndexOutOfBounds. Fail here,
            // where the cause is visible, instead of corrupting the selector for everything downstream.
            LOGGER.error("[Umbra] Ignoring texture-unit selection for invalid unit {}", unit);
            return;
        }
        if (unit < CACHED_UNITS) {
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0 + unit);
        } else {
            LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        }
    }

    // Ends a selectScratch sequence, landing on a selector state the cache agrees with
    public static void releaseScratch() {
        resetToUnit0();
    }

    // Binds one texture as part of a RUN of binds, leaving the selector wherever it lands
    // The caller must finish the run with releaseScratch(), ideally in a finally
    // Why this exists next to bindTexture2D: that one returns the selector to unit 0 after every single bind, which
    // is right for a one-off but costs two extra glActiveTexture calls per texture. A program with thirty samplers
    // rebinds all of them every time it is used, so that would be sixty redundant selector moves per program bind
    // Iris has the same shape and solves it the same way — ProgramSamplers.update() reads the active unit once,
    // runs every SamplerBinding, and restores once at the end
    // Cache correctness is unaffected: the selector still goes through GlStateManager for the units it can
    // represent, and a 2D bind below CACHED_UNITS still goes through GlStateManager.bindTexture so the record is
    // written. Only the RESTORE is hoisted out of the loop
    public static void bindTextureInRun(int unit, int target, int texture) {
        if (unit < 0) {
            LOGGER.error("[Umbra] Ignoring texture bind for invalid unit {}", unit);
            return;
        }
        selectScratch(unit);
        if (unit < CACHED_UNITS && target == GL11.GL_TEXTURE_2D) {
            // GlStateManager.bindTexture records against its cached active unit, which selectScratch just set through
            // GlStateManager for this range, so the cache and real GL agree.
            GlStateManager.bindTexture(texture);
        } else {
            // At or above the cache there is no record to keep; below it, a non-2D target is not tracked either.
            LWJGL.glBindTexture(target, texture);
        }
    }

    // Binds a 2D texture to a unit and leaves the selector on unit 0, with the cache correct either way
    // Use this rather than a raw glActiveTexture + glBindTexture pair
    // For units the cache can hold it goes through GlStateManager so the record is written; for units beyond the
    // cache it binds raw, which is correct precisely because no cached slot describes those units
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
