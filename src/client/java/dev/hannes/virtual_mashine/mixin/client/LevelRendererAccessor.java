package dev.hannes.virtual_mashine.mixin.client;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Makes the frame after a camera block's picture pick the player's chunk sections again. */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("prevCamRotX")
    void virtual_mashine$setPrevCamRotX(double value);
}
