package dev.hannes.virtual_mashine.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A vanilla-looking button that opens a scrollable list to pick from, for choices too long to click
 * through one by one (ISO files, disk images). Typing while it is open filters the list.
 *
 * <p>The list is drawn and fed by the screen: it calls {@link #extractPopup} after its widgets and hands
 * mouse and key events to {@link #popupMouseClicked} and friends first while {@link #isOpen()}.
 */
final class Dropdown<T> extends AbstractButton {
    private static final Identifier SCROLLER = Identifier.withDefaultNamespace("widget/scroller");
    private static final Identifier SCROLLER_BACKGROUND = Identifier.withDefaultNamespace("widget/scroller_background");
    private static final int ROW = 12;
    private static final int MAX_ROWS = 10;
    private static final int SCROLLBAR = 6;
    private static final int KEY_BACKSPACE = 259;
    private static final int KEY_PAGE_UP = 266;
    private static final int KEY_PAGE_DOWN = 267;
    private static final int KEY_HOME = 268;
    private static final int KEY_END = 269;

    private final Component prefix;
    private final Function<T, Component> label;
    private final Function<T, @Nullable Component> hint;
    private final Consumer<T> onChange;
    private final Font font;
    private final List<T> values;
    private T value;
    /** Shown on the button instead of the value, e.g. while the choice no longer applies. */
    private @Nullable Component override;

    private boolean open;
    private String filter = "";
    private List<T> shown = List.of();
    private int scroll;
    /** Row picked with the arrow keys, -1 = none. */
    private int cursor = -1;
    private boolean draggingScrollbar;
    // Where the list was drawn last, for the mouse.
    private int popupX;
    private int popupY;
    private int popupW;
    private int popupH;
    private int rowsY;
    private int visibleRows;

    /**
     * @param hint short gray text at the right end of a row (e.g. what kind of system an image is), may return null
     */
    Dropdown(int x, int y, int width, int height, Component prefix, List<T> values, T value,
             Function<T, Component> label, Function<T, @Nullable Component> hint, Consumer<T> onChange) {
        super(x, y, width, height, Component.empty());
        this.prefix = prefix;
        this.label = label;
        this.hint = hint;
        this.onChange = onChange;
        this.font = Minecraft.getInstance().font;
        this.values = List.copyOf(values);
        this.value = value;
        updateMessage();
    }

    T getValue() {
        return value;
    }

    void setValue(T newValue) {
        value = newValue;
        updateMessage();
    }

    void setOverride(@Nullable Component text) {
        if (!Objects.equals(override, text)) {
            override = text;
            updateMessage();
        }
    }

    boolean isOpen() {
        return open;
    }

    void close() {
        open = false;
        draggingScrollbar = false;
    }

    private void updateMessage() {
        setMessage(Component.translatable("options.generic_value", prefix, override != null ? override : label.apply(value)));
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (open) {
            close();
            return;
        }
        open = true;
        filter = "";
        refilter();
        int selected = shown.indexOf(value);
        cursor = selected;
        scroll = Math.max(0, selected - MAX_ROWS / 2);
    }

    private void refilter() {
        String wanted = filter.toLowerCase(Locale.ROOT);
        List<T> result = new ArrayList<>();
        for (T candidate : values) {
            if (wanted.isEmpty() || label.apply(candidate).getString().toLowerCase(Locale.ROOT).contains(wanted)) {
                result.add(candidate);
            }
        }
        shown = result;
        cursor = shown.isEmpty() ? -1 : Math.min(Math.max(cursor, 0), shown.size() - 1);
        clampScroll();
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, shown.size() - Math.max(1, visibleRows == 0 ? MAX_ROWS : visibleRows)));
    }

    private void pick(T picked) {
        close();
        if (!Objects.equals(picked, value)) {
            value = picked;
            updateMessage();
            onChange.accept(picked);
        }
        playDownSound(Minecraft.getInstance().getSoundManager());
    }

    // ---------------------------------------------------------------- the button

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        extractDefaultSprite(graphics);
        int textRight = getX() + getWidth() - 12;
        graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE)
                .acceptScrollingWithDefaultCenter(getMessage(), getX() + 4, textRight, getY(), getY() + getHeight());
        // A small triangle like ▼ (▲ while open), drawn with lines so every font has it.
        int color = active ? 0xFFFFFFFF : 0xFFA0A0A0;
        int cx = getX() + getWidth() - 8;
        int cy = getY() + getHeight() / 2 - 1;
        for (int i = 0; i < 3; i++) {
            int row = open ? cy + 1 - i : cy - 1 + i;
            graphics.horizontalLine(cx - 2 + i, cx + 2 - i, row, color);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }

    // ---------------------------------------------------------------- the open list

    /** Draws the open list above everything else of the screen. */
    void extractPopup(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int screenWidth, int screenHeight) {
        if (!open) {
            return;
        }
        graphics.nextStratum();
        int widest = getWidth();
        for (T candidate : shown) {
            Component extra = hint.apply(candidate);
            int w = font.width(label.apply(candidate)) + (extra != null ? font.width(extra) + 10 : 0) + 12 + SCROLLBAR;
            widest = Math.max(widest, w);
        }
        popupW = Math.min(widest, screenWidth - 8);
        popupX = Math.max(4, Math.min(getX(), screenWidth - 4 - popupW));
        int searchH = ROW + 2;
        int below = screenHeight - getBottom() - 6;
        int above = getY() - 6;
        boolean down = below >= Math.min(MAX_ROWS, Math.max(1, shown.size())) * ROW + searchH + 4 || below >= above;
        int room = (down ? below : above) - searchH - 4;
        visibleRows = Math.max(1, Math.min(MAX_ROWS, Math.min(Math.max(1, shown.size()), room / ROW)));
        clampScroll();
        popupH = searchH + visibleRows * ROW + 4;
        popupY = down ? getBottom() + 1 : getY() - 1 - popupH;

        // Frame like a vanilla text field: light gray border, black inside.
        graphics.fill(popupX, popupY, popupX + popupW, popupY + popupH, 0xFFA0A0A0);
        graphics.fill(popupX + 1, popupY + 1, popupX + popupW - 1, popupY + popupH - 1, 0xF0000000);

        // Search line.
        String search = filter.isEmpty() ? Component.translatable("virtual_mashine.dropdown.search").getString()
                : filter + ((System.currentTimeMillis() / 400) % 2 == 0 ? "_" : "");
        graphics.text(font, font.plainSubstrByWidth(search, popupW - 8, filter.length() > 0), popupX + 4, popupY + 4,
                filter.isEmpty() ? 0xFF707070 : 0xFFFFFFFF, false);
        graphics.horizontalLine(popupX + 1, popupX + popupW - 2, popupY + searchH, 0xFF404040);

        rowsY = popupY + searchH + 2;
        int listRight = popupX + popupW - 2 - (shown.size() > visibleRows ? SCROLLBAR + 1 : 0);
        if (shown.isEmpty()) {
            graphics.text(font, Component.translatable("virtual_mashine.dropdown.nothing"), popupX + 4, rowsY + 2, 0xFF808080, false);
        }
        int hovered = rowAt(mouseX, mouseY);
        for (int i = 0; i < visibleRows && scroll + i < shown.size(); i++) {
            int index = scroll + i;
            T candidate = shown.get(index);
            int top = rowsY + i * ROW;
            boolean selected = Objects.equals(candidate, value);
            if (index == hovered || index == cursor && hovered < 0) {
                graphics.fill(popupX + 2, top, listRight, top + ROW, 0xFF3A3A3A);
            }
            if (selected) {
                graphics.outline(popupX + 2, top, listRight - popupX - 2, ROW, 0xFFFFFFFF);
            }
            Component extra = hint.apply(candidate);
            int extraW = extra != null ? font.width(extra) + 6 : 0;
            int textW = listRight - popupX - 8 - extraW;
            String text = label.apply(candidate).getString();
            if (font.width(text) > textW) {
                text = font.plainSubstrByWidth(text, textW - font.width("…")) + "…";
            }
            graphics.text(font, text, popupX + 5, top + 2, selected ? 0xFFFFFF55 : 0xFFE0E0E0, false);
            if (extra != null) {
                graphics.text(font, extra, listRight - extraW + 2, top + 2, 0xFF8A8A8A, false);
            }
        }
        if (shown.size() > visibleRows) {
            int barX = popupX + popupW - 2 - SCROLLBAR;
            int trackH = visibleRows * ROW;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER_BACKGROUND, barX, rowsY, SCROLLBAR, trackH);
            int thumbH = Math.max(8, trackH * visibleRows / shown.size());
            int thumbY = rowsY + (trackH - thumbH) * scroll / Math.max(1, shown.size() - visibleRows);
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER, barX, thumbY, SCROLLBAR, thumbH);
        }
    }

    /** Index into {@link #shown} under the mouse, or -1. */
    private int rowAt(double mouseX, double mouseY) {
        int listRight = popupX + popupW - 2 - (shown.size() > visibleRows ? SCROLLBAR + 1 : 0);
        if (mouseX < popupX + 2 || mouseX >= listRight || mouseY < rowsY || mouseY >= rowsY + visibleRows * ROW) {
            return -1;
        }
        int index = scroll + (int) ((mouseY - rowsY) / ROW);
        return index < shown.size() ? index : -1;
    }

    private boolean onScrollbar(double mouseX, double mouseY) {
        int barX = popupX + popupW - 2 - SCROLLBAR;
        return shown.size() > visibleRows && mouseX >= barX && mouseX < barX + SCROLLBAR
                && mouseY >= rowsY && mouseY < rowsY + visibleRows * ROW;
    }

    private boolean insidePopup(double mouseX, double mouseY) {
        return mouseX >= popupX && mouseX < popupX + popupW && mouseY >= popupY && mouseY < popupY + popupH;
    }

    /** Every click goes here while the list is open: a row picks it, anywhere else just closes the list. */
    boolean popupMouseClicked(MouseButtonEvent event) {
        int row = rowAt(event.x(), event.y());
        if (row >= 0) {
            pick(shown.get(row));
        } else if (onScrollbar(event.x(), event.y())) {
            draggingScrollbar = true;
            scrollTo(event.y());
        } else if (!insidePopup(event.x(), event.y())) {
            close(); // Also when the button itself was clicked: that is how it closes again.
        }
        return true;
    }

    boolean popupMouseDragged(MouseButtonEvent event) {
        if (draggingScrollbar) {
            scrollTo(event.y());
        }
        return true;
    }

    boolean popupMouseReleased() {
        draggingScrollbar = false;
        return true;
    }

    private void scrollTo(double mouseY) {
        double part = (mouseY - rowsY) / Math.max(1, visibleRows * ROW);
        scroll = (int) Math.round(part * (shown.size() - visibleRows));
        clampScroll();
    }

    boolean popupMouseScrolled(double scrollY) {
        scroll -= (int) Math.signum(scrollY) * (Math.abs(scrollY) >= 3 ? 3 : 1);
        clampScroll();
        return true;
    }

    boolean popupKeyPressed(KeyEvent event) {
        if (event.isEscape()) {
            close();
        } else if (event.isConfirmation()) {
            if (cursor >= 0 && cursor < shown.size()) {
                pick(shown.get(cursor));
            }
        } else if (event.isUp() || event.isDown() || event.key() == KEY_PAGE_UP || event.key() == KEY_PAGE_DOWN
                || event.key() == KEY_HOME || event.key() == KEY_END) {
            if (!shown.isEmpty()) {
                int step = switch (event.key()) {
                    case KEY_PAGE_UP -> -visibleRows;
                    case KEY_PAGE_DOWN -> visibleRows;
                    case KEY_HOME -> -shown.size();
                    case KEY_END -> shown.size();
                    default -> event.isUp() ? -1 : 1;
                };
                cursor = Math.max(0, Math.min(shown.size() - 1, cursor + step));
                if (cursor < scroll) {
                    scroll = cursor;
                } else if (cursor >= scroll + visibleRows) {
                    scroll = cursor - visibleRows + 1;
                }
                clampScroll();
            }
        } else if (event.key() == KEY_BACKSPACE) {
            if (!filter.isEmpty()) {
                filter = event.hasControlDown() ? "" : filter.substring(0, filter.length() - 1);
                refilter();
            }
        }
        return true; // Nothing else of the screen reacts while the list is open (Escape must not close the screen).
    }

    boolean popupCharTyped(CharacterEvent event) {
        if (event.isAllowedChatCharacter() && filter.length() < 40) {
            filter += event.codepointAsString();
            cursor = 0;
            scroll = 0;
            refilter();
        }
        return true;
    }
}
