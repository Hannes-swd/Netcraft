package dev.hannes.virtual_mashine.network;

import dev.hannes.virtual_mashine.vm.VmManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class PcNetworking {
    private PcNetworking() {
    }

    public static void registerCommon() {
        PayloadTypeRegistry.playC2S().register(CasePayload.TYPE, CasePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(FabricatePayload.TYPE, FabricatePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(FabricatePayload.TYPE, (payload, context) -> {
            var player = context.player();
            if (player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(payload.pos())) <= 64
                    && player.level().getBlockState(payload.pos()).getBlock() instanceof dev.hannes.virtual_mashine.block.PartFabricatorBlock) {
                dev.hannes.virtual_mashine.pc.PartCosts.fabricate(player, payload.partId());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(CasePayload.TYPE,
                (payload, context) -> dev.hannes.virtual_mashine.pc.CaseActions.handle(context.player(), payload));
        PayloadTypeRegistry.playS2C().register(PcInfoPayload.TYPE, PcInfoPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(PcActionPayload.TYPE, PcActionPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(TunnelPayloads.Open.TYPE, TunnelPayloads.Open.CODEC);
        PayloadTypeRegistry.playC2S().register(TunnelPayloads.Data.TYPE, TunnelPayloads.Data.CODEC);
        PayloadTypeRegistry.playC2S().register(TunnelPayloads.Close.TYPE, TunnelPayloads.Close.CODEC);
        PayloadTypeRegistry.playS2C().register(TunnelPayloads.Data.TYPE, TunnelPayloads.Data.CODEC);
        PayloadTypeRegistry.playS2C().register(TunnelPayloads.Close.TYPE, TunnelPayloads.Close.CODEC);
        PayloadTypeRegistry.playS2C().register(NetPayloads.PortView.TYPE, NetPayloads.PortView.CODEC);
        PayloadTypeRegistry.playS2C().register(NetPayloads.CableSync.TYPE, NetPayloads.CableSync.CODEC);
        PayloadTypeRegistry.playS2C().register(NetPayloads.HeldCable.TYPE, NetPayloads.HeldCable.CODEC);
        PayloadTypeRegistry.playC2S().register(NetPayloads.PortAction.TYPE, NetPayloads.PortAction.CODEC);
        PayloadTypeRegistry.playS2C().register(NetPayloads.ClipView.TYPE, NetPayloads.ClipView.CODEC);
        PayloadTypeRegistry.playC2S().register(NetPayloads.ClipAction.TYPE, NetPayloads.ClipAction.CODEC);
        PayloadTypeRegistry.playS2C().register(NetPayloads.SpeakerView.TYPE, NetPayloads.SpeakerView.CODEC);
        PayloadTypeRegistry.playC2S().register(NetPayloads.SpeakerAction.TYPE, NetPayloads.SpeakerAction.CODEC);
        PayloadTypeRegistry.playS2C().register(NetPayloads.WlanView.TYPE, NetPayloads.WlanView.CODEC);
        PayloadTypeRegistry.playS2C().register(NetPayloads.ApView.TYPE, NetPayloads.ApView.CODEC);
        PayloadTypeRegistry.playC2S().register(NetPayloads.WlanAction.TYPE, NetPayloads.WlanAction.CODEC);
        PayloadTypeRegistry.playC2S().register(NetPayloads.ApAction.TYPE, NetPayloads.ApAction.CODEC);
        PayloadTypeRegistry.playS2C().register(NetPayloads.IoView.TYPE, NetPayloads.IoView.CODEC);
        PayloadTypeRegistry.playC2S().register(NetPayloads.IoAction.TYPE, NetPayloads.IoAction.CODEC);
        PayloadTypeRegistry.playS2C().register(MediaPayloads.VmAudio.TYPE, MediaPayloads.VmAudio.CODEC);
        PayloadTypeRegistry.playS2C().register(MediaPayloads.MicState.TYPE, MediaPayloads.MicState.CODEC);
        PayloadTypeRegistry.playS2C().register(MediaPayloads.CameraJob.TYPE, MediaPayloads.CameraJob.CODEC);
        PayloadTypeRegistry.playC2S().register(MediaPayloads.AudioFocus.TYPE, MediaPayloads.AudioFocus.CODEC);
        PayloadTypeRegistry.playC2S().register(MediaPayloads.MicAudio.TYPE, MediaPayloads.MicAudio.CODEC);
        PayloadTypeRegistry.playC2S().register(MediaPayloads.CameraFrame.TYPE, MediaPayloads.CameraFrame.CODEC);
        PayloadTypeRegistry.playS2C().register(MediaPayloads.MicGameJob.TYPE, MediaPayloads.MicGameJob.CODEC);
        PayloadTypeRegistry.playC2S().register(MediaPayloads.MicGameAudio.TYPE, MediaPayloads.MicGameAudio.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(MediaPayloads.MicGameAudio.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.network().media().gameAudio(context.player(), payload);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(MediaPayloads.AudioFocus.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.network().media().setFocus(context.player(), payload.vm());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(MediaPayloads.MicAudio.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.network().media().micAudio(context.player(), payload);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(MediaPayloads.CameraFrame.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.network().media().cameraFrame(context.player(), payload);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(NetPayloads.IoAction.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.network().handleIoAction(context.player(), payload);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(NetPayloads.WlanAction.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.network().handleWlanAction(context.player(), payload);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(NetPayloads.ApAction.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.network().handleApAction(context.player(), payload);
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(NetPayloads.PortAction.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.network().handlePortAction(context.player(), payload);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(NetPayloads.ClipAction.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.network().handleClipAction(context.player(), payload);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(NetPayloads.SpeakerAction.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.network().handleSpeakerAction(context.player(), payload);
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(PcActionPayload.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.handleAction(context.player(), payload);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(TunnelPayloads.Open.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.openTunnel(context.player(), payload);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(TunnelPayloads.Data.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.tunnelData(context.player(), payload);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(TunnelPayloads.Close.TYPE, (payload, context) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.closeTunnel(context.player(), payload);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            VmManager manager = VmManager.get();
            if (manager != null) {
                manager.onPlayerLeft(handler.getPlayer().getUUID());
            }
        });
    }
}
