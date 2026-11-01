package dev.hannes.virtual_mashine.client.input;

import net.minecraft.util.StringUtil;

/** A typed character as newer Minecraft versions pass it around; 1.21.1 hands over a char. */
public record CharacterEvent(int codepoint, int modifiers) {
    public boolean isAllowedChatCharacter() {
        return StringUtil.isAllowedChatCharacter((char) codepoint);
    }

    public String codepointAsString() {
        return Character.toString(codepoint);
    }
}
