package com.bdmajora.impetus.engine.impl.render.chunk.sprite;

public enum SpriteTransparencyLevel {
    OPAQUE,
    TRANSPARENT,
    TRANSLUCENT;

    // Whichever of the two levels has the higher ordinal, i.e. demands the more capable render pass
    // Used to fold a model's sprites together: one translucent sprite promotes the whole model to the translucent
    // pass, since the pass has to accommodate the worst case
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
