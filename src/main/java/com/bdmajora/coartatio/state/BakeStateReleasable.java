package com.bdmajora.coartatio.state;

/**
 * Lets the {@code ModelLoader} cleanup reach state declared on its superclass {@code ModelBakery}.
 *
 * <p>Needed because {@code multipartVariantMap} is <b>private</b> to {@code ModelBakery}. A private
 * superclass field is not accessible from a subclass at the JVM level, so a {@code @Shadow} of it on
 * a {@code ModelLoader} mixin compiles and reobfuscates cleanly and then fails with
 * {@code IllegalAccessError} when the injected access runs. The mixin has to live on the class that
 * declares the field, and the two halves talk through this interface.
 */
public interface BakeStateReleasable {
    /** Clears bake-time state owned by {@code ModelBakery}. Returns how many entries went. */
    int coartatio$releaseBakeryState();
}
