package dev.hannes.virtual_mashine.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** Right-click a device: its port panel with link speed, MAC address and traffic of every port. */
public class NetworkTesterItem extends Item {
    public NetworkTesterItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> builder, TooltipFlag flag) {
        builder.add(Component.translatable("item.virtual_mashine.network_tester.desc").withStyle(ChatFormatting.GRAY));
    }
}
