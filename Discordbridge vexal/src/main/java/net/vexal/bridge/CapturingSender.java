package net.vexal.bridge;

import net.kyori.adventure.audience.MessageType;
import net.kyori.adventure.identity.Identified;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.conversations.Conversation;
import org.bukkit.conversations.ConversationAbandonedEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Console sender that captures command output for the bridge result payload.
 */
@SuppressWarnings("deprecation")
public final class CapturingSender implements ConsoleCommandSender {

    private static final int MAX_CHARS = 1500;
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final ConsoleCommandSender delegate;
    private final List<String> lines = new ArrayList<>();
    private int capturedChars;

    public CapturingSender(ConsoleCommandSender delegate) {
        this.delegate = delegate;
    }

    public String capturedOutput() {
        if (lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines);
    }

    private void capture(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (capturedChars >= MAX_CHARS) {
            return;
        }
        int remaining = MAX_CHARS - capturedChars;
        String chunk = text.length() > remaining ? text.substring(0, remaining) : text;
        lines.add(chunk);
        capturedChars += chunk.length();
        if (chunk.length() < text.length() && capturedChars < MAX_CHARS) {
            // account for potential join newlines already counted loosely
            capturedChars = MAX_CHARS;
        }
    }

    private void captureComponent(Component message) {
        if (message == null) {
            return;
        }
        capture(PLAIN.serialize(message));
    }

    private void safeDelegate(Runnable action) {
        if (delegate == null) {
            return;
        }
        try {
            action.run();
        } catch (Throwable ignored) {
            // must not throw if delegate is null or misbehaves
        }
    }

    @Override
    public void sendMessage(String message) {
        capture(message);
        safeDelegate(() -> delegate.sendMessage(message));
    }

    @Override
    public void sendMessage(String... messages) {
        if (messages != null) {
            for (String message : messages) {
                capture(message);
            }
        }
        safeDelegate(() -> delegate.sendMessage(messages));
    }

    @Override
    public void sendMessage(UUID sender, String message) {
        capture(message);
        safeDelegate(() -> delegate.sendMessage(sender, message));
    }

    @Override
    public void sendMessage(UUID sender, String... messages) {
        if (messages != null) {
            for (String message : messages) {
                capture(message);
            }
        }
        safeDelegate(() -> delegate.sendMessage(sender, messages));
    }

    @Override
    public void sendMessage(Component message) {
        captureComponent(message);
        safeDelegate(() -> delegate.sendMessage(message));
    }

    @Override
    public void sendMessage(Identified source, Component message) {
        captureComponent(message);
        safeDelegate(() -> delegate.sendMessage(source, message));
    }

    @Override
    public void sendMessage(Identity source, Component message) {
        captureComponent(message);
        safeDelegate(() -> delegate.sendMessage(source, message));
    }

    @Override
    public void sendMessage(Component message, MessageType type) {
        captureComponent(message);
        safeDelegate(() -> delegate.sendMessage(message, type));
    }

    @Override
    public void sendMessage(Identified source, Component message, MessageType type) {
        captureComponent(message);
        safeDelegate(() -> delegate.sendMessage(source, message, type));
    }

    @Override
    public void sendMessage(Identity source, Component message, MessageType type) {
        captureComponent(message);
        safeDelegate(() -> delegate.sendMessage(source, message, type));
    }

    @Override
    public void sendRichMessage(String message) {
        capture(message);
        safeDelegate(() -> delegate.sendRichMessage(message));
    }

    @Override
    public void sendPlainMessage(String message) {
        capture(message);
        safeDelegate(() -> delegate.sendPlainMessage(message));
    }

    @Override
    public void sendRawMessage(String message) {
        capture(message);
        safeDelegate(() -> delegate.sendRawMessage(message));
    }

    @Override
    public void sendRawMessage(UUID sender, String message) {
        capture(message);
        safeDelegate(() -> delegate.sendRawMessage(sender, message));
    }

    @Override
    public String getName() {
        return "VexalBridge";
    }

    @Override
    public Component name() {
        return Component.text("VexalBridge");
    }

    @Override
    public boolean isOp() {
        return true;
    }

    @Override
    public void setOp(boolean value) {
        // bridge sender is always op; ignore mutations
    }

    @Override
    public boolean isPermissionSet(String name) {
        return true;
    }

    @Override
    public boolean isPermissionSet(Permission perm) {
        return true;
    }

    @Override
    public boolean hasPermission(String name) {
        return true;
    }

    @Override
    public boolean hasPermission(Permission perm) {
        return true;
    }

    @Override
    public Server getServer() {
        return delegate != null ? delegate.getServer() : org.bukkit.Bukkit.getServer();
    }

    @Override
    public boolean isConversing() {
        return delegate != null && delegate.isConversing();
    }

    @Override
    public void acceptConversationInput(String input) {
        safeDelegate(() -> delegate.acceptConversationInput(input));
    }

    @Override
    public boolean beginConversation(Conversation conversation) {
        if (delegate == null) {
            return false;
        }
        try {
            return delegate.beginConversation(conversation);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void abandonConversation(Conversation conversation) {
        safeDelegate(() -> delegate.abandonConversation(conversation));
    }

    @Override
    public void abandonConversation(Conversation conversation, ConversationAbandonedEvent details) {
        safeDelegate(() -> delegate.abandonConversation(conversation, details));
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) {
        if (delegate == null) {
            return null;
        }
        try {
            return delegate.addAttachment(plugin, name, value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin) {
        if (delegate == null) {
            return null;
        }
        try {
            return delegate.addAttachment(plugin);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) {
        if (delegate == null) {
            return null;
        }
        try {
            return delegate.addAttachment(plugin, name, value, ticks);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, int ticks) {
        if (delegate == null) {
            return null;
        }
        try {
            return delegate.addAttachment(plugin, ticks);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public void removeAttachment(PermissionAttachment attachment) {
        safeDelegate(() -> delegate.removeAttachment(attachment));
    }

    @Override
    public void recalculatePermissions() {
        safeDelegate(delegate::recalculatePermissions);
    }

    @Override
    public Set<PermissionAttachmentInfo> getEffectivePermissions() {
        if (delegate == null) {
            return Set.of();
        }
        try {
            return delegate.getEffectivePermissions();
        } catch (Throwable ignored) {
            return Set.of();
        }
    }

    @Override
    public Spigot spigot() {
        if (delegate == null) {
            throw new IllegalStateException("No console delegate available");
        }
        return delegate.spigot();
    }
}
