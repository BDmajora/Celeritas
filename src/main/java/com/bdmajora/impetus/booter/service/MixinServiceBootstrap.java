package com.bdmajora.impetus.booter.service;

import org.spongepowered.asm.service.IMixinServiceBootstrap;
import com.bdmajora.impetus.booter.Tags;

public class MixinServiceBootstrap implements IMixinServiceBootstrap {

    private static final String OWN_SERVICE = "com.bdmajora.impetus.booter.service.MixinBooterService";

    @Override
    public String getName() {
        return Tags.MOD_NAME;
    }

    @Override
    public String getServiceClassName() {
        return OWN_SERVICE;
    }

    @Override
    public void bootstrap() { }

}
