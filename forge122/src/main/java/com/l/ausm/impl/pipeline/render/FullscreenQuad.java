package com.l.ausm.impl.pipeline.render;

import com.l.ausm.api.pipeline.fbo.*;
import com.l.ausm.api.pipeline.shader.*;
import com.l.ausm.api.pipeline.pack.*;

import com.l.ausm.impl.pipeline.PipelineContext;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import com.l.ausm.impl.MainMod;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility for drawing a fullscreen quad.
 * Used during the Deferred, Composite, and Final passes.
 */
public class FullscreenQuad {

    // TEMP diagnostic: localize the GL 1282 spam. Logs each distinct (label, error) once.
    private static final Set<String> PROBED = ConcurrentHashMap.newKeySet();

    private static void probe(String label) {
        int e = GL11.glGetError();
        if (e != 0 && PROBED.add(label + ":" + e)) {
            MainMod.LOGGER.error("[GLProbe] GL 0x{} ({}) at {}", Integer.toHexString(e), e, label);
        }
    }

    public static void draw() {
        // Localize: is an error already pending when the composite/final quad starts (upstream: gbuffer/shadow),
        // or does the quad draw itself cause it?
        probe("fullscreen:before");
        GL30.glBindVertexArray(0);
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);

        PipelineContext context = PipelineContext.getInstance();
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        if (context.shouldDrawFullscreenAsTriangles()) {
            buffer.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_TEX);
            buffer.pos(0.0D, 0.0D, 0.0D).tex(0.0D, 0.0D).endVertex();
            buffer.pos(1.0D, 0.0D, 0.0D).tex(1.0D, 0.0D).endVertex();
            buffer.pos(1.0D, 1.0D, 0.0D).tex(1.0D, 1.0D).endVertex();
            buffer.pos(0.0D, 0.0D, 0.0D).tex(0.0D, 0.0D).endVertex();
            buffer.pos(1.0D, 1.0D, 0.0D).tex(1.0D, 1.0D).endVertex();
            buffer.pos(0.0D, 1.0D, 0.0D).tex(0.0D, 1.0D).endVertex();
            tessellator.draw();
            probe("fullscreen:after-triangles");
            return;
        }

        buffer.begin(context.drawModeForActiveProgram(GL11.GL_QUADS), DefaultVertexFormats.POSITION_TEX);
        buffer.pos(0.0D, 0.0D, 0.0D).tex(0.0D, 0.0D).endVertex();
        buffer.pos(1.0D, 0.0D, 0.0D).tex(1.0D, 0.0D).endVertex();
        buffer.pos(1.0D, 1.0D, 0.0D).tex(1.0D, 1.0D).endVertex();
        buffer.pos(0.0D, 1.0D, 0.0D).tex(0.0D, 1.0D).endVertex();
        tessellator.draw();
        probe("fullscreen:after-quads");
    }
}
