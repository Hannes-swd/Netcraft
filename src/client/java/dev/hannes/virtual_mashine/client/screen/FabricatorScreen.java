package dev.hannes.virtual_mashine.client.screen;

import dev.hannes.virtual_mashine.ModRegistry;
import dev.hannes.virtual_mashine.block.PartFabricatorBlock;
import dev.hannes.virtual_mashine.item.PcPartItem;
import dev.hannes.virtual_mashine.network.FabricatePayload;
import dev.hannes.virtual_mashine.pc.PartCategory;
import dev.hannes.virtual_mashine.pc.PartCosts;
import dev.hannes.virtual_mashine.pc.PcPart;
import dev.hannes.virtual_mashine.pc.PcParts;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Pick a kind of part, then the model: the screen shows what it costs and makes it from a template. */
public class FabricatorScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFF9A9A9A;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;
    private static final int ROW = 20;
    private static final int TOP = 40;

    private final BlockPos pos;
    private PartCategory category = PartCategory.CPU;
    private @Nullable PcPart selected;
    private int scroll;
    private Button makeButton;

    public FabricatorScreen(BlockPos pos) {
        super(Component.literal("Part Fabricator"));
        this.pos = pos;
    }

    private int listX() {
        return Math.max(8, width / 2 - 220);
    }

    private int listW() {
        return 200;
    }

    private int panelX() {
        return listX() + listW() + 12;
    }

    @Override
    protected void init() {
        PartCategory[] all = PartCategory.values();
        int total = Math.min(width - 16, 440);
        int x0 = (width - total) / 2;
        int w = total / all.length;
        for (int i = 0; i < all.length; i++) {
            PartCategory c = all[i];
            Button tab = Button.builder(Component.literal(c.label), b -> {
                category = c;
                selected = null;
                scroll = 0;
                rebuildWidgets();
            }).bounds(x0 + i * w, 14, w - 2, 20).build();
            tab.active = c != category;
            addRenderableWidget(tab);
        }
        makeButton = addRenderableWidget(Button.builder(Component.literal("Fabricate"), b -> {
            if (selected != null && ClientPlayNetworking.canSend(FabricatePayload.TYPE)) {
                ClientPlayNetworking.send(new FabricatePayload(pos, selected.id()));
            }
        }).bounds(panelX(), height - 30, 120, 20).build());
    }

    @Override
    public void tick() {
        if (minecraft == null || minecraft.player == null || minecraft.level == null
                || !(minecraft.level.getBlockState(pos).getBlock() instanceof PartFabricatorBlock)
                || minecraft.player.distanceToSqr(Vec3.atCenterOf(pos)) > 64) {
            onClose();
            return;
        }
        makeButton.active = selected != null && PartCosts.affordable(minecraft.player, selected);
    }

    private List<PcPart> parts() {
        return PcParts.of(category);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        super.render(g, mx, my, partialTick);
        int lx = listX();
        int bottom = height - 8;
        g.fill(lx - 2, TOP - 2, lx + listW() + 2, bottom, 0xC0101014);
        List<PcPart> parts = parts();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, parts.size() * ROW - (bottom - TOP))));
        g.enableScissor(lx, TOP, lx + listW(), bottom);
        for (int i = 0; i < parts.size(); i++) {
            PcPart part = parts.get(i);
            int y = TOP + i * ROW - scroll;
            if (part == selected) {
                g.fill(lx, y, lx + listW(), y + ROW, 0x6055AAFF);
            } else if (mx >= lx && mx < lx + listW() && my >= y && my < y + ROW && my >= TOP && my < bottom) {
                g.fill(lx, y, lx + listW(), y + ROW, 0x30FFFFFF);
            }
            g.renderItem(PcPartItem.stackOf(part), lx + 2, y + 2);
            g.drawString(font, font.plainSubstrByWidth(part.fullName(), listW() - 26), lx + 22, y + 6, WHITE, true);
        }
        g.disableScissor();

        int px = panelX();
        int py = TOP;
        if (selected == null) {
            g.drawString(font, "Pick a " + category.label + " on the left.", px, py, GRAY, true);
            return;
        }
        g.drawString(font, selected.fullName(), px, py, WHITE, true);
        py += 14;
        for (String line : selected.details()) {
            g.drawString(font, line, px, py, 0xFF55CCCC, true);
            py += 10;
        }
        py += 8;
        g.drawString(font, "Needs:", px, py, WHITE, true);
        py += 12;
        boolean creative = minecraft.player.getAbilities().instabuild;
        List<ItemStack> needs = new ArrayList<>();
        needs.add(new ItemStack(ModRegistry.PART_TEMPLATES.get(category)));
        needs.addAll(PartCosts.of(selected));
        for (ItemStack need : needs) {
            int have = PartCosts.count(minecraft.player.getInventory(), need.getItem());
            g.renderItem(need, px, py);
            String text = need.getHoverName().getString() + "  " + have + " / " + need.getCount();
            g.drawString(font, text, px + 20, py + 4, creative || have >= need.getCount() ? GREEN : RED, true);
            py += 18;
        }
        if (creative) {
            g.drawString(font, "Creative: free", px + 126, height - 24, GRAY, true);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int lx = listX();
        if (mx >= lx && mx < lx + listW() && my >= TOP && my < height - 8) {
            int index = (int) ((my - TOP + scroll) / ROW);
            List<PcPart> parts = parts();
            if (index >= 0 && index < parts.size()) {
                selected = parts.get(index);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (mx < listX() + listW()) {
            scroll -= (int) (scrollY * ROW);
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
