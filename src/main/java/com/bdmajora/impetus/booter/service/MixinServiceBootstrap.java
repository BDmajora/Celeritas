package com.bdmajora.impetus.booter.service;

import org.spongepowered.asm.service.IMixinServiceBootstrap;
import com.bdmajora.impetus.booter.Tags;

public class MixinServiceBootstrap implements IMixinServiceBootstrap {

    private static final String OWN_SERVICE = "com.bdmajora.impetus.booter.service.MixinBooterService";

    // Matches the service name
    @Override
    public String getName() {
        return Tags.MOD_NAME;
    }

    // Points Mixin at our service implementation
    @Override
    public String getServiceClassName() {
        return OWN_SERVICE;
    }

    // Nothing to prepare; BooterCore has already set the system properties
    @Override
    public void bootstrap() { }

}
