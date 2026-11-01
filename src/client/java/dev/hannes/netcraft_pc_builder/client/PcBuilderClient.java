package dev.hannes.netcraft_pc_builder.client;

import dev.hannes.netcraft_pc_builder.PcBuilderRegistry;
import dev.hannes.netcraft_pc_builder.block.CaseBlock;
import dev.hannes.netcraft_pc_builder.block.PartFabricatorBlock;
import dev.hannes.netcraft_pc_builder.client.render.CaseRenderer;
import dev.hannes.netcraft_pc_builder.client.screen.CaseScreen;
import dev.hannes.netcraft_pc_builder.client.screen.FabricatorScreen;
import dev.hannes.netcraft_pc_builder.item.PcPartItem;
import dev.hannes.netcraft_pc_builder.pc.PcPart;
import dev.hannes.netcraft_pc_builder.pc.PcParts;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

import java.util.Map;

public class PcBuilderClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        CaseBlock.openScreen = pos -> Minecraft.getInstance().setScreen(new CaseScreen(pos));
        PartFabricatorBlock.openScreen = pos -> Minecraft.getInstance().setScreen(new FabricatorScreen(pos));
        PcPartItem.shiftDown = Screen::hasShiftDown;

        // Netcraft's LEDs and sockets plus the parts behind the glass side panel.
        BlockEntityRenderers.register(PcBuilderRegistry.CASE_BLOCK_ENTITY, CaseRenderer::new);
        for (Block block : PcBuilderRegistry.CASE_BLOCKS.values()) {
            BlockRenderLayerMap.INSTANCE.putBlock(block, RenderType.translucent());
        }

        // A case item carries the parts built into it.
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            Map<String, String> parts = stack.get(PcBuilderRegistry.PC_BUILD);
            if (parts == null || parts.isEmpty()) return;
            lines.add(Component.translatable("netcraft_pc_builder.case.parts", parts.size()).withStyle(ChatFormatting.GRAY));
            if (Screen.hasShiftDown()) {
                for (String id : parts.values()) {
                    PcPart part = PcParts.get(id);
                    if (part != null) lines.add(Component.literal(" " + part.fullName()).withStyle(ChatFormatting.DARK_AQUA));
                }
            }
        });
    }
}
