package dev.hannes.virtual_mashine.client.input;

import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** A key press as newer Minecraft versions pass it around; 1.21.1 hands over the three ints. */
public record KeyEvent(int key, int scancode, int modifiers) {
    public boolean isEscape() {
        return key == GLFW.GLFW_KEY_ESCAPE;
    }

    public boolean isConfirmation() {
        return key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_KP_ENTER;
    }

    public boolean isUp() {
        return key == GLFW.GLFW_KEY_UP;
    }

    public boolean isDown() {
        return key == GLFW.GLFW_KEY_DOWN;
    }

    public boolean hasControlDown() {
        return Screen.hasControlDown();
    }
}
