package org.taumc.celeritas.iris.gl.program;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the OptiFine {@code /* DRAWBUFFERS:0246 *&#47;} (and the newer Iris {@code /* RENDERTARGETS: 0,2,4,6 *&#47;})
 * directive from a fragment shader, which declares the set of color attachments the program writes to.
 * <p>
 * {@code DRAWBUFFERS} uses one hex digit per attachment ({@code 0-9}, then {@code a-f} for 10-15). {@code RENDERTARGETS}
 * uses a comma-separated decimal list. When neither is present the program is assumed to write only {@code colortex0}.
 */
public final class DrawBuffers {
    private static final Pattern DRAWBUFFERS = Pattern.compile("/\\*\\s*DRAWBUFFERS:([0-9a-fA-F]+)\\s*\\*/");
    private static final Pattern RENDERTARGETS = Pattern.compile("/\\*\\s*RENDERTARGETS:\\s*([0-9,\\s]+)\\*/");

    /** The default when a fragment shader declares no directive: write to colortex0 only. */
    public static final int[] DEFAULT = new int[]{0};

    private DrawBuffers() {
    }

    public static int[] parse(String fragmentSource) {
        if (fragmentSource == null) {
            return DEFAULT.clone();
        }

        Matcher rt = RENDERTARGETS.matcher(fragmentSource);
        if (rt.find()) {
            String[] parts = rt.group(1).trim().split("\\s*,\\s*");
            int[] buffers = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                buffers[i] = Integer.parseInt(parts[i].trim());
            }
            return buffers;
        }

        Matcher db = DRAWBUFFERS.matcher(fragmentSource);
        if (db.find()) {
            String digits = db.group(1);
            int[] buffers = new int[digits.length()];
            for (int i = 0; i < digits.length(); i++) {
                buffers[i] = Character.digit(digits.charAt(i), 16);
            }
            return buffers;
        }

        return DEFAULT.clone();
    }
}
