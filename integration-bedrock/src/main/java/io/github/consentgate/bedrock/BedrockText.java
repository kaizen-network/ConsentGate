package io.github.consentgate.bedrock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;

final class BedrockText {
    private static final String COLORS = "0123456789abcdef";
    private static final NamedTextColor[] PALETTE = {
            NamedTextColor.BLACK, NamedTextColor.DARK_BLUE, NamedTextColor.DARK_GREEN, NamedTextColor.DARK_AQUA,
            NamedTextColor.DARK_RED, NamedTextColor.DARK_PURPLE, NamedTextColor.GOLD, NamedTextColor.GRAY,
            NamedTextColor.DARK_GRAY, NamedTextColor.BLUE, NamedTextColor.GREEN, NamedTextColor.AQUA,
            NamedTextColor.RED, NamedTextColor.LIGHT_PURPLE, NamedTextColor.YELLOW, NamedTextColor.WHITE
    };

    private BedrockText() { }

    static String serialize(Component component) {
        var output = new StringBuilder();
        append(component, Style.empty(), output);
        return output.append("\u00a7r").toString();
    }

    private static void append(Component component, Style inherited, StringBuilder output) {
        Style style = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            // Bedrock color codes do not reset decorations. Reset every text run explicitly.
            output.append("\u00a7r\u00a7").append(color(style));
            if (style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) output.append("\u00a7l");
            if (style.decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE) output.append("\u00a7o");
            if (style.decoration(TextDecoration.OBFUSCATED) == TextDecoration.State.TRUE) output.append("\u00a7k");
            // Java underline and strikethrough codes select different colors on Bedrock.
            output.append(text.content());
        }
        for (Component child : component.children()) append(child, style, output);
    }

    private static char color(Style style) {
        NamedTextColor color = style.color() == null ? NamedTextColor.WHITE : NamedTextColor.nearestTo(style.color());
        for (int i = 0; i < PALETTE.length; i++) if (PALETTE[i].equals(color)) return COLORS.charAt(i);
        return 'f';
    }
}
