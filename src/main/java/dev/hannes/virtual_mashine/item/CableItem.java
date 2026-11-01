package dev.hannes.virtual_mashine.item;

import dev.hannes.virtual_mashine.ModRegistry;
import dev.hannes.virtual_mashine.device.CableType;
import dev.hannes.virtual_mashine.vm.VmManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * A patch cable. Right-click a device to open its port panel and plug one end in, then do the
 * same on the second device. Sneak + right-click into the air changes the jacket colour.
 */
public class CableItem extends Item {
    public final CableType cableType;

    public CableItem(CableType cableType, Item.Properties properties) {
        super(properties);
        this.cableType = cableType;
    }

    public static int color(ItemStack stack) {
        CableType type = stack.getItem() instanceof CableItem cable ? cable.cableType : CableType.CAT6;
        return stack.getOrDefault(ModRegistry.CABLE_COLOR, type.defaultColor);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            VmManager manager = VmManager.get();
            if (manager != null && manager.network().cancelPending(serverPlayer)) {
                return InteractionResultHolder.success(player.getItemInHand(hand));
            }
            if (player.isShiftKeyDown()) {
                ItemStack stack = player.getItemInHand(hand);
                int next = CableType.nextColor(color(stack));
                stack.set(ModRegistry.CABLE_COLOR, next);
                serverPlayer.displayClientMessage(Component.translatable("virtual_mashine.cable.color_changed",
                        Component.translatable(CableType.colorKey(next))), true);
            }
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> builder, TooltipFlag flag) {
        builder.add(Component.translatable(cableType.speedKey()).withStyle(ChatFormatting.GRAY));
        builder.add(Component.translatable("virtual_mashine.cable.max_length", cableType.maxLength).withStyle(ChatFormatting.GRAY));
        builder.add(Component.translatable("virtual_mashine.cable.color", Component.translatable(CableType.colorKey(color(stack))))
                .withStyle(style -> style.withColor(color(stack))));
        builder.add(Component.translatable("virtual_mashine.cable.usage").withStyle(ChatFormatting.DARK_GRAY));
    }
}
