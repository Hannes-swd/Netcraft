package dev.hannes.netcraft_pc_builder.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** A blank part: the part fabricator turns it (plus materials) into a real part of its kind. */
public class PartTemplateItem extends Item {
    public PartTemplateItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("netcraft_pc_builder.template.hint").withStyle(ChatFormatting.GRAY));
    }
}
