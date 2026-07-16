package com.bdmajora.impetus.engine.impl.render.chunk.sprite;

public enum SpriteTransparencyLevel {
    OPAQUE,
    TRANSPARENT,
    TRANSLUCENT;

    /**
     * {@return whichever level has a higher ordinal, i.e. requires a better render pass}
     */
    public SpriteTransparencyLevel chooseNextLevel(SpriteTransparencyLevel level) {
        return level.ordinal() >= this.ordinal() ? level : this;
    }

    public interface Holder {
        SpriteTransparencyLevel impetus$getTransparencyLevel();

        static SpriteTransparencyLevel getTransparencyLevel(Object o) {
            return ((Holder)o).impetus$getTransparencyLevel();
        }
    }
}
