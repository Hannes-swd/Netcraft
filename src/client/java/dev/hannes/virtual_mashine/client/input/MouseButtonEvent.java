package dev.hannes.virtual_mashine.client.input;

/** A mouse click as newer Minecraft versions pass it around; 1.21.1 hands over position and button. */
public record MouseButtonEvent(double x, double y, int button) {
}
