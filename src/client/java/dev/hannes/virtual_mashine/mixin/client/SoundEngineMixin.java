package dev.hannes.virtual_mashine.mixin.client;

import dev.hannes.virtual_mashine.client.media.GameSoundTap;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every sound the game starts also reaches the microphone blocks that listen to game sounds. */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    @Inject(method = "play", at = @At("RETURN"))
    private void virtual_mashine$tapForMicrophones(SoundInstance instance, CallbackInfo ci) {
        // 1.21.1 does not report whether the sound started; onPlay skips sounds without a resolved sound file.
        GameSoundTap.onPlay(instance);
    }
}
