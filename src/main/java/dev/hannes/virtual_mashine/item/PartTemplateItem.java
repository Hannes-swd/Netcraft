package dev.hannes.virtual_mashine.item;

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
        tooltip.add(Component.translatable("virtual_mashine.template.hint").withStyle(ChatFormatting.GRAY));
    }
}
