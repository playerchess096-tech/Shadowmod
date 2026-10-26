package com.magialuama.theshadow;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;

import java.util.UUID;

/** Effetti speciali: chat, titoli disturbati, schermo che trema, suoni, buio, fulmini. */
public final class Fx {
    private Fx() {}

    public enum Tone { FRIEND, WATCH, ANGRY, WHISPER, CORRUPT }

    private static final String GLITCH = "█▓▒░#%&@$?!/|<>=+*";

    // ---------- chat ----------
    /** Una riga di chat scritta da "L'Ombra", come se fosse un giocatore vero. */
    public static void chat(ServerPlayer p, String text, Tone tone) {
        if (!Data.cfg.chat) return;
        String t = text.replace("{p}", p.getName().getString());
        MutableComponent name = Component.literal("L'Ombra");
        MutableComponent body;
        switch (tone) {
            case WATCH -> body = Component.literal(t).withStyle(ChatFormatting.GRAY);
            case ANGRY -> {
                name.withStyle(ChatFormatting.DARK_RED);
                body = Component.literal(t).withStyle(ChatFormatting.RED);
            }
            case WHISPER -> {
                name.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
                body = Component.literal(t).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
            }
            case CORRUPT -> body = corrupt(t);
            default -> body = Component.literal(t);
        }
        p.sendSystemMessage(Component.literal("<").append(name).append("> ").append(body));
    }

    /** Frase con qualche parola "rovinata" (testo offuscato di Minecraft). */
    private static MutableComponent corrupt(String t) {
        String[] words = t.split(" ");
        int bad = Director.R.nextInt(words.length);
        MutableComponent out = Component.literal("");
        for (int i = 0; i < words.length; i++) {
            String w = words[i] + (i < words.length - 1 ? " " : "");
            out.append(i == bad || Director.R.nextInt(4) == 0
                    ? Component.literal(w).withStyle(ChatFormatting.DARK_RED, ChatFormatting.OBFUSCATED)
                    : Component.literal(w).withStyle(ChatFormatting.RED));
        }
        return out;
    }

    /** Riga grigia in corsivo, senza nome (narratore). */
    public static void narrator(ServerPlayer p, String text) {
        p.sendSystemMessage(Component.literal(text.replace("{p}", p.getName().getString()))
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    public static void clue(ServerPlayer p, int n, String text) {
        p.sendSystemMessage(Component.literal("[Frammento " + Lines.ROMAN[n] + "] ")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(text).withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC)));
    }

    /** Il vero messaggio giallo di Minecraft "L'Ombra è entrato nel gioco" (tradotto dal client). */
    public static void joinMsg(ServerPlayer p) {
        p.sendSystemMessage(Component.translatable("multiplayer.player.joined", Component.literal("L'Ombra"))
                .withStyle(ChatFormatting.YELLOW));
    }

    public static void leftMsg(ServerPlayer p) {
        p.sendSystemMessage(Component.translatable("multiplayer.player.left", Component.literal("L'Ombra"))
                .withStyle(ChatFormatting.YELLOW));
    }

    // ---------- titoli ----------
    public static void title(ServerPlayer p, Component title, Component sub, int in, int stay, int out) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(in, stay, out));
        p.connection.send(new ClientboundSetSubtitleTextPacket(sub));
        p.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    private static String garble(String s, double chance) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c != ' ' && Director.R.nextDouble() < chance) sb.append(GLITCH.charAt(Director.R.nextInt(GLITCH.length())));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** Titolo "disturbato": lampeggia in caratteri corrotti e poi si legge. */
    public static void glitchTitle(ServerPlayer p, String text) {
        UUID id = p.getUUID();
        for (int i = 0; i < 9; i++) {
            final int f = i;
            Director.later(i * 2, id, q -> {
                boolean clean = f >= 7;
                String s = clean ? text : garble(text, 0.25 + Director.R.nextDouble() * 0.45);
                ChatFormatting col = clean ? ChatFormatting.DARK_RED
                        : (Director.R.nextBoolean() ? ChatFormatting.RED : ChatFormatting.WHITE);
                title(q, Component.literal(s).withStyle(col, ChatFormatting.BOLD), Component.empty(), 0, f == 8 ? 26 : 4, 12);
            });
        }
    }

    // ---------- schermo ----------
    /** Schermo che trema: tanti piccoli "colpi" alla visuale (nessun danno). */
    public static void shake(ServerPlayer p, int ticks) {
        if (!Data.cfg.screenShake) return;
        UUID id = p.getUUID();
        for (int i = 0; i < ticks; i += 2) {
            Director.later(i, id, q -> q.connection.send(new ClientboundHurtAnimationPacket(q.getId(), Director.R.nextFloat() * 360f)));
        }
    }

    public static void darkness(ServerPlayer p, int ticks) {
        if (!Data.cfg.darkness) return;
        p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, ticks, 0, false, false, false));
    }

    public static void blind(ServerPlayer p, int ticks) {
        if (!Data.cfg.darkness) return;
        p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, ticks, 0, false, false, false));
    }

    // ---------- suoni ----------
    public static void soundAt(ServerPlayer p, SoundEvent ev, SoundSource src, double x, double y, double z, float vol, float pitch) {
        p.connection.send(new ClientboundSoundPacket(Holder.direct(ev), src, x, y, z, vol, pitch, Director.R.nextLong()));
    }

    public static void soundSelf(ServerPlayer p, SoundEvent ev, SoundSource src, float vol, float pitch) {
        soundAt(p, ev, src, p.getX(), p.getY(), p.getZ(), vol, pitch);
    }

    /** Zittisce musica e rumori ambientali (non i suoni dell'Ombra). */
    public static void silence(ServerPlayer p) {
        SoundSource[] cats = {SoundSource.MUSIC, SoundSource.AMBIENT, SoundSource.NEUTRAL, SoundSource.WEATHER, SoundSource.BLOCKS, SoundSource.PLAYERS};
        for (SoundSource c : cats) p.connection.send(new ClientboundStopSoundPacket(null, c));
    }

    // ---------- mondo ----------
    public static void particles(ServerLevel level, ParticleOptions type, double x, double y, double z, int n, double dx, double dy, double dz, double speed) {
        level.sendParticles(type, x, y, z, n, dx, dy, dz, speed);
    }

    /** Fulmine solo visivo (niente fuoco, niente danni). Porta con sé il tuono. */
    public static void lightning(ServerLevel level, double x, double y, double z) {
        LightningBolt bolt = new LightningBolt(EntityTypes.LIGHTNING_BOLT, level);
        bolt.setPos(x, y, z);
        bolt.setVisualOnly(true);
        level.addFreshEntity(bolt);
    }
}
