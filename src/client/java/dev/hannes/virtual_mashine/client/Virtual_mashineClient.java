package dev.hannes.virtual_mashine.client;

import dev.hannes.virtual_mashine.ModRegistry;
import dev.hannes.virtual_mashine.client.net.CableRenderer;
import dev.hannes.virtual_mashine.client.net.DeviceLedRenderer;
import dev.hannes.virtual_mashine.client.net.RackRenderer;
import dev.hannes.virtual_mashine.client.display.DisplayManager;
import dev.hannes.virtual_mashine.client.media.CameraCapture;
import dev.hannes.virtual_mashine.client.media.GameSoundTap;
import dev.hannes.virtual_mashine.client.media.MicCapture;
import dev.hannes.virtual_mashine.client.media.VmSound;
import dev.hannes.virtual_mashine.network.MediaPayloads;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.client.screen.AccessPointScreen;
import dev.hannes.virtual_mashine.client.screen.CableClipScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import dev.hannes.virtual_mashine.client.screen.IoModuleScreen;
import dev.hannes.virtual_mashine.client.screen.PortScreen;
import dev.hannes.virtual_mashine.client.screen.WlanScreen;
import dev.hannes.virtual_mashine.client.vnc.TunnelTransport;
import dev.hannes.virtual_mashine.network.NetPayloads;
import dev.hannes.virtual_mashine.network.PcInfoPayload;
import dev.hannes.virtual_mashine.network.TunnelPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public class Virtual_mashineClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(PcInfoPayload.TYPE,
                (payload, context) -> ClientPcState.onInfo(payload, context.client()));
        ClientPlayNetworking.registerGlobalReceiver(TunnelPayloads.Data.TYPE, (payload, context) -> TunnelTransport.onData(payload));
        ClientPlayNetworking.registerGlobalReceiver(TunnelPayloads.Close.TYPE, (payload, context) -> TunnelTransport.onClose(payload));
        ClientPlayNetworking.registerGlobalReceiver(NetPayloads.PortView.TYPE, (payload, context) -> onPortView(payload, context.client()));
        ClientPlayNetworking.registerGlobalReceiver(NetPayloads.CableSync.TYPE, (payload, context) -> CableRenderer.setCables(payload.cables()));
        ClientPlayNetworking.registerGlobalReceiver(NetPayloads.HeldCable.TYPE, (payload, context) -> CableRenderer.setHeld(payload));
        ClientPlayNetworking.registerGlobalReceiver(NetPayloads.SpeakerView.TYPE, (payload, context) -> {
            Minecraft minecraft = context.client();
            if (minecraft.screen instanceof dev.hannes.virtual_mashine.client.screen.SpeakerScreen screen && screen.speakerPos().equals(payload.pos())) {
                screen.update(payload);
            } else {
                minecraft.setScreen(new dev.hannes.virtual_mashine.client.screen.SpeakerScreen(payload));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(NetPayloads.ClipView.TYPE, (payload, context) -> {
            Minecraft minecraft = context.client();
            if (minecraft.screen instanceof CableClipScreen screen && screen.clipPos().equals(payload.pos())) {
                screen.update(payload);
            } else {
                minecraft.setScreen(new CableClipScreen(payload));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(NetPayloads.WlanView.TYPE, (payload, context) -> {
            Minecraft minecraft = context.client();
            if (minecraft.screen instanceof WlanScreen screen && screen.deviceId().equals(payload.device())) {
                screen.update(payload);
            } else {
                minecraft.setScreen(new WlanScreen(payload));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(NetPayloads.ApView.TYPE, (payload, context) -> {
            Minecraft minecraft = context.client();
            if (minecraft.screen instanceof AccessPointScreen screen && screen.deviceId().equals(payload.device())) {
                screen.update(payload);
            } else {
                minecraft.setScreen(new AccessPointScreen(payload));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(NetPayloads.IoView.TYPE, (payload, context) -> {
            Minecraft minecraft = context.client();
            if (minecraft.screen instanceof IoModuleScreen screen && screen.deviceId().equals(payload.device())) {
                screen.update(payload);
            } else {
                minecraft.setScreen(new IoModuleScreen(payload));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(MediaPayloads.VmAudio.TYPE, (payload, context) -> VmSound.onAudio(payload));
        ClientPlayNetworking.registerGlobalReceiver(MediaPayloads.MicState.TYPE, (payload, context) -> MicCapture.onState(payload));
        ClientPlayNetworking.registerGlobalReceiver(MediaPayloads.CameraJob.TYPE, (payload, context) -> CameraCapture.onJob(payload));
        ClientPlayNetworking.registerGlobalReceiver(MediaPayloads.MicGameJob.TYPE, (payload, context) -> GameSoundTap.onJob(payload));
        ClientTickEvents.END_CLIENT_TICK.register(DisplayManager::tick);
        ClientTickEvents.END_CLIENT_TICK.register(VmSound::tick);
        ClientTickEvents.END_CLIENT_TICK.register(MicCapture::tick);
        ClientTickEvents.END_CLIENT_TICK.register(GameSoundTap::tick);
        HudRenderCallback.EVENT.register(MicCapture::hud);
        HudRenderCallback.EVENT.register(DisplayManager::hud);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            DisplayManager.disconnectAll();
            VmSound.stopAll();
            MicCapture.reset();
            GameSoundTap.reset();
            CameraCapture.reset();
            TunnelTransport.closeAll();
            CableRenderer.clear();
        });
        WorldRenderEvents.AFTER_ENTITIES.register(CableRenderer::render);
        BlockEntityRenderers.register(ModRegistry.DEVICE_BLOCK_ENTITY, DeviceLedRenderer::new);
        BlockEntityRenderers.register(ModRegistry.RACK_BLOCK_ENTITY, RackRenderer::new);
        dev.hannes.virtual_mashine.block.CaseBlock.openScreen =
                pos -> Minecraft.getInstance().setScreen(new dev.hannes.virtual_mashine.client.screen.CaseScreen(pos));
        dev.hannes.virtual_mashine.item.PcPartItem.shiftDown = net.minecraft.client.gui.screens.Screen::hasShiftDown;
        dev.hannes.virtual_mashine.block.PartFabricatorBlock.openScreen =
                pos -> Minecraft.getInstance().setScreen(new dev.hannes.virtual_mashine.client.screen.FabricatorScreen(pos));
        // A case item carries the parts built into it.
        net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            java.util.Map<String, String> parts = stack.get(ModRegistry.PC_BUILD);
            if (parts == null || parts.isEmpty()) return;
            lines.add(net.minecraft.network.chat.Component.literal(parts.size() + " parts built in").withStyle(net.minecraft.ChatFormatting.GRAY));
            if (net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
                for (String id : parts.values()) {
                    dev.hannes.virtual_mashine.pc.PcPart part = dev.hannes.virtual_mashine.pc.PcParts.get(id);
                    if (part != null) lines.add(net.minecraft.network.chat.Component.literal(" " + part.fullName()).withStyle(net.minecraft.ChatFormatting.DARK_AQUA));
                }
            }
        });
    }

    private static void onPortView(NetPayloads.PortView view, Minecraft minecraft) {
        if (minecraft.screen instanceof PortScreen screen && screen.deviceId().equals(view.device())) {
            screen.update(view);
        } else if (view.open()) {
            minecraft.setScreen(new PortScreen(view));
        }
    }
}
