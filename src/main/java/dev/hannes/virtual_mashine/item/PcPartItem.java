package dev.hannes.virtual_mashine.item;

import dev.hannes.virtual_mashine.ModRegistry;
import dev.hannes.virtual_mashine.pc.PartCategory;
import dev.hannes.virtual_mashine.pc.PcPart;
import dev.hannes.virtual_mashine.pc.PcParts;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;

/** One item per category; which real part it is lives in the {@link ModRegistry#PC_PART} component. */
public class PcPartItem extends Item {
    /** Set by the client: whether Shift is held (for the detail tooltip). */
    public static BooleanSupplier shiftDown = () -> false;

    private final PartCategory category;

    public PcPartItem(PartCategory category, Properties properties) {
        super(properties);
        this.category = category;
    }

    public PartCategory category() {
        return category;
    }

    public static @Nullable PcPart part(ItemStack stack) {
        if (!(stack.getItem() instanceof PcPartItem item)) return null;
        String id = stack.get(ModRegistry.PC_PART);
        PcPart part = id == null ? null : PcParts.get(id);
        return part != null && part.category() == item.category ? part : null;
    }

    public static ItemStack stackOf(PcPart part) {
        ItemStack stack = new ItemStack(ModRegistry.PART_ITEMS.get(part.category()));
        stack.set(ModRegistry.PC_PART, part.id());
        return stack;
    }

    @Override
    public Component getName(ItemStack stack) {
        PcPart part = part(stack);
        return part == null ? super.getName(stack) : Component.literal(part.fullName());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal(category.label).withStyle(ChatFormatting.GRAY));
        PcPart part = part(stack);
        if (part == null) return;
        if (shiftDown.getAsBoolean()) {
            for (String line : part.details()) {
                tooltip.add(Component.literal(line).withStyle(ChatFormatting.DARK_AQUA));
            }
        } else {
            tooltip.add(Component.literal("Hold Shift for details").withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
