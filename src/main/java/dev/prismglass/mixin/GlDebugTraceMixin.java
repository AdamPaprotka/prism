package dev.prismglass.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev only (-Dprismglass.gltrace=true): stack trace for GL API errors, to find which call made them. */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlDebug")
public abstract class GlDebugTraceMixin {
    private static final boolean TRACE = Boolean.getBoolean("prismglass.gltrace");

    @Inject(method = "printDebugLog", at = @At("HEAD"))
    private void prism$trace(int source, int type, int id, int severity, int length, long message, long userParam, CallbackInfo ci) {
        if (TRACE && type == 0x824C /* GL_DEBUG_TYPE_ERROR */) dev.prismglass.Prism.LOG.warn("[gltrace] GL error id={}", id, new Throwable("GL error here"));
    }
}
