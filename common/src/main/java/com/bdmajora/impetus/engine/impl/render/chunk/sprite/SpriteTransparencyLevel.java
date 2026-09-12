package com.bdmajora.impetus.engine.impl.render.chunk.sprite;

public enum SpriteTransparencyLevel {
    OPAQUE,
    TRANSPARENT,
    TRANSLUCENT;

    // Whichever level has the higher ordinal, i.e. needs the more capable pass; one translucent sprite promotes the whole model to the translucent pass
    public SpriteTransparencyLevel chooseNextLevel(SpriteTransparencyLevel level) {
        return level.ordinal() >= this.ordinal() ? level : this;
    }

    public interface Holder {
        SpriteTransparencyLevel impetus$getTransparencyLevel();

        // From the platform sprite via its extension interface
        static SpriteTransparencyLevel getTransparencyLevel(Object o) {
            return ((Holder)o).impetus$getTransparencyLevel();
        }
    }
}
