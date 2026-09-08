package com.bdmajora.coartatio.state;

// Bridges ModelLoader cleanup to state declared on ModelBakery (its superclass).
// multipartVariantMap is private to ModelBakery, so a @Shadow on a ModelLoader mixin would compile but throw IllegalAccessError at runtime; the mixin must live on ModelBakery itself and talk back through this interface.
public interface BakeStateReleasable {
    // Clears bake-time state owned by ModelBakery. Returns how many entries went.
    int coartatio$releaseBakeryState();
}
