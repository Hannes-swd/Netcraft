package dev.hannes.virtual_mashine.pc;

import dev.hannes.virtual_mashine.block.CaseBlock;
import dev.hannes.virtual_mashine.block.DeviceBlockEntity;
import dev.hannes.virtual_mashine.device.DeviceType;
import dev.hannes.virtual_mashine.item.PcPartItem;
import dev.hannes.virtual_mashine.network.CasePayload;
import dev.hannes.virtual_mashine.network.PcActionPayload;
import dev.hannes.virtual_mashine.vm.VmManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Server side of the build screen: every change is checked again here. */
public final class CaseActions {
    private CaseActions() {
    }

    private static void deny(ServerPlayer player, String reason) {
        player.displayClientMessage(Component.literal(reason).withStyle(ChatFormatting.RED), true);
    }

    public static void handle(ServerPlayer player, CasePayload payload) {
        if (player.distanceToSqr(Vec3.atCenterOf(payload.pos())) > 64
                || !(player.level().getBlockState(payload.pos()).getBlock() instanceof CaseBlock)
                || !(player.level().getBlockEntity(payload.pos()) instanceof DeviceBlockEntity entity)) {
            return;
        }
        VmManager manager = VmManager.get();
        UUID id = entity.getOrCreatePcId();
        boolean powered = manager != null && manager.isPowered(id);
        PcBuild build = entity.build();
        switch (payload.action()) {
            case CasePayload.INSTALL -> {
                if (powered) {
                    deny(player, "Shut the PC down before building");
                    return;
                }
                Inventory inventory = player.getInventory();
                if (payload.invSlot() < 0 || payload.invSlot() >= inventory.getContainerSize()) return;
                ItemStack stack = inventory.getItem(payload.invSlot());
                PcPart part = PcPartItem.part(stack);
                if (part == null) return;
                String reason = build.canInstall(payload.slot(), part);
                if (reason != null) {
                    deny(player, reason);
                    return;
                }
                entity.setPart(payload.slot(), part.id());
                if (!player.getAbilities().instabuild) stack.shrink(1);
                player.level().playSound(null, payload.pos(), SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.4F, 1.8F);
            }
            case CasePayload.REMOVE -> {
                if (powered) {
                    deny(player, "Shut the PC down before building");
                    return;
                }
                PcPart part = build.get(payload.slot());
                if (part == null) return;
                String reason = build.canRemove(payload.slot());
                if (reason != null) {
                    deny(player, reason);
                    return;
                }
                entity.setPart(payload.slot(), null);
                ItemStack stack = PcPartItem.stackOf(part);
                if (!player.getInventory().add(stack)) player.drop(stack, false);
                player.level().playSound(null, payload.pos(), SoundEvents.IRON_TRAPDOOR_OPEN, SoundSource.BLOCKS, 0.4F, 1.8F);
            }
            case CasePayload.POWER_ON -> {
                if (manager == null || powered) return;
                BuiltPcs.update(id, build);
                if (!build.bootable()) {
                    deny(player, build.missing().isEmpty() ? build.problems().get(0) : "Missing: " + String.join(", ", build.missing()));
                    return;
                }
                manager.autoStart(id, DeviceType.PC);
                player.level().playSound(null, payload.pos(), SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.6F, 1.2F);
            }
            case CasePayload.SHUTDOWN -> {
                if (manager != null && powered) manager.handleAction(player, new PcActionPayload(id, PcActionPayload.SHUTDOWN));
            }
            case CasePayload.SCREEN -> {
                if (manager != null) manager.openFor(player, id, DeviceType.PC);
            }
            default -> {
            }
        }
    }
}
