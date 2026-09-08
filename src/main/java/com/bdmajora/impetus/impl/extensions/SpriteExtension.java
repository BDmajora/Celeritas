package com.bdmajora.impetus.impl.extensions;

// Extension mixed into sprite instances; tracks whether an animated sprite frame needs re-uploading this tick
public interface SpriteExtension {
    void impetus$markActive();
    boolean impetus$shouldUpdate();
}
