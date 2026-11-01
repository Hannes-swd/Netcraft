package dev.hannes.virtual_mashine.client.screen;

import dev.hannes.virtual_mashine.block.CaseBlock;
import dev.hannes.virtual_mashine.block.DeviceBlock;
import dev.hannes.virtual_mashine.block.DeviceBlockEntity;
import dev.hannes.virtual_mashine.item.PcPartItem;
import dev.hannes.virtual_mashine.network.CasePayload;
import dev.hannes.virtual_mashine.pc.CaseSize;
import dev.hannes.virtual_mashine.pc.PartCategory;
import dev.hannes.virtual_mashine.pc.PcBuild;
import dev.hannes.virtual_mashine.pc.PcPart;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The open case: the player's parts in a list on the left, the inside of the case on the right.
 * Drag a part (or click it, then click a place) onto its place; empty places have a dashed outline,
 * green while the held part fits there, red when it does not. Click a built-in part to take it out.
 */
public class CaseScreen extends Screen {
    private static final int LIST_X = 8;
    private static final int LIST_W = 150;
    private static final int TOP = 24;
    private static final int ROW = 18;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFF9A9A9A;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;
    private static final int ORANGE = 0xFFFFAA33;

    private record Rect(int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && my >= y && mx < x + w && my < y + h;
        }
    }

    /** A row of the parts list: a category header (part == null) or a part from inventory slot inv. */
    private record Row(@Nullable PartCategory header, int inv, ItemStack stack, @Nullable PcPart part) {
    }

    private final BlockPos pos;
    private final long openedAt = Util.getMillis();
    private final Map<String, Rect> layout = new LinkedHashMap<>();
    private PcBuild build;
    private Rect caseRect = new Rect(0, 0, 0, 0);
    private int scroll;
    /** Inventory slot of the part being dragged, -1 = none. */
    private int held = -1;
    private @Nullable Component message;
    private long messageUntil;
    private Button powerButton;
    private Button screenButton;

    public CaseScreen(BlockPos pos) {
        super(Component.translatable("virtual_mashine.case.title"));
        this.pos = pos;
    }

    private static net.minecraft.network.chat.MutableComponent tr(String key, Object... args) {
        return Component.translatable("virtual_mashine.case." + key, args);
    }

    private static final Component LOCKED = Component.translatable("virtual_mashine.build.locked");

    private @Nullable DeviceBlockEntity entity() {
        return minecraft != null && minecraft.level != null && minecraft.level.getBlockState(pos).getBlock() instanceof CaseBlock
                && minecraft.level.getBlockEntity(pos) instanceof DeviceBlockEntity c ? c : null;
    }

    /** The power LED of the case: its machine runs. */
    private boolean powered() {
        return minecraft != null && minecraft.level != null && minecraft.level.getBlockState(pos).getBlock() instanceof CaseBlock
                && minecraft.level.getBlockState(pos).getValue(DeviceBlock.LIT);
    }

    @Override
    protected void init() {
        powerButton = addRenderableWidget(Button.builder(tr("power_on"), b -> {
            send(powered() ? CasePayload.SHUTDOWN : CasePayload.POWER_ON, "", -1);
        }).bounds(width - 108, 2, 100, 18).build());
        screenButton = addRenderableWidget(Button.builder(tr("open_screen"), b -> send(CasePayload.SCREEN, "", -1))
                .bounds(width - 212, 2, 100, 18).build());
        updateButtons();
    }

    private void updateButtons() {
        if (powerButton == null) return;
        boolean on = powered();
        powerButton.setMessage(tr(on ? "shut_down" : "power_on"));
        DeviceBlockEntity e = entity();
        powerButton.active = on || e != null && e.build().bootable();
        screenButton.active = on;
    }

    @Override
    public void tick() {
        if (entity() == null || minecraft.player == null || minecraft.player.distanceToSqr(Vec3.atCenterOf(pos)) > 64) {
            onClose();
        }
        updateButtons();
    }

    private float progress() {
        return Math.min(1F, (Util.getMillis() - openedAt) / 220F);
    }

    // ---------------------------------------------------------------- parts list

    private List<Row> rows() {
        List<Row> parts = new ArrayList<>();
        if (minecraft == null || minecraft.player == null) return parts;
        var items = minecraft.player.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            PcPart part = PcPartItem.part(items.get(i));
            if (part != null) parts.add(new Row(null, i, items.get(i), part));
        }
        parts.sort(Comparator.<Row>comparingInt(r -> r.part().category().ordinal()).thenComparing(r -> r.part().fullName()));
        List<Row> rows = new ArrayList<>();
        PartCategory last = null;
        for (Row row : parts) {
            if (row.part().category() != last) {
                last = row.part().category();
                rows.add(new Row(last, -1, ItemStack.EMPTY, null));
            }
            rows.add(row);
        }
        return rows;
    }

    private int listBottom() {
        return height - 8;
    }

    private @Nullable Row rowAt(double mx, double my) {
        if (mx < LIST_X || mx >= LIST_X + LIST_W || my < TOP || my >= listBottom()) return null;
        int index = (int) ((my - TOP + scroll) / ROW);
        List<Row> rows = rows();
        return index >= 0 && index < rows.size() && rows.get(index).part() != null ? rows.get(index) : null;
    }

    private @Nullable PcPart heldPart() {
        if (held < 0 || minecraft == null || minecraft.player == null) return null;
        return PcPartItem.part(minecraft.player.getInventory().getItem(held));
    }

    // ---------------------------------------------------------------- case layout

    private static Rect frac(Rect b, double x, double y, double w, double h) {
        return new Rect(b.x + (int) (b.w * x), b.y + (int) (b.h * y), Math.max(4, (int) (b.w * w)), Math.max(4, (int) (b.h * h)));
    }

    private void computeLayout() {
        CaseSize size = build.size();
        int ax = LIST_X + LIST_W + 12;
        int aw = width - ax - 8;
        int ah = height - TOP - 50;
        float k = Math.min(Math.min(aw / (float) size.width, ah / (float) size.height), 1.6F);
        int x0 = ax + (aw - (int) (size.width * k)) / 2;
        int y0 = TOP;
        caseRect = new Rect(x0, y0, (int) (size.width * k), (int) (size.height * k));
        layout.clear();

        PcPart board = build.get("mainboard");
        String form = board != null ? board.str("form") : size.forms.get(0);
        int[] dims = switch (form) {
            case "ATX" -> new int[]{170, 175};
            case "mATX" -> new int[]{150, 150};
            default -> new int[]{120, 120};
        };
        Rect b = new Rect(x0 + (int) (14 * k), y0 + (int) (14 * k), (int) (dims[0] * k), (int) (dims[1] * k));
        if (board != null) {
            Rect cpu = frac(b, 0.28, 0.10, 0.24, 0.20);
            int grow = (int) (6 * k);
            layout.put("cooler", new Rect(cpu.x - grow, cpu.y - grow, cpu.w + 2 * grow, cpu.h + 2 * grow));
            layout.put("cpu", cpu);
            int kits = board.num("ram_slots") / 2;
            for (int i = 0; i < kits; i++) layout.put("ram" + i, frac(b, 0.62 + i * 0.16, 0.06, 0.13, 0.40));
            for (int i = 0; i < Math.min(4, board.num("m2")); i++) layout.put("m2_" + i, frac(b, 0.08, 0.46 + i * 0.08, 0.45, 0.055));
            layout.put("gpu", frac(b, 0.03, 0.80, 0.94, 0.13));
        }
        layout.put("mainboard", b);
        layout.put("psu", new Rect(x0 + (int) (14 * k), y0 + (int) ((size.height - 46) * k), (int) (92 * k), (int) (34 * k)));
        int by = 14;
        for (int i = 0; i < size.bays35; i++, by += 24) {
            layout.put("bay35_" + i, new Rect(x0 + (int) ((size.width - 100) * k), y0 + (int) (by * k), (int) (86 * k), (int) (20 * k)));
        }
        for (int i = 0; i < size.bays25; i++, by += 24) {
            layout.put("bay25_" + i, new Rect(x0 + (int) ((size.width - 100) * k), y0 + (int) (by * k), (int) (86 * k), (int) (20 * k)));
        }
    }

    /** The place under the mouse; the cooler sits over the CPU and wins only when it is there or held. */
    private @Nullable String slotAt(double mx, double my) {
        PcPart heldPart = heldPart();
        for (Map.Entry<String, Rect> entry : layout.entrySet()) {
            String slot = entry.getKey();
            if (!entry.getValue().contains(mx, my)) continue;
            if (slot.equals("cooler")) {
                boolean coolerIn = build.get("cooler") != null;
                boolean holdingCooler = heldPart != null && heldPart.category() == PartCategory.COOLER;
                boolean onCpu = layout.get("cpu").contains(mx, my);
                if (coolerIn || holdingCooler || (!onCpu && build.get("cpu") != null)) return slot;
                continue;
            }
            return slot;
        }
        return null;
    }

    // ---------------------------------------------------------------- drawing

    private static void dashed(GuiGraphics g, Rect r, int color) {
        for (int i = 0; i < r.w; i += 6) {
            int l = Math.min(3, r.w - i);
            g.fill(r.x + i, r.y, r.x + i + l, r.y + 1, color);
            g.fill(r.x + i, r.y + r.h - 1, r.x + i + l, r.y + r.h, color);
        }
        for (int i = 0; i < r.h; i += 6) {
            int l = Math.min(3, r.h - i);
            g.fill(r.x, r.y + i, r.x + 1, r.y + i + l, color);
            g.fill(r.x + r.w - 1, r.y + i, r.x + r.w, r.y + i + l, color);
        }
    }

    private void label(GuiGraphics g, Rect r, String text, int color, boolean top) {
        String fitted = font.plainSubstrByWidth(text, Math.max(0, r.w - 4));
        if (fitted.isEmpty() || r.h < 9) return;
        int x = r.x + (r.w - font.width(fitted)) / 2;
        int y = top ? r.y + 3 : r.y + (r.h - 8) / 2;
        g.drawString(font, fitted, x, y, color, true);
    }

    private void drawSlot(GuiGraphics g, String slot, Rect r) {
        PcPart part = build.get(slot);
        if (part != null) {
            int color = part.category().color;
            g.fill(r.x, r.y, r.x + r.w, r.y + r.h, color);
            g.renderOutline(r.x, r.y, r.w, r.h, 0xFF000000);
            if (!slot.equals("mainboard")) label(g, r, part.name(), WHITE, false);
            return;
        }
        PcPart heldPart = heldPart();
        int color = GRAY;
        if (heldPart != null) {
            if (build.canInstall(slot, heldPart) == null) {
                color = GREEN;
                g.fill(r.x, r.y, r.x + r.w, r.y + r.h, 0x3055FF55);
            } else {
                color = heldPart.category() == PcBuild.categoryOf(slot) ? RED : 0xFF505050;
            }
        }
        dashed(g, r, color);
        String text = PcBuild.slotLabel(slot).getString();
        if (slot.equals("cooler")) {
            PcPart cpu = build.get("cpu");
            if (cpu != null && cpu.bool("box_cooler")) text = tr("cooler_stock").getString();
        }
        label(g, r, text, color, slot.equals("mainboard") || slot.equals("cooler"));
    }

    private void drawCase(GuiGraphics g) {
        Rect c = caseRect;
        g.fill(c.x - 3, c.y - 3, c.x + c.w + 3, c.y + c.h + 3, 0xFF15161A);
        g.fill(c.x, c.y, c.x + c.w, c.y + c.h, 0xFF2A2C31);
        g.drawString(font, build.size().displayName(), c.x, c.y - 12, WHITE, true);

        drawSlot(g, "mainboard", layout.get("mainboard"));
        PcPart board = build.get("mainboard");
        if (board != null) {
            Rect b = layout.get("mainboard");
            g.drawString(font, font.plainSubstrByWidth(board.fullName(), b.w - 6), b.x + 3, b.y + b.h - 11, 0xFFB8E0C0, true);
        }
        for (Map.Entry<String, Rect> entry : layout.entrySet()) {
            String slot = entry.getKey();
            if (slot.equals("mainboard") || slot.equals("cooler")) continue;
            drawSlot(g, slot, entry.getValue());
        }
        // The cooler on top of the CPU: drawn when built in, or as an outline once the CPU is there.
        if (layout.containsKey("cooler") && (build.get("cooler") != null || build.get("cpu") != null)) {
            drawSlot(g, "cooler", layout.get("cooler"));
        }
    }

    private void drawList(GuiGraphics g, int mx, int my) {
        g.fill(LIST_X - 2, TOP - 14, LIST_X + LIST_W + 2, listBottom() + 2, 0xC0101014);
        g.drawString(font, tr("your_parts"), LIST_X + 2, TOP - 11, WHITE, true);
        List<Row> rows = rows();
        int maxScroll = Math.max(0, rows.size() * ROW - (listBottom() - TOP));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        g.enableScissor(LIST_X, TOP, LIST_X + LIST_W, listBottom());
        if (rows.isEmpty()) {
            g.drawWordWrap(font, tr("no_parts"), LIST_X + 4, TOP + 4, LIST_W - 8, GRAY);
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = TOP + i * ROW - scroll;
            if (y + ROW < TOP || y > listBottom()) continue;
            if (row.part() == null) {
                g.drawString(font, row.header().displayName(), LIST_X + 2, y + 6, row.header().color | 0xFF606060, true);
                continue;
            }
            boolean hover = new Rect(LIST_X, y, LIST_W, ROW).contains(mx, my);
            if (row.inv() == held) {
                g.fill(LIST_X, y, LIST_X + LIST_W, y + ROW, 0x6055FF55);
            } else if (hover) {
                g.fill(LIST_X, y, LIST_X + LIST_W, y + ROW, 0x30FFFFFF);
            }
            g.renderItem(row.stack(), LIST_X + 1, y + 1);
            String count = row.stack().getCount() > 1 ? " x" + row.stack().getCount() : "";
            g.drawString(font, font.plainSubstrByWidth(row.part().fullName(), LIST_W - 24 - font.width(count)) + count,
                    LIST_X + 20, y + 5, WHITE, true);
        }
        g.disableScissor();
    }

    private void drawStatus(GuiGraphics g) {
        int y = caseRect.y + caseRect.h + 10;
        int x = caseRect.x;
        Component blocker = build.blocker();
        int maxW = width - x - 8;
        if (powered()) {
            g.drawString(font, tr("running"), x, y, GREEN, true);
        } else if (blocker == null) {
            g.drawString(font, tr("ready"), x, y, GREEN, true);
        } else {
            g.drawString(font, font.plainSubstrByWidth(blocker.getString(), maxW), x, y, build.missing().isEmpty() ? ORANGE : RED, true);
        }
        PcPart psu = build.get("psu");
        Component stats = psu != null
                ? tr("stats_psu", build.threads(), build.ramGb(), build.powerDraw(), psu.num("watts"))
                : tr("stats", build.threads(), build.ramGb(), build.powerDraw());
        g.drawString(font, stats, x, y + 12, GRAY, true);
        if (message != null && Util.getMillis() < messageUntil) {
            g.drawString(font, message, x, y + 24, RED, true);
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        super.render(g, mx, my, partialTick);
        DeviceBlockEntity entity = entity();
        if (entity == null) return;
        build = entity.build();
        computeLayout();

        float p = progress();
        float eased = 1 - (1 - p) * (1 - p) * (1 - p);
        float scale = 0.5F + 0.5F * eased;
        g.pose().pushPose();
        float cx = caseRect.x + caseRect.w / 2F;
        float cy = caseRect.y + caseRect.h / 2F;
        g.pose().translate(cx, cy, 0);
        g.pose().scale(scale, scale, 1);
        g.pose().translate(-cx, -cy, 0);
        drawCase(g);
        g.pose().popPose();
        if (p < 1) return;

        drawList(g, mx, my);
        drawStatus(g);
        tooltips(g, mx, my);
        if (held >= 0 && minecraft.player != null) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            g.renderItem(minecraft.player.getInventory().getItem(held), mx - 8, my - 8);
            g.pose().popPose();
        }
    }

    private void tooltips(GuiGraphics g, int mx, int my) {
        PcPart heldPart = heldPart();
        String slot = slotAt(mx, my);
        if (heldPart != null) {
            if (slot != null && build.get(slot) == null) {
                Component reason = build.canInstall(slot, heldPart);
                g.renderTooltip(font, (reason == null ? tr("click_install") : reason).copy()
                        .withStyle(reason == null ? ChatFormatting.GREEN : ChatFormatting.RED), mx, my);
            }
            return;
        }
        Row row = rowAt(mx, my);
        if (row != null) {
            g.renderTooltip(font, row.stack(), mx, my);
            return;
        }
        if (slot != null) {
            PcPart part = build.get(slot);
            if (part != null) {
                List<Component> lines = new ArrayList<>(getTooltipFromItem(minecraft, PcPartItem.stackOf(part)));
                Component reason = build.canRemove(slot);
                lines.add((reason == null ? tr("click_remove") : reason).copy()
                        .withStyle(reason == null ? ChatFormatting.YELLOW : ChatFormatting.RED));
                g.renderComponentTooltip(font, lines, mx, my);
            } else {
                g.renderTooltip(font, tr("empty", PcBuild.slotLabel(slot)).withStyle(ChatFormatting.GRAY), mx, my);
            }
        }
    }

    // ---------------------------------------------------------------- input

    private void flash(Component text) {
        message = text;
        messageUntil = Util.getMillis() + 3000;
    }

    private void send(String action, String slot, int inv) {
        if (ClientPlayNetworking.canSend(CasePayload.TYPE)) {
            ClientPlayNetworking.send(new CasePayload(pos, action, slot, inv));
        }
    }

    private void tryInstall(String slot) {
        PcPart part = heldPart();
        if (part == null) {
            held = -1;
            return;
        }
        Component reason = powered() ? LOCKED : build.canInstall(slot, part);
        if (reason != null) {
            flash(reason);
        } else {
            send(CasePayload.INSTALL, slot, held);
        }
        held = -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (progress() < 1 || build == null) return true;
        Row row = rowAt(mx, my);
        if (row != null) {
            held = row.inv() == held ? -1 : row.inv();
            return true;
        }
        String slot = slotAt(mx, my);
        if (slot != null) {
            if (held >= 0) {
                tryInstall(slot);
            } else if (build.get(slot) != null) {
                Component reason = powered() ? LOCKED : build.canRemove(slot);
                if (reason != null) flash(reason);
                else send(CasePayload.REMOVE, slot, -1);
            }
            return true;
        }
        held = -1;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (held >= 0 && build != null) {
            String slot = slotAt(mx, my);
            if (slot != null && build.get(slot) == null) {
                tryInstall(slot);
            }
            // Released over the list: keep holding (click a part, then click its place).
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (mx < LIST_X + LIST_W) {
            scroll -= (int) (scrollY * ROW);
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (minecraft != null && minecraft.options.keyInventory.matches(key, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
