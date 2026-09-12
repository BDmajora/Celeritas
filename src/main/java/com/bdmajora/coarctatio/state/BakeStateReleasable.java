package com.bdmajora.coarctatio.state;

// Bridges ModelLoader cleanup to state on its superclass ModelBakery; multipartVariantMap is private there, so a @Shadow from a ModelLoader mixin throws IllegalAccessError
public interface BakeStateReleasable {
    // Clears bake-time state owned by ModelBakery; returns how many entries went
    int coarctatio$releaseBakeryState();
}
