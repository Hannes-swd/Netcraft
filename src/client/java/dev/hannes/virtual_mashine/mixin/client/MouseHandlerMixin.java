package dev.hannes.virtual_mashine.mixin.client;

import dev.hannes.virtual_mashine.client.display.DisplayManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clicks and the wheel go to a display block's page while the player looks at it. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void virtual_mashine$clickDisplay(long handle, int button, int action, int modifiers, CallbackInfo ci) {
        if (handle == Minecraft.getInstance().getWindow().getWindow() && DisplayManager.mouseButton(button, action)) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void virtual_mashine$scrollDisplay(long handle, double xOffset, double yOffset, CallbackInfo ci) {
        if (handle == Minecraft.getInstance().getWindow().getWindow() && DisplayManager.scroll(yOffset)) {
            ci.cancel();
        }
    }
}
