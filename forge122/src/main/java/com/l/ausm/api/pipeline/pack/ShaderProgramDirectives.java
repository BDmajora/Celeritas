package com.l.ausm.api.pipeline.pack;
import com.github.bsideup.jabel.Desugar;

import com.l.ausm.api.pipeline.fbo.*;
import com.l.ausm.api.pipeline.shader.*;
import com.l.ausm.api.pipeline.pack.*;

import com.l.ausm.api.pipeline.fbo.Attachment;
import com.l.ausm.api.pipeline.shader.ProgramId;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Iris-style per-program directive bundle.
 *
 * <p>AUSM still stores the source maps during the migration, but new pipeline
 * code should prefer this ProgramId-keyed view over raw RenderPass lookups.</p>
 */
@Desugar
public record ShaderProgramDirectives(
        ProgramId programId,
        List<Attachment> drawBuffers,
        ShaderViewportScale viewportScale,
        ShaderAlphaTest alphaTestOverride,
        ShaderBlendMode blendModeOverride,
        Map<Attachment, ShaderBlendMode> attachmentBlendModes,
        Set<Attachment> clearDisabledBuffers,
        Set<Attachment> mipmappedBuffers,
        Map<Attachment, Boolean> explicitFlips
) {
    public Attachment[] clearAttachments(Iterable<Attachment> buffers) {
        java.util.EnumSet<Attachment> attachments = java.util.EnumSet.noneOf(Attachment.class);
        for (Attachment attachment : buffers) {
            if (!clearDisabledBuffers.contains(attachment)) {
                attachments.add(attachment);
            }
        }
        return attachments.toArray(new Attachment[0]);
    }

    public Attachment[] flippedAttachments(Iterable<Attachment> buffers) {
        java.util.List<Attachment> flipped = new java.util.ArrayList<>();
        for (Attachment attachment : buffers) {
            if (explicitFlips.get(attachment) != Boolean.FALSE) {
                flipped.add(attachment);
            }
        }
        explicitFlips.forEach((attachment, shouldFlip) -> {
            if (shouldFlip && !flipped.contains(attachment)) {
                flipped.add(attachment);
            }
        });
        return flipped.toArray(new Attachment[0]);
    }

    public static ShaderProgramDirectives empty(ProgramId programId) {
        return new ShaderProgramDirectives(
                programId,
                java.util.Collections.emptyList(),
                ShaderViewportScale.DEFAULT,
                null,
                null,
                java.util.Collections.emptyMap(),
                java.util.Collections.emptySet(),
                java.util.Collections.emptySet(),
                java.util.Collections.emptyMap()
        );
    }
}
