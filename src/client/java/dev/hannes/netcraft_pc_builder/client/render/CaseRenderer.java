package dev.hannes.netcraft_pc_builder.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.hannes.netcraft_pc_builder.block.CaseBlock;
import dev.hannes.netcraft_pc_builder.block.CaseBlockEntity;
import dev.hannes.virtual_mashine.client.net.DeviceLedRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/** A case: Netcraft's socket LEDs like on every device, plus the parts seen through the glass. */
public final class CaseRenderer implements BlockEntityRenderer<CaseBlockEntity> {
    private final DeviceLedRenderer leds;

    public CaseRenderer(BlockEntityRendererProvider.Context context) {
        this.leds = new DeviceLedRenderer(context);
    }

    @Override
    public void render(CaseBlockEntity entity, float partialTicks, PoseStack poseStack, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {
        leds.render(entity, partialTicks, poseStack, buffers, packedLight, packedOverlay);
        if (entity.getBlockState().getBlock() instanceof CaseBlock block) {
            CasePartsRenderer.render(block, entity.getBlockState(), entity.build(), poseStack, buffers, packedLight, packedOverlay);
        }
    }
}
