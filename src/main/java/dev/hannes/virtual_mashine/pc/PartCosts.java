package dev.hannes.virtual_mashine.pc;

import dev.hannes.virtual_mashine.ModRegistry;
import dev.hannes.virtual_mashine.item.PcPartItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a part costs in the part fabricator: its category's template plus materials that grow with the
 * part's specs (more cores, more VRAM, more watts: more redstone, gold, diamonds ...).
 */
public final class PartCosts {
    private PartCosts() {
    }

    private static void add(Map<Item, Integer> cost, Item item, int count) {
        if (count > 0) cost.merge(item, count, Integer::sum);
    }

    /** The materials (without the template). */
    public static List<ItemStack> of(PcPart part) {
        Map<Item, Integer> c = new LinkedHashMap<>();
        switch (part.category()) {
            case CPU -> {
                add(c, Items.REDSTONE, part.num("threads"));
                add(c, Items.QUARTZ, part.num("cores"));
                add(c, Items.GOLD_INGOT, (int) Math.ceil(part.dbl("boost_ghz") / 2));
                add(c, Items.DIAMOND, part.num("threads") >= 24 ? 2 : part.num("threads") >= 16 ? 1 : 0);
            }
            case MAINBOARD -> {
                add(c, Items.IRON_INGOT, 4);
                add(c, Items.COPPER_INGOT, part.str("form").equals("ATX") ? 6 : 4);
                add(c, Items.REDSTONE, 8);
                add(c, Items.GREEN_DYE, 2);
                add(c, Items.GOLD_INGOT, part.str("chipset").startsWith("Z") || part.str("chipset").endsWith("E") ? 3 : 1);
            }
            case RAM -> {
                add(c, Items.REDSTONE, part.num("size_gb") / 4);
                add(c, Items.QUARTZ, part.num("size_gb") / 8);
                add(c, Items.GOLD_INGOT, part.num("speed") >= 5000 ? 2 : 1);
            }
            case GPU -> {
                add(c, Items.IRON_INGOT, Math.max(2, part.num("length_mm") / 60));
                add(c, Items.REDSTONE, part.num("vram_gb") * 2);
                add(c, Items.GOLD_INGOT, Math.max(1, part.num("power") / 100));
                add(c, Items.DIAMOND, part.num("power") >= 400 ? 3 : part.num("power") >= 250 ? 2 : part.num("power") >= 200 ? 1 : 0);
                add(c, Items.EMERALD, part.num("vram_gb") >= 24 ? 1 : 0);
            }
            case PSU -> {
                add(c, Items.IRON_INGOT, Math.max(2, part.num("watts") / 150));
                add(c, Items.COPPER_INGOT, part.num("watts") / 100);
                add(c, Items.REDSTONE, part.num("watts") / 100);
                add(c, Items.GOLD_INGOT, part.str("rating").contains("Bronze") ? 0 : part.str("rating").contains("Gold") ? 1 : 2);
            }
            case COOLER -> {
                boolean aio = "aio".equals(part.str("type"));
                add(c, Items.IRON_INGOT, aio ? 4 : 2);
                add(c, Items.COPPER_INGOT, part.num("max_tdp") / 25);
                if (aio) {
                    add(c, Items.GLASS, 2);
                    add(c, Items.WATER_BUCKET, 1);
                }
            }
            case STORAGE -> {
                add(c, Items.IRON_INGOT, part.str("slot").equals("sata35") ? 3 : 1);
                add(c, Items.REDSTONE, part.num("size_gb") / 250);
                add(c, Items.QUARTZ, part.str("slot").equals("m2") ? 2 : 0);
                add(c, Items.GOLD_INGOT, part.num("read_mbs") / 2500);
            }
        }
        List<ItemStack> out = new ArrayList<>();
        c.forEach((item, count) -> out.add(new ItemStack(item, Math.min(64, count))));
        return out;
    }

    public static int count(Inventory inventory, Item item) {
        int n = 0;
        for (ItemStack stack : inventory.items) {
            if (stack.is(item) && !stack.has(ModRegistry.PC_PART)) n += stack.getCount();
        }
        return n;
    }

    /** Whether the player has the template and every material (creative players need nothing). */
    public static boolean affordable(Player player, PcPart part) {
        if (player.getAbilities().instabuild) return true;
        Inventory inventory = player.getInventory();
        if (count(inventory, ModRegistry.PART_TEMPLATES.get(part.category())) < 1) return false;
        for (ItemStack cost : of(part)) {
            if (count(inventory, cost.getItem()) < cost.getCount()) return false;
        }
        return true;
    }

    private static void take(Inventory inventory, Item item, int count) {
        for (ItemStack stack : inventory.items) {
            if (count <= 0) return;
            if (stack.is(item)) {
                int n = Math.min(count, stack.getCount());
                // A water bucket leaves its bucket behind.
                if (item == Items.WATER_BUCKET) {
                    for (int i = 0; i < n; i++) inventory.add(new ItemStack(Items.BUCKET));
                }
                stack.shrink(n);
                count -= n;
            }
        }
    }

    public static void fabricate(ServerPlayer player, String partId) {
        PcPart part = PcParts.get(partId);
        if (part == null) return;
        if (!affordable(player, part)) {
            player.displayClientMessage(Component.literal("You lack the template or materials").withStyle(ChatFormatting.RED), true);
            return;
        }
        if (!player.getAbilities().instabuild) {
            Inventory inventory = player.getInventory();
            take(inventory, ModRegistry.PART_TEMPLATES.get(part.category()), 1);
            for (ItemStack cost : of(part)) {
                take(inventory, cost.getItem(), cost.getCount());
            }
        }
        ItemStack result = PcPartItem.stackOf(part);
        if (!player.getInventory().add(result)) player.drop(result, false);
        player.level().playSound(null, player.blockPosition(), SoundEvents.SMITHING_TABLE_USE, SoundSource.BLOCKS, 0.7F, 1.2F);
    }
}
