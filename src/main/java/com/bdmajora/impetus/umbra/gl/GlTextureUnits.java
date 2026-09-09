package com.bdmajora.impetus.umbra.gl;

import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL13;
import net.minecraft.client.renderer.GlStateManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// The ONE place allowed to move the texture-unit selector or bind a 2D texture on the pipeline's behalf
//
// Why it exists: 1.12.2's GlStateManager caches texture state, and it caches against its OWN idea of which unit is
// selected. bindTexture reads textureState[activeTextureUnit] to decide whether to skip, records into that same
// cached slot, and then issues a glBindTexture that affects the REAL unit
//
// That gives two distinct ways to desync it, and both have bitten this port
//   Selector desync — a raw glActiveTexture moves real GL without telling the cache. And because setActiveTexture
//   is ITSELF cached, the obvious repair of calling GlStateManager.setActiveTexture(GL_TEXTURE0) can be swallowed
//   as a no-op, leaving real GL parked on a high unit indefinitely
//   Binding desync — a raw glBindTexture on a unit inside the cache's range changes the real binding without
//   updating the record. Every later GlStateManager.bindTexture of the value the cache still believes is bound then
//   no-ops, and that unit keeps the wrong texture forever
//
// Either one makes Framebuffer.bindFramebufferTexture() — the fullscreen blit that puts the world on screen —
// silently do nothing, so the blit samples whatever actually occupies the unit. A depth or shadow texture read as
// colour is 1.0 everywhere: a white screen, every frame, until a restart
// That is not hypothetical. It is the F2 white-screen bug, and vanilla's ScreenShotHelper.createScreenshot is one
// of the cached binds that trips it
//
// Neither reference implementation lets the cache diverge at all
//   OptiFine, on this same MC version, enlarges GlStateManager.textureState from 8 slots to 32 and routes EVERY
//   unit through GlStateManager.setActiveTexture — 48 call sites and not one raw glActiveTexture in its shader
//   code. Its tail idiom is setActiveTexture(GL_TEXTURE0), which is safe there precisely because its cache is
//   never out of step
//   Iris raw-binds for speed but keeps the cache authoritative through a GlStateManagerAccessor mixin: read the
//   active unit from the cache, raw-bind, write the binding back into the cache, restore
//
// OptiFine's route is not cheaply available here: GlStateManager.TextureState is package-private with a private
// constructor, so enlarging that array from a mixin means either constructing inaccessible instances or a
// @ModifyConstant on <clinit> that would rewrite every matching literal in the class
// So this class reaches the same position by a different means — units below CACHED_UNITS always go through
// GlStateManager, units at or above it are raw because the cache cannot represent them at all, and every raw
// sequence is handed back through releaseScratch(), which lands the cache and real GL on unit 0 TOGETHER
//
// The rules that follow from that
//   Never call LWJGL.glActiveTexture outside this class; use selectScratch and releaseScratch
//   Never call LWJGL.glBindTexture(GL_TEXTURE_2D, ...) on a unit below CACHED_UNITS, and never on "whatever unit
//   happens to be selected" — use bindTexture2D, or do the work on a scratch unit
//   Binding a NON-2D target (3D, array, cube) raw is fine at any unit, because GlStateManager only tracks 2D and
//   the 2D record for that unit stays true. The selector still has to be handed back properly
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
