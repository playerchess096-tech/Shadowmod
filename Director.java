package com.magialuama.theshadow;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Il "regista" dell'horror: ascolta il gioco, decide quando far succedere cosa,
 * gestisce fasi/ondate, regole di combattimento e reazioni di L'Ombra.
 */
public final class Director {
    private Director() {}

    static final Random R = new Random();

    /**
     * Il tag vanilla #minecraft:diamond_ores (diamond_ore e deepslate_diamond_ore).
     * Da 26.2 la costante BlockTags.DIAMOND_ORES non esiste piu': i tag che esistono sia per i blocchi sia per gli
     * oggetti sono passati in BlockItemTags. Il tag di dati e' rimasto identico, quindi lo si costruisce dal suo id.
     */
    private static final TagKey<Block> DIAMOND_ORES =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("minecraft", "diamond_ores"));
    private static MinecraftServer server;
    private static long now = 0;
    private static final Map<UUID, Rt> RT = new HashMap<>();
    private static final List<Task> TASKS = new ArrayList<>();

    private record Task(long due, Runnable run) {}

    /** Stato temporaneo di un giocatore (non viene salvato). */
    static final class Rt {
        long nextEvent;
        double tension = 5;
        boolean wasSleeping;
        ShadowEntity boss;
        final List<ShadowEntity> echoes = new ArrayList<>();
        final Map<String, Long> cooldown = new HashMap<>();
        final Set<Long> seenSpawners = new HashSet<>();
        long lastJump;
        long lastHit;
        long waveUntil;
        int safeTicks;
    }

    private static Rt rt(UUID id) {
        return RT.computeIfAbsent(id, k -> new Rt());
    }

    public static long now() {
        return now;
    }

    // ------------------------------------------------------------------ pianificazione

    static void later(int ticks, UUID id, Consumer<ServerPlayer> task) {
        TASKS.add(new Task(now + Math.max(0, ticks), () -> {
            if (server == null) return;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) task.accept(p);
        }));
    }

    static void later(int ticks, ServerPlayer p, Consumer<ServerPlayer> task) {
        later(ticks, p.getUUID(), task);
    }

    // ------------------------------------------------------------------ avvio

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
            server = srv;
            Data.load(srv);
            RT.clear();
            TASKS.clear();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            Data.save(srv);
            for (UUID id : new ArrayList<>(RT.keySet())) clearShadows(id);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> onJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> onLeave(handler.getPlayer(), srv));
        ServerTickEvents.END_SERVER_TICK.register(Director::tick);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(Director::allowDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register(Director::afterDeath);
        PlayerBlockBreakEvents.AFTER.register(Director::afterBreak);
    }

    private static void onJoin(ServerPlayer p) {
        if (!Data.cfg.enabled) return;
        Data.PlayerData pd = Data.of(p.getUUID());
        Rt rt = rt(p.getUUID());
        rt.nextEvent = now + 20L * 100;
        rt.waveUntil = 0;
        if (!pd.met) {
            pd.met = true;
            pd.clues.add(1);
            later(120, p, q -> Fx.chat(q, Lines.pick(Lines.GREET_FIRST), Fx.Tone.FRIEND));
            later(400, p, q -> Fx.clue(q, 1, Lines.CLUES[1]));
        } else if (!(pd.defeated && !pd.nightmare) && R.nextInt(100) < 40) {
            later(140, p, q -> Fx.chat(q, Lines.pick(Lines.WELCOME_BACK), pd.phase >= 3 ? Fx.Tone.WATCH : Fx.Tone.FRIEND));
        }
    }

    private static void onLeave(ServerPlayer p, MinecraftServer srv) {
        clearShadows(p.getUUID());
        Data.save(srv);
    }

    // ------------------------------------------------------------------ tick

    private static void tick(MinecraftServer srv) {
        server = srv;
        now++;
        if (!TASKS.isEmpty()) {
            List<Task> due = new ArrayList<>();
            for (Iterator<Task> it = TASKS.iterator(); it.hasNext(); ) {
                Task t = it.next();
                if (t.due() <= now) {
                    due.add(t);
                    it.remove();
                }
            }
            for (Task t : due) {
                try {
                    t.run().run();
                } catch (Exception e) {
                    TheShadow.LOG.warn("Task fallito: {}", e.toString());
                }
            }
        }
        if (!Data.cfg.enabled) return;
        for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
            if (!p.isSpectator()) tickPlayer(p);
        }
        if (now % 6000 == 0) Data.save(srv);
    }

    private static void tickPlayer(ServerPlayer p) {
        if (!(p.level() instanceof ServerLevel level)) return;
        UUID id = p.getUUID();
        Data.PlayerData pd = Data.of(id);
        Rt rt = rt(id);
        pd.playTicks++;

        boolean sleeping = p.isSleeping();
        if (sleeping && !rt.wasSleeping) onSleepStart(p, pd);
        if (!sleeping && rt.wasSleeping) onSleepEnd(p, pd);
        rt.wasSleeping = sleeping;

        if (Math.floorMod(now + id.hashCode(), 20L) == 0) slowTick(p, level, pd, rt);
    }

    /** Una volta al secondo per giocatore. */
    private static void slowTick(ServerPlayer p, ServerLevel level, Data.PlayerData pd, Rt rt) {
        if (rt.boss != null && rt.boss.isRemoved()) rt.boss = null;
        rt.echoes.removeIf(Entity::isRemoved);

        // tensione invisibile: sale al buio, sotto terra e con la pioggia, scende alla luce del sole
        BlockPos pos = p.blockPosition();
        boolean sky = level.canSeeSky(pos);
        boolean bright = level.getMaxLocalRawBrightness(pos) >= 12;
        double d = -0.25;
        if (!sky && p.getBlockY() < 55) d += 0.9;
        if (sky && !bright) d += 0.5;
        if (level.isRaining()) d += 0.3;
        if (sky && bright) d -= 1.2;
        rt.tension = Math.max(pd.phase * 4.0, Math.min(100.0, rt.tension + d));

        if (pd.defeated && !pd.nightmare) return;
        checkMilestones(p, level, pd, rt);
        if (((now / 20) & 1) == 0) checkSpawner(p, level, pd, rt);

        if (now >= rt.nextEvent && now >= rt.waveUntil && pd.met) {
            Ev ev = pick(level, p, pd, rt);
            rt.nextEvent = now + nextInterval(pd, rt);
            if (ev != null) {
                runEvent(ev, p, level, pd, rt);
                rt.cooldown.put(ev.name(), now + cooldownTicks(ev));
                rt.tension = Math.min(100.0, rt.tension + 6);
            }
        }
    }

    // ------------------------------------------------------------------ traguardi, fasi, ondate

    private static boolean wears(ServerPlayer p, Item h, Item c, Item l, Item b) {
        return p.getItemBySlot(EquipmentSlot.HEAD).is(h)
                && p.getItemBySlot(EquipmentSlot.CHEST).is(c)
                && p.getItemBySlot(EquipmentSlot.LEGS).is(l)
                && p.getItemBySlot(EquipmentSlot.FEET).is(b);
    }

    private static boolean hasItem(ServerPlayer p, Item item) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) return true;
        }
        return false;
    }

    /** Ogni traguardo scatta UNA sola volta (il flag resta salvato nel mondo). */
    private static void checkMilestones(ServerPlayer p, ServerLevel level, Data.PlayerData pd, Rt rt) {
        if (now < rt.waveUntil) return; // una ondata alla volta

        if (pd.phase < 2 && pd.playTicks >= 20L * 60 * 12) {
            pd.flags.add("tempo");
            raise(p, level, pd, rt, 2);
            return;
        }
        if (!pd.has("ferro") && wears(p, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS)) {
            pd.flags.add("ferro");
            raise(p, level, pd, rt, 3);
            return;
        }
        if (!pd.has("diamante") && hasItem(p, Items.DIAMOND)) {
            pd.flags.add("diamante");
            pd.remember("hai trovato i diamanti a Y=" + p.getBlockY());
            raise(p, level, pd, rt, 4);
            return;
        }
        if (!pd.has("nether") && level.dimension() == Level.NETHER) {
            pd.flags.add("nether");
            pd.remember("hai attraversato il portale");
            raise(p, level, pd, rt, 5);
            return;
        }
        if (!pd.has("armdiam") && wears(p, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS)) {
            pd.flags.add("armdiam");
            raise(p, level, pd, rt, 6);
            return;
        }
        if (!pd.has("netherite") && wears(p, Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS)) {
            pd.flags.add("netherite");
            raise(p, level, pd, rt, 7);
            return;
        }
        // ondata a tempo: ogni 15 minuti di gioco, dalla fase 2 in poi
        if (pd.phase >= 2) {
            if (pd.nextTimeWaveAt == 0) {
                pd.nextTimeWaveAt = pd.playTicks + 20L * 60 * 15;
            } else if (pd.playTicks >= pd.nextTimeWaveAt) {
                pd.nextTimeWaveAt = pd.playTicks + 20L * 60 * 15;
                startWave(p, level, pd, rt, 0);
            }
        }
    }

    private static void raise(ServerPlayer p, ServerLevel level, Data.PlayerData pd, Rt rt, int idx) {
        pd.phase = Math.max(pd.phase, idx);
        if (pd.clues.add(idx)) {
            later(240, p, q -> Fx.clue(q, idx, Lines.CLUES[idx]));
        }
        startWave(p, level, pd, rt, idx);
        rt.nextEvent = now + 20L * 60;
    }

    /** idx 0 = ondata a tempo, 2..8 = ondata del traguardo corrispondente. */
    static void startWave(ServerPlayer p, ServerLevel level, Data.PlayerData pd, Rt rt, int idx) {
        boolean time = idx == 0;
        String[] w = time ? Lines.TIME_WAVE : Lines.WAVES[Math.max(1, Math.min(8, idx))];
        rt.waveUntil = now + 20L * (time ? 60 : 100);
        Fx.title(p,
                Component.literal(w[0]).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                Component.literal(w[1]).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC),
                10, 70, 25);
        Fx.soundSelf(p, ModSounds.WAVE, SoundSource.HOSTILE, 1.0f, 1.0f);
        Fx.shake(p, 8);
        farLightning(p, level);
        later(50, p, q -> Fx.chat(q, w[2], pd.phase >= 5 ? Fx.Tone.ANGRY : Fx.Tone.WATCH));

        boolean bodies = Data.cfg.shadowBody && !pd.defeated && level.getDifficulty() != Difficulty.PEACEFUL;
        if (bodies) later(80, p, q -> spawnWave(q, pd, time));
    }

    private static void spawnWave(ServerPlayer p, Data.PlayerData pd, boolean time) {
        if (!(p.level() instanceof ServerLevel level)) return;
        Rt rt = rt(p.getUUID());
        ShadowEntity boss = bossActive(rt) ? rt.boss
                : spawnShadow(p, level, ShadowEntity.Mode.HUNT, 16, 22, lookAngle(p) + Math.PI, false, false);
        if (boss != null) {
            boss.mode = (pd.phase >= 3 && Data.cfg.hostile) ? ShadowEntity.Mode.HUNT : ShadowEntity.Mode.STALK;
            boss.expireTick = now + 20L * (time ? 55 : 95);
            Fx.soundAt(p, ModSounds.APPEAR, SoundSource.HOSTILE, boss.getX(), boss.getY(), boss.getZ(), 1.0f, 1.0f);
        }
        if (pd.phase < 3 || !Data.cfg.hostile) return;
        int n = Math.min(6, (time ? 1 + pd.phase / 3 : 2 + pd.phase / 2) + (pd.nightmare ? 2 : 0));
        for (int i = 0; i < n; i++) {
            ShadowEntity e = spawnShadow(p, level, ShadowEntity.Mode.HUNT, 10, 18, Double.NaN, false, true);
            if (e != null) e.expireTick = now + 20L * 50;
        }
    }

    // ------------------------------------------------------------------ eventi horror

    enum Ev { WHISPER, FOOTSTEPS, DOOR, THUNDER, FIGURE, ECHO, FAKE_JOIN, STALK, ISOLATION, AMBUSH, JUMPSCARE }

    private record Def(Ev ev, int minPhase, int weight, int cooldownSec) {}

    private static final Def[] DEFS = {
            new Def(Ev.WHISPER, 1, 22, 45),
            new Def(Ev.FOOTSTEPS, 1, 24, 100),
            new Def(Ev.DOOR, 1, 12, 150),
            new Def(Ev.THUNDER, 2, 14, 220),
            new Def(Ev.FIGURE, 2, 16, 160),
            new Def(Ev.ECHO, 2, 12, 120),
            new Def(Ev.FAKE_JOIN, 3, 9, 420),
            new Def(Ev.STALK, 4, 9, 300),
            new Def(Ev.ISOLATION, 5, 5, 700),
            new Def(Ev.AMBUSH, 5, 7, 420),
            new Def(Ev.JUMPSCARE, 4, 5, 540),
    };

    /** Minuti medi tra due eventi, per fase (indice = fase). */
    private static final double[] BASE_MIN = {0, 3.5, 3.0, 2.4, 2.0, 1.6, 1.3, 1.1, 0.9};

    static final String[] EVENT_NAMES = {"sussurro", "passi", "porta", "tuono", "figura", "eco", "finto",
            "pedinamento", "isolamento", "agguato", "jumpscare"};

    private static long cooldownTicks(Ev ev) {
        for (Def d : DEFS) if (d.ev() == ev) return 20L * d.cooldownSec();
        return 0;
    }

    private static long nextInterval(Data.PlayerData pd, Rt rt) {
        double min = BASE_MIN[Math.max(1, Math.min(8, pd.phase))] * (pd.nightmare ? 0.6 : 1.0);
        double mult = Math.max(0.25, Data.cfg.speed) * (0.75 + rt.tension / 100.0 * 0.8);
        double jitter = 0.7 + R.nextDouble() * 0.6;
        return (long) (min * 60 * 20 * jitter / mult);
    }

    private static boolean bossActive(Rt rt) {
        return rt.boss != null && !rt.boss.isRemoved() && rt.boss.isAlive();
    }

    private static Ev pick(ServerLevel level, ServerPlayer p, Data.PlayerData pd, Rt rt) {
        boolean bodies = Data.cfg.shadowBody && !pd.defeated
                && level.getDifficulty() != Difficulty.PEACEFUL && !bossActive(rt);
        boolean under = !level.canSeeSky(p.blockPosition()) && p.getBlockY() < 58;
        boolean rain = level.isRaining();
        int[] w = new int[DEFS.length];
        int total = 0;
        for (int i = 0; i < DEFS.length; i++) {
            Def d = DEFS[i];
            if (pd.phase < d.minPhase() || rt.cooldown.getOrDefault(d.ev().name(), 0L) > now) continue;
            int wt = d.weight();
            switch (d.ev()) {
                case FIGURE -> wt = bodies ? (rain ? wt * 3 : wt) : 0;
                case STALK -> wt = bodies ? wt : 0;
                case AMBUSH -> wt = (bodies && Data.cfg.hostile) ? wt : 0;
                case ECHO -> wt = under ? wt * 3 : 0;
                case THUNDER -> wt = rain ? wt * 2 : wt;
                case DOOR -> wt = Data.cfg.doors ? wt : 0;
                case FAKE_JOIN -> wt = Data.cfg.fakeJoin ? wt : 0;
                case JUMPSCARE -> wt = jumpOk(rt) ? Math.max(1, Data.cfg.jumpscareChance / 2) : 0;
                default -> { }
            }
            w[i] = wt;
            total += wt;
        }
        if (total <= 0) return null;
        int r = R.nextInt(total);
        for (int i = 0; i < DEFS.length; i++) {
            r -= w[i];
            if (r < 0) return DEFS[i].ev();
        }
        return null;
    }

    private static boolean jumpOk(Rt rt) {
        return Data.cfg.jumpscare && Data.cfg.jumpscareChance > 0 && now - rt.lastJump > 20L * 60 * 8 && rt.tension >= 30;
    }

    private static void runEvent(Ev ev, ServerPlayer p, ServerLevel level, Data.PlayerData pd, Rt rt) {
        switch (ev) {
            case WHISPER -> evWhisper(p, pd);
            case FOOTSTEPS -> evFootsteps(p, pd);
            case DOOR -> evDoor(p, level);
            case THUNDER -> evThunder(p, level, pd);
            case FIGURE -> evFigure(p, level);
            case ECHO -> evEcho(p);
            case FAKE_JOIN -> evFakeJoin(p);
            case STALK -> evStalk(p, level);
            case ISOLATION -> evIsolation(p);
            case AMBUSH -> evAmbush(p, level);
            case JUMPSCARE -> evJumpscare(p, level, rt);
        }
    }

    /** Usato dai comandi di prova: lancia un evento subito. */
    static boolean force(ServerPlayer p, String name) {
        if (!(p.level() instanceof ServerLevel level)) return false;
        Data.PlayerData pd = Data.of(p.getUUID());
        Rt rt = rt(p.getUUID());
        Ev ev = switch (name) {
            case "sussurro" -> Ev.WHISPER;
            case "passi" -> Ev.FOOTSTEPS;
            case "porta" -> Ev.DOOR;
            case "tuono" -> Ev.THUNDER;
            case "figura" -> Ev.FIGURE;
            case "eco" -> Ev.ECHO;
            case "finto" -> Ev.FAKE_JOIN;
            case "pedinamento" -> Ev.STALK;
            case "isolamento" -> Ev.ISOLATION;
            case "agguato" -> Ev.AMBUSH;
            case "jumpscare" -> Ev.JUMPSCARE;
            default -> null;
        };
        if (ev == null) return false;
        if (ev == Ev.FIGURE || ev == Ev.STALK || ev == Ev.AMBUSH) clearShadows(p.getUUID());
        runEvent(ev, p, level, pd, rt);
        return true;
    }

    private static void evWhisper(ServerPlayer p, Data.PlayerData pd) {
        String line;
        if (!pd.memory.isEmpty() && R.nextInt(100) < 35) {
            line = Lines.pick(Lines.MEMORY).replace("{m}", pd.memory.get(R.nextInt(pd.memory.size())));
        } else if (pd.phase >= 5) {
            line = Lines.pick(R.nextBoolean() ? Lines.ANGRY : Lines.TAUNT);
        } else if (pd.phase >= 3) {
            line = Lines.pick(R.nextInt(3) == 0 ? Lines.TAUNT : Lines.WATCH);
        } else {
            line = Lines.pick(R.nextInt(3) == 0 ? Lines.WATCH : Lines.FRIEND);
        }
        Fx.Tone tone = pd.phase >= 5 ? Fx.Tone.ANGRY : (pd.phase >= 3 ? Fx.Tone.WATCH : Fx.Tone.FRIEND);
        if (R.nextInt(100) < 20) {
            tone = Fx.Tone.WHISPER;
            Fx.soundSelf(p, ModSounds.WHISPER, SoundSource.HOSTILE, 0.8f, 1.0f);
        }
        Fx.chat(p, line, tone);
    }

    /** Passi alle tue spalle: si avvicinano e poi si fermano. */
    private static void evFootsteps(ServerPlayer p, Data.PlayerData pd) {
        int n = 5 + R.nextInt(4);
        int t = 5;
        for (int i = 0; i < n; i++) {
            final int k = i;
            later(t, p, q -> {
                Vec3 look = q.getLookAngle();
                Vec3 flat = new Vec3(look.x, 0, look.z).normalize();
                double dist = Math.max(2.0, 7.0 - k * 0.7);
                Vec3 pos = q.position().subtract(flat.scale(dist));
                Fx.soundAt(q, ModSounds.FOOTSTEP, SoundSource.HOSTILE, pos.x, pos.y, pos.z, 0.9f, 0.85f + R.nextFloat() * 0.25f);
            });
            t += 9 + R.nextInt(4);
        }
        if (pd.phase >= 3 && R.nextInt(100) < 40) {
            later(t + 20, p, q -> Fx.chat(q, "Mi senti?", Fx.Tone.WHISPER));
        }
    }

    /** Una porta di legno vicina si apre da sola (o si sente cigolare alle spalle). */
    private static void evDoor(ServerPlayer p, ServerLevel level) {
        BlockPos c = p.blockPosition();
        List<BlockPos> doors = new ArrayList<>();
        for (BlockPos bp : BlockPos.betweenClosed(c.offset(-12, -4, -12), c.offset(12, 4, 12))) {
            BlockState st = level.getBlockState(bp);
            if (st.is(BlockTags.WOODEN_DOORS) && st.getBlock() instanceof DoorBlock
                    && !st.getValue(DoorBlock.OPEN)
                    && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                    && bp.distSqr(c) > 9) {
                doors.add(bp.immutable());
            }
        }
        if (!doors.isEmpty()) {
            BlockPos door = doors.get(R.nextInt(doors.size()));
            BlockState st = level.getBlockState(door);
            ((DoorBlock) st.getBlock()).setOpen(null, level, st, door, true);
            Fx.soundAt(p, ModSounds.CREAK, SoundSource.HOSTILE, door.getX() + 0.5, door.getY() + 1.0, door.getZ() + 0.5, 0.9f, 1.0f);
        } else {
            double a = lookAngle(p) + Math.PI + (R.nextDouble() - 0.5);
            Fx.soundAt(p, ModSounds.CREAK, SoundSource.HOSTILE, p.getX() + Math.cos(a) * 6, p.getY(), p.getZ() + Math.sin(a) * 6, 1.0f, 0.9f);
        }
    }

    /** Fulmine lontano, poi il battito del cuore che accelera. */
    private static void evThunder(ServerPlayer p, ServerLevel level, Data.PlayerData pd) {
        farLightning(p, level);
        int beats = 9 + R.nextInt(5);
        int t = 45;
        for (int i = 0; i < beats; i++) {
            final int k = i;
            later(t, p, q -> {
                Fx.soundSelf(q, k > beats / 2 ? ModSounds.HEARTBEAT_FAST : ModSounds.HEARTBEAT, SoundSource.HOSTILE, 1.0f, 1.0f);
                if (pd.phase >= 4 && k % 3 == 0) Fx.shake(q, 4);
            });
            t += Math.max(13, 26 - i * 2);
        }
        if (pd.phase >= 3) later(t + 15, p, q -> Fx.chat(q, Lines.pick(Lines.HEART), Fx.Tone.WATCH));
    }

    private static void farLightning(ServerPlayer p, ServerLevel level) {
        double a = R.nextDouble() * Math.PI * 2;
        double d = 45 + R.nextDouble() * 25;
        double x = p.getX() + Math.cos(a) * d;
        double z = p.getZ() + Math.sin(a) * d;
        double y = groundY(level, (int) Math.floor(x), (int) Math.floor(z), p.getBlockY());
        Fx.lightning(level, x, Double.isNaN(y) ? p.getY() : y, z);
    }

    /** Una figura lontana ti fissa: sparisce se la guardi (con la pioggia, tra i lampi). */
    private static void evFigure(ServerPlayer p, ServerLevel level) {
        ShadowEntity s = spawnShadow(p, level, ShadowEntity.Mode.OBSERVE, 24, 38, lookAngle(p), true, false);
        if (s == null) return;
        s.expireTick = now + 20L * 40;
        if (level.isRaining()) Fx.lightning(level, s.getX(), s.getY(), s.getZ());
    }

    /** Colpi sordi da direzioni diverse, come un'eco nella caverna. */
    private static void evEcho(ServerPlayer p) {
        int n = 3 + R.nextInt(3);
        int t = 20;
        for (int i = 0; i < n; i++) {
            t += 12 + R.nextInt(30);
            later(t, p, q -> {
                double a = R.nextDouble() * Math.PI * 2;
                double d = 6 + R.nextDouble() * 10;
                Fx.soundAt(q, ModSounds.KNOCK, SoundSource.HOSTILE, q.getX() + Math.cos(a) * d, q.getY(), q.getZ() + Math.sin(a) * d, 0.9f, 0.9f + R.nextFloat() * 0.3f);
            });
        }
    }

    /** Finto "L'Ombra è entrato nel gioco", poi il buio si chiude e "esce". */
    private static void evFakeJoin(ServerPlayer p) {
        UUID id = p.getUUID();
        Fx.joinMsg(p);
        later(70, id, q -> Fx.chat(q, Lines.pick(Lines.JOIN_FAKE), Fx.Tone.FRIEND));
        later(190, id, q -> {
            Fx.darkness(q, 160);
            Fx.soundSelf(q, ModSounds.DRONE, SoundSource.HOSTILE, 0.8f, 1.0f);
            Fx.chat(q, "Ops. Si è spenta la luce.", Fx.Tone.WATCH);
        });
        later(340, id, q -> Fx.leftMsg(q));
    }

    /** Pedinamento: avanza solo quando non la guardi. */
    private static void evStalk(ServerPlayer p, ServerLevel level) {
        ShadowEntity s = spawnShadow(p, level, ShadowEntity.Mode.STALK, 26, 34, Double.NaN, false, false);
        if (s == null) return;
        s.expireTick = now + 20L * 75;
        Fx.soundSelf(p, ModSounds.WHISPER, SoundSource.HOSTILE, 0.7f, 0.9f);
    }

    /** Il mondo ammutolisce per un po'. */
    private static void evIsolation(ServerPlayer p) {
        UUID id = p.getUUID();
        for (int i = 0; i < 45; i++) later(i * 8, id, Fx::silence);
        later(30, id, q -> Fx.darkness(q, 200));
        later(50, id, q -> Fx.soundSelf(q, ModSounds.DRONE, SoundSource.HOSTILE, 0.9f, 1.0f));
        later(110, id, q -> Fx.chat(q, Lines.pick(Lines.ALONE), Fx.Tone.WHISPER));
        later(340, id, q -> Fx.chat(q, "...o forse no.", Fx.Tone.WATCH));
    }

    /** Imboscata: L'Ombra compare dietro di te e ti dà la caccia. */
    private static void evAmbush(ServerPlayer p, ServerLevel level) {
        ShadowEntity s = spawnShadow(p, level, ShadowEntity.Mode.HUNT, 13, 18, lookAngle(p) + Math.PI, false, false);
        if (s == null) return;
        s.expireTick = now + 20L * 45;
        Fx.soundAt(p, ModSounds.APPEAR, SoundSource.HOSTILE, s.getX(), s.getY(), s.getZ(), 1.0f, 1.0f);
        Fx.chat(p, "Corri.", Fx.Tone.ANGRY);
    }

    /** Il jumpscare: battito, poi urlo, schermo che trema, titolo disturbato, buio. */
    private static void evJumpscare(ServerPlayer p, ServerLevel level, Rt rt) {
        if (!Data.cfg.jumpscare) return;
        rt.lastJump = now;
        Fx.soundSelf(p, ModSounds.HEARTBEAT_FAST, SoundSource.HOSTILE, 1.0f, 1.0f);
        later(36, p, q -> {
            if (q.level() instanceof ServerLevel lv) {
                ShadowEntity s = spawnShadow(q, lv, ShadowEntity.Mode.JUMPSCARE, 2.0, 2.8, lookAngle(q), false, false);
                if (s != null) s.expireTick = now + 30;
            }
            Fx.soundSelf(q, ModSounds.SCREAM, SoundSource.HOSTILE, 1.0f, 1.0f);
            Fx.soundSelf(q, ModSounds.STATIC, SoundSource.HOSTILE, 0.8f, 1.0f);
            Fx.glitchTitle(q, Lines.pick(Lines.JUMP_TITLES));
            Fx.shake(q, 30);
            Fx.blind(q, 14);
            Fx.darkness(q, 120);
            later(60, q, r -> Fx.chat(r, Lines.pick(Lines.AFTER_JUMP), Fx.Tone.ANGRY));
        });
    }

    // ------------------------------------------------------------------ sonno, spawner, blocchi

    private static void onSleepStart(ServerPlayer p, Data.PlayerData pd) {
        pd.remember("hai dormito mentre ti guardavo");
        later(60, p, q -> Fx.chat(q, Lines.pick(Lines.SLEEP_START), pd.phase >= 3 ? Fx.Tone.WATCH : Fx.Tone.FRIEND));
    }

    private static void onSleepEnd(ServerPlayer p, Data.PlayerData pd) {
        later(30, p, q -> {
            Fx.chat(q, Lines.pick(Lines.SLEEP_END), pd.phase >= 3 ? Fx.Tone.WATCH : Fx.Tone.FRIEND);
            if (pd.phase >= 2 && !pd.defeated && q.level() instanceof ServerLevel lv && !bossActive(rt(q.getUUID()))) {
                ShadowEntity s = spawnShadow(q, lv, ShadowEntity.Mode.OBSERVE, 5, 8, Double.NaN, true, false);
                if (s != null) s.expireTick = now + 20L * 5;
            }
        });
    }

    private static void checkSpawner(ServerPlayer p, ServerLevel level, Data.PlayerData pd, Rt rt) {
        BlockPos c = p.blockPosition();
        for (BlockPos bp : BlockPos.betweenClosed(c.offset(-6, -4, -6), c.offset(6, 4, 6))) {
            if (level.getBlockState(bp).is(Blocks.SPAWNER)) {
                if (rt.seenSpawners.add(bp.asLong())) {
                    pd.remember("hai trovato una gabbia di mostri a Y=" + bp.getY());
                    later(25, p, q -> {
                        Fx.chat(q, Lines.pick(Lines.SPAWNER_NEAR), Fx.Tone.WHISPER);
                        Fx.soundSelf(q, ModSounds.KNOCK, SoundSource.HOSTILE, 0.9f, 0.8f);
                    });
                }
                return;
            }
        }
    }

    private static void afterBreak(Level level, Player player, BlockPos pos, BlockState state, BlockEntity be) {
        if (!Data.cfg.enabled || !(player instanceof ServerPlayer p)) return;
        Data.PlayerData pd = Data.of(p.getUUID());
        if (state.is(Blocks.SPAWNER)) {
            pd.remember("hai rotto una gabbia di mostri a Y=" + pos.getY());
            later(30, p, q -> {
                Fx.chat(q, Lines.pick(Lines.SPAWNER_BREAK), pd.phase >= 3 ? Fx.Tone.ANGRY : Fx.Tone.WATCH);
                Fx.soundSelf(q, ModSounds.STATIC, SoundSource.HOSTILE, 0.7f, 1.0f);
                Fx.shake(q, 6);
            });
        } else if (state.is(DIAMOND_ORES)) {
            pd.remember("hai scavato i diamanti a Y=" + pos.getY());
        }
    }

    // ------------------------------------------------------------------ spawn dell'Ombra

    static double lookAngle(ServerPlayer p) {
        Vec3 v = p.getLookAngle();
        return Math.atan2(v.z, v.x);
    }

    /** Y del primo suolo libero (2 blocchi d'aria sopra) vicino a yRef, oppure NaN. */
    static double groundY(ServerLevel level, int x, int z, int yRef) {
        for (int dy = 6; dy >= -10; dy--) {
            BlockPos pos = new BlockPos(x, yRef + dy, z);
            if (!level.hasChunkAt(pos)) return Double.NaN;
            BlockPos below = pos.below();
            if (level.getBlockState(pos).isAir()
                    && level.getBlockState(pos.above()).isAir()
                    && level.getBlockState(pos.above(2)).isAir()
                    && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                return pos.getY();
            }
        }
        return Double.NaN;
    }

    private static Vec3 findSpot(ServerLevel level, ServerPlayer p, double minD, double maxD, double bias) {
        for (int i = 0; i < 30; i++) {
            double ang = Double.isNaN(bias) ? R.nextDouble() * Math.PI * 2 : bias + (R.nextDouble() - 0.5) * 1.3;
            double dist = minD + R.nextDouble() * (maxD - minD);
            int x = (int) Math.floor(p.getX() + Math.cos(ang) * dist);
            int z = (int) Math.floor(p.getZ() + Math.sin(ang) * dist);
            double y = groundY(level, x, z, p.getBlockY());
            if (!Double.isNaN(y)) return new Vec3(x + 0.5, y, z + 0.5);
        }
        return null;
    }

    static ShadowEntity spawnShadow(ServerPlayer p, ServerLevel level, ShadowEntity.Mode mode,
                                    double minD, double maxD, double bias, boolean needSight, boolean echo) {
        if (!Data.cfg.shadowBody || level.getDifficulty() == Difficulty.PEACEFUL) return null;
        Data.PlayerData pd = Data.of(p.getUUID());
        for (int attempt = 0; attempt < 10; attempt++) {
            Vec3 pos = findSpot(level, p, minD, maxD, bias);
            if (pos == null) continue;
            ShadowEntity s = new ShadowEntity(ModEntities.SHADOW, level);
            s.setPos(pos.x, pos.y, pos.z);
            s.owner = p.getUUID();
            s.mode = mode;
            s.echo = echo;
            face(s, p);
            if (needSight && !p.hasLineOfSight(s)) continue;
            applyStats(s, pd, echo);
            s.expireTick = now + 20L * 60;
            level.addFreshEntity(s);
            Rt rt = rt(p.getUUID());
            if (echo) rt.echoes.add(s);
            else rt.boss = s;
            return s;
        }
        return null;
    }

    private static void setAttr(ShadowEntity s, Holder<Attribute> attr, double value) {
        AttributeInstance inst = s.getAttribute(attr);
        if (inst != null) inst.setBaseValue(value);
    }

    private static void applyStats(ShadowEntity s, Data.PlayerData pd, boolean echo) {
        double hp;
        double dmg;
        double spd;
        if (echo) {
            hp = 20;
            dmg = 3 + pd.phase * 0.4;
            spd = 0.30;
        } else {
            hp = (110 + 22.0 * pd.phase) * (pd.nightmare ? 1.5 : 1.0);
            dmg = 5 + pd.phase * 0.8 + (pd.nightmare ? 2 : 0);
            spd = 0.29 + 0.01 * pd.phase;
        }
        setAttr(s, Attributes.MAX_HEALTH, hp);
        setAttr(s, Attributes.ATTACK_DAMAGE, dmg);
        setAttr(s, Attributes.MOVEMENT_SPEED, spd);
        double ratio = echo ? 1.0 : Math.min(1.0, pd.bossHp + 0.10); // le ferite restano tra un'ondata e l'altra
        s.setHealth((float) (hp * ratio));
    }

    static void face(Entity e, Entity target) {
        double dx = target.getX() - e.getX();
        double dz = target.getZ() - e.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        e.setYRot(yaw);
        if (e instanceof LivingEntity le) {
            le.setYHeadRot(yaw);
            le.setYBodyRot(yaw);
        }
    }

    /** Il giocatore la sta guardando davvero (nel mirino e senza ostacoli)? */
    static boolean looked(ServerPlayer p, Entity e) {
        Vec3 to = e.getEyePosition().subtract(p.getEyePosition());
        double d = to.length();
        if (d < 0.001 || d > 90) return false;
        double dot = p.getLookAngle().dot(to.scale(1.0 / d));
        return dot > 0.975 && p.hasLineOfSight(e);
    }

    private static boolean safeLight(ServerPlayer p, ServerLevel level) {
        BlockPos pos = p.blockPosition();
        return level.dimension() == Level.OVERWORLD && level.canSeeSky(pos) && !level.isRaining()
                && level.getMaxLocalRawBrightness(pos) >= 13;
    }

    static void retire(ShadowEntity s, ServerLevel level, ServerPlayer p, boolean effects) {
        if (s.owner != null) {
            Rt r = rt(s.owner);
            if (s.echo) {
                r.echoes.remove(s);
            } else {
                Data.of(s.owner).bossHp = Math.max(0.05, Math.min(1.0, s.getHealth() / s.getMaxHealth()));
                if (r.boss == s) r.boss = null;
            }
        }
        if (effects) {
            Fx.particles(level, ParticleTypes.LARGE_SMOKE, s.getX(), s.getY() + 1.0, s.getZ(), 25, 0.3, 0.8, 0.3, 0.02);
            if (p != null) Fx.soundAt(p, ModSounds.VANISH, SoundSource.HOSTILE, s.getX(), s.getY(), s.getZ(), 0.8f, 1.0f);
        }
        s.discard();
    }

    static void clearShadows(UUID id) {
        Rt r = RT.get(id);
        if (r == null) return;
        if (r.boss != null) r.boss.discard();
        for (ShadowEntity e : r.echoes) e.discard();
        r.boss = null;
        r.echoes.clear();
    }

    // ------------------------------------------------------------------ comportamento dell'Ombra

    /** Chiamato a ogni tick da ShadowEntity.tick(). */
    static void tickShadow(ShadowEntity s, ServerLevel level) {
        if (s.owner == null || server == null) {
            s.discard(); // orfana (per esempio dopo un riavvio del mondo)
            return;
        }
        ServerPlayer p = server.getPlayerList().getPlayer(s.owner);
        if (p == null || !p.isAlive() || p.level() != level) {
            s.discard();
            return;
        }
        if (now >= s.expireTick) {
            retire(s, level, p, true);
            return;
        }
        Rt rt = rt(s.owner);
        Data.PlayerData pd = Data.of(s.owner);
        switch (s.mode) {
            case OBSERVE -> tickObserve(s, level, p);
            case STALK -> tickStalk(s, level, p, pd, rt);
            case HUNT -> tickHunt(s, level, p, rt);
            case JUMPSCARE -> {
                s.setNoAi(true);
                face(s, p);
            }
        }
    }

    /** Ferma a distanza. Se la guardi: inclina la testa, poi sparisce o si teletrasporta lontano. */
    private static void tickObserve(ShadowEntity s, ServerLevel level, ServerPlayer p) {
        s.setNoAi(true);
        face(s, p);
        if (looked(p, s)) s.stare++;
        else s.stare = Math.max(0, s.stare - 1);
        if (s.stare == 10 && R.nextInt(100) < 40) s.setXRot(28f);
        if (s.stare >= 30 || (s.stare >= 12 && R.nextInt(100) < 3)) {
            if (R.nextInt(100) < 30) {
                Vec3 far = findSpot(level, p, 40, 55, Double.NaN);
                if (far != null) {
                    Fx.particles(level, ParticleTypes.SQUID_INK, s.getX(), s.getY() + 1.0, s.getZ(), 20, 0.3, 0.8, 0.3, 0.02);
                    s.setPos(far.x, far.y, far.z);
                    s.stare = 0;
                    s.setXRot(0f);
                    return;
                }
            }
            retire(s, level, p, true);
        }
    }

    /** Pedinamento: avanza solo quando non la guardi, si immobilizza se la fissi. */
    private static void tickStalk(ShadowEntity s, ServerLevel level, ServerPlayer p, Data.PlayerData pd, Rt rt) {
        s.setNoAi(true);
        face(s, p);
        if (looked(p, s)) {
            s.stare++;
            if (s.stare == 12 && R.nextInt(100) < 40) s.setXRot(28f);
            if (s.stare > 110) retire(s, level, p, true); // uno sguardo lungo la scaccia
            return;
        }
        s.stare = Math.max(0, s.stare - 2);
        s.setXRot(0f);
        if (now % 6 != 0) return;
        double dist = s.distanceTo(p);
        if (dist <= 5.0) {
            stalkerPounce(s, level, p, pd, rt);
            return;
        }
        Vec3 dir = new Vec3(p.getX() - s.getX(), 0, p.getZ() - s.getZ()).normalize();
        double step = 1.0 + R.nextDouble() * 1.2;
        double nx = s.getX() + dir.x * step;
        double nz = s.getZ() + dir.z * step;
        double ny = groundY(level, (int) Math.floor(nx), (int) Math.floor(nz), (int) Math.floor(s.getY()));
        if (!Double.isNaN(ny)) {
            s.setPos(nx, ny, nz);
            if (R.nextInt(3) == 0) Fx.soundAt(p, ModSounds.FOOTSTEP, SoundSource.HOSTILE, nx, ny, nz, 0.6f, 0.8f);
        }
    }

    private static void stalkerPounce(ShadowEntity s, ServerLevel level, ServerPlayer p, Data.PlayerData pd, Rt rt) {
        if (jumpOk(rt) && R.nextInt(100) < 50) {
            retire(s, level, p, false);
            evJumpscare(p, level, rt);
        } else if (Data.cfg.hostile && pd.phase >= 3) {
            s.mode = ShadowEntity.Mode.HUNT;
            s.expireTick = now + 20L * 25;
            Fx.chat(p, "TI HO PRESO.", Fx.Tone.ANGRY);
        } else {
            Fx.chat(p, Lines.pick(Lines.WATCH), Fx.Tone.WHISPER);
            retire(s, level, p, true);
        }
    }

    /** Caccia: inseguimento, teletrasporto tattico, attacco forte con preavviso e finestra di vulnerabilità. */
    private static void tickHunt(ShadowEntity s, ServerLevel level, ServerPlayer p, Rt rt) {
        if (!s.echo) {
            if (safeLight(p, level)) {
                if (++rt.safeTicks > 100) {
                    rt.safeTicks = 0;
                    Fx.chat(p, Lines.pick(Lines.RETREAT), Fx.Tone.WATCH);
                    retire(s, level, p, true);
                    return;
                }
            } else if (rt.safeTicks > 0) {
                rt.safeTicks--;
            }
        }
        if (s.fakeDead) {
            tickFakeDead(s, level, p);
            return;
        }
        if (!Data.cfg.hostile) {
            s.setNoAi(true);
            face(s, p);
            return;
        }
        if (!s.echo && !s.fakeDeadUsed && s.getHealth() < s.getMaxHealth() * 0.12F) {
            startFakeDead(s, p);
            return;
        }

        double dist = s.distanceTo(p);

        if (s.lunge > 0) {            // affondo: l'IA è accesa
            s.lunge--;
            s.setNoAi(false);
            if (s.lunge == 0) s.stun = 50;
            return;
        }
        if (s.stun > 0) {             // dopo l'affondo resta scoperta: è il momento di colpire
            s.stun--;
            s.setNoAi(true);
            s.getNavigation().stop();
            if (s.stun % 8 == 0) Fx.particles(level, ParticleTypes.SOUL, s.getX(), s.getY() + 1.0, s.getZ(), 4, 0.3, 0.6, 0.3, 0.01);
            if (s.stun == 0) s.setNoAi(false);
            return;
        }
        if (s.telegraph > 0) {        // preavviso: ferma, contorno luminoso, rumore profondo
            s.telegraph--;
            s.setNoAi(true);
            face(s, p);
            if (s.telegraph % 4 == 0) Fx.particles(level, ParticleTypes.LARGE_SMOKE, s.getX(), s.getY() + 1.0, s.getZ(), 6, 0.3, 0.7, 0.3, 0.02);
            if (s.telegraph == 0) {
                s.setGlowingTag(false);
                s.setNoAi(false);
                Vec3 dir = p.position().subtract(s.position()).normalize();
                s.setDeltaMovement(dir.x * 1.3, 0.3, dir.z * 1.3);
                s.lunge = 14;
            }
            return;
        }

        s.setNoAi(false);
        if (s.getTarget() != p) s.setTarget(p);
        if (s.blinkCooldown > 0) s.blinkCooldown--;
        if (s.echo) return;

        if (s.blinkCooldown == 0 && (dist > 22 || (dist > 7 && R.nextInt(320) == 0))) {
            blink(s, level, p);
            return;
        }
        if (dist < 12 && dist > 3 && now >= s.nextBig && R.nextInt(90) == 0) {
            s.telegraph = 28;
            s.nextBig = now + 20L * 9;
            s.setGlowingTag(true);
            s.getNavigation().stop();
            Fx.soundAt(p, ModSounds.CHARGE, SoundSource.HOSTILE, s.getX(), s.getY(), s.getZ(), 1.0f, 1.0f);
        }
    }

    /** Teletrasporto tattico: sparisce e riappare dietro di te (non di continuo). */
    private static void blink(ShadowEntity s, ServerLevel level, ServerPlayer p) {
        Vec3 pos = findSpot(level, p, 6, 9, lookAngle(p) + Math.PI);
        if (pos == null) {
            s.blinkCooldown = 40;
            return;
        }
        Fx.particles(level, ParticleTypes.LARGE_SMOKE, s.getX(), s.getY() + 1.0, s.getZ(), 20, 0.3, 0.8, 0.3, 0.02);
        s.getNavigation().stop();
        s.setPos(pos.x, pos.y, pos.z);
        s.blinkCooldown = 120;
        Fx.soundAt(p, ModSounds.APPEAR, SoundSource.HOSTILE, pos.x, pos.y, pos.z, 0.7f, 1.2f);
    }

    /** Falsa sconfitta: a salute critica finge di essere morta e prova un'imboscata finale. */
    private static void startFakeDead(ShadowEntity s, ServerPlayer p) {
        s.fakeDead = true;
        s.fakeDeadUsed = true;
        s.fakeDeadUntil = now + 20L * 7;
        s.setTarget(null);
        s.setNoAi(true);
        s.getNavigation().stop();
        s.setGlowingTag(false);
        Fx.chat(p, "...", Fx.Tone.WHISPER);
    }

    private static void tickFakeDead(ShadowEntity s, ServerLevel level, ServerPlayer p) {
        s.setNoAi(true);
        if (now < s.fakeDeadUntil && s.distanceTo(p) > 2.8) return;
        s.fakeDead = false;
        s.setNoAi(false);
        s.setHealth(s.getMaxHealth() * 0.3F);
        Vec3 look = p.getLookAngle();
        Vec3 behind = p.position().subtract(look.x * 2.0, 0, look.z * 2.0);
        double y = groundY(level, (int) Math.floor(behind.x), (int) Math.floor(behind.z), p.getBlockY());
        if (!Double.isNaN(y)) s.setPos(behind.x, y, behind.z);
        face(s, p);
        s.setTarget(p);
        s.lunge = 12;
        Fx.chat(p, "TI È SEMBRATO FINITA?", Fx.Tone.ANGRY);
        Fx.soundSelf(p, ModSounds.CHARGE, SoundSource.HOSTILE, 1.0f, 1.0f);
        Fx.shake(p, 10);
    }

    // ------------------------------------------------------------------ regole di combattimento

    /** Spada di netherite, oppure qualsiasi arma con attacco superiore alla soglia (8). */
    static boolean isLethal(ServerPlayer p) {
        if (p.getMainHandItem().is(Items.NETHERITE_SWORD)) return true;
        return p.getAttributeValue(Attributes.ATTACK_DAMAGE) > Data.cfg.weaponThreshold;
    }

    private static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof ShadowEntity s)) return true;
        if (s.echo) return true;                                  // gli Echi muoiono con qualsiasi arma
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return true; // /kill e vuoto
        if (s.fakeDead) return false;
        if (source.getEntity() instanceof ServerPlayer p && source.getDirectEntity() == p) {
            if (isLethal(p)) {
                onGoodHit(s, p);
                return true;
            }
            onBadHit(s, p);
            return false;
        }
        return false;                                             // immune a tutto il resto
    }

    private static void onBadHit(ShadowEntity s, ServerPlayer p) {
        Data.PlayerData pd = Data.of(p.getUUID());
        Rt rt = rt(p.getUUID());
        pd.hits++;
        Fx.soundAt(p, ModSounds.HURT, SoundSource.HOSTILE, s.getX(), s.getY(), s.getZ(), 0.9f, 1.0f);
        if (s.level() instanceof ServerLevel lv) {
            Fx.particles(lv, ParticleTypes.SMOKE, s.getX(), s.getY() + 1.2, s.getZ(), 10, 0.25, 0.5, 0.25, 0.01);
        }
        if (now - rt.lastHit > 40) {
            rt.lastHit = now;
            Fx.chat(p, Lines.pick(pd.hits % 7 == 0 ? Lines.HINT : Lines.INEFFECTIVE), Fx.Tone.ANGRY);
        }
        if (s.mode == ShadowEntity.Mode.OBSERVE && pd.phase >= 3 && Data.cfg.hostile) {
            s.mode = ShadowEntity.Mode.HUNT;      // l'hai fatta arrabbiare
            s.expireTick = now + 20L * 40;
        }
    }

    private static void onGoodHit(ShadowEntity s, ServerPlayer p) {
        Rt rt = rt(p.getUUID());
        Fx.soundAt(p, ModSounds.HIT_OK, SoundSource.HOSTILE, s.getX(), s.getY(), s.getZ(), 0.9f, 1.0f);
        if (s.level() instanceof ServerLevel lv) {
            Fx.particles(lv, ParticleTypes.SOUL, s.getX(), s.getY() + 1.2, s.getZ(), 8, 0.25, 0.5, 0.25, 0.02);
        }
        if (now - rt.lastHit > 100) {
            rt.lastHit = now;
            Fx.chat(p, Lines.pick(Lines.WEAPON_OK), Fx.Tone.ANGRY);
        }
    }

    // ------------------------------------------------------------------ morti

    private static void afterDeath(LivingEntity entity, DamageSource source) {
        if (!Data.cfg.enabled) return;
        if (entity instanceof ServerPlayer p) {
            onPlayerDeath(p, source);
        } else if (entity instanceof ShadowEntity s) {
            onShadowDeath(s, source);
        } else if (entity.getType() == EntityTypes.ENDER_DRAGON) {
            onDragonDeath(entity);
        }
    }

    private static void onPlayerDeath(ServerPlayer p, DamageSource src) {
        UUID id = p.getUUID();
        Data.PlayerData pd = Data.of(id);
        Rt rt = rt(id);
        pd.deaths++;
        String[] pool;
        String mem;
        if (src.getEntity() instanceof ShadowEntity) {
            pool = Lines.DEATH_SHADOW;
            mem = "ti ho fermato io";
        } else if (src.is(DamageTypeTags.IS_FALL)) {
            pool = Lines.DEATH_FALL;
            mem = "sei morto cadendo";
        } else if (src.is(DamageTypeTags.IS_FIRE)) {
            pool = Lines.DEATH_FIRE;
            mem = "sei morto tra le fiamme";
        } else if (src.is(DamageTypeTags.IS_DROWNING)) {
            pool = Lines.DEATH_DROWN;
            mem = "sei annegato";
        } else if (src.is(DamageTypeTags.IS_EXPLOSION)) {
            pool = Lines.DEATH_BOOM;
            mem = "sei saltato in aria";
        } else if (src.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            pool = Lines.DEATH_VOID;
            mem = "sei caduto nel vuoto";
        } else if (src.getEntity() instanceof Mob) {
            pool = Lines.DEATH_MOB;
            mem = "un mostro ti ha ucciso";
        } else {
            pool = Lines.DEATH_OTHER;
            mem = "sei morto per la " + pd.deaths + "a volta";
        }
        pd.remember(mem);
        clearShadows(id);
        rt.tension = Math.max(0, rt.tension - 15);
        rt.nextEvent = now + 20L * 150;
        rt.waveUntil = 0;
        String line = Lines.pick(pool).replace("{n}", String.valueOf(pd.deaths));
        later(100, id, q -> Fx.chat(q, line, pd.phase >= 5 ? Fx.Tone.ANGRY : Fx.Tone.WATCH));
    }

    /** Morte definitiva dell'Ombra: scena finale (e finale segreto se hai letto tutti gli indizi). */
    private static void onShadowDeath(ShadowEntity s, DamageSource src) {
        if (s.owner == null) return;
        UUID id = s.owner;
        Rt rt = rt(id);
        if (s.echo) {
            rt.echoes.remove(s);
            return;
        }
        rt.boss = null;
        if (!(src.getEntity() instanceof ServerPlayer)) return; // caduta nel vuoto o /kill: non è una vittoria

        Data.PlayerData pd = Data.of(id);
        pd.defeated = true;
        pd.bossHp = 1.0;
        boolean secret = pd.clues.size() >= 8;
        for (ShadowEntity e : new ArrayList<>(rt.echoes)) e.discard();
        rt.echoes.clear();
        if (s.level() instanceof ServerLevel lv) {
            Fx.particles(lv, ParticleTypes.SOUL, s.getX(), s.getY() + 1.0, s.getZ(), 60, 0.5, 1.0, 0.5, 0.05);
            Fx.lightning(lv, s.getX(), s.getY(), s.getZ());
        }
        later(10, id, q -> {
            Fx.soundSelf(q, ModSounds.TOLL, SoundSource.HOSTILE, 1.0f, 1.0f);
            Fx.title(q,
                    Component.literal("L'OMBRA È CADUTA").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD),
                    Component.literal("Il silenzio, finalmente").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC),
                    10, 100, 40);
        });
        int t = 70;
        for (String line : Lines.FINAL) {
            later(t, id, q -> Fx.chat(q, line, Fx.Tone.CORRUPT));
            t += 70;
        }
        later(t, id, q -> Fx.narrator(q, Lines.FINAL_END));
        t += 90;
        if (secret) {
            for (String line : Lines.SECRET) {
                later(t, id, q -> Fx.chat(q, line, Fx.Tone.WHISPER));
                t += 80;
            }
            later(t, id, q -> Fx.narrator(q, Lines.SECRET_END));
            t += 90;
        }
        later(t + 60, id, q -> Fx.narrator(q, Lines.NIGHTMARE_HINT));
    }

    private static void onDragonDeath(LivingEntity dragon) {
        if (!(dragon.level() instanceof ServerLevel lv)) return;
        for (ServerPlayer p : lv.players()) {
            Data.PlayerData pd = Data.of(p.getUUID());
            if (pd.has("drago")) continue;
            pd.flags.add("drago");
            pd.remember("hai ucciso il drago");
            later(300, p, q -> {
                if (q.level() instanceof ServerLevel l2) raise(q, l2, pd, rt(q.getUUID()), 8);
            });
        }
    }

    // ------------------------------------------------------------------ per i comandi

    static String status(ServerPlayer p) {
        Data.PlayerData pd = Data.of(p.getUUID());
        Rt rt = rt(p.getUUID());
        return "Fase " + pd.phase + "/8 (" + Lines.PHASE_NAMES[Math.max(1, Math.min(8, pd.phase))] + ")"
                + " | tensione " + (int) rt.tension + "/100"
                + " | tempo " + (pd.playTicks / 1200) + " min"
                + " | morti " + pd.deaths
                + " | indizi " + pd.clues.size() + "/8"
                + (pd.defeated ? " | L'Ombra è sconfitta" : "")
                + (pd.nightmare ? " | INCUBO" : "");
    }

    static void setPhase(ServerPlayer p, int phase) {
        Data.PlayerData pd = Data.of(p.getUUID());
        pd.phase = Math.max(1, Math.min(8, phase));
        pd.nextTimeWaveAt = 0;
        rt(p.getUUID()).nextEvent = now + 20L * 20;
    }

    static boolean forceWave(ServerPlayer p) {
        if (!(p.level() instanceof ServerLevel level)) return false;
        Data.PlayerData pd = Data.of(p.getUUID());
        Rt rt = rt(p.getUUID());
        rt.waveUntil = 0;
        startWave(p, level, pd, rt, pd.phase >= 2 ? pd.phase : 0);
        return true;
    }

    static boolean spawnHunt(ServerPlayer p) {
        if (!(p.level() instanceof ServerLevel level)) return false;
        clearShadows(p.getUUID());
        ShadowEntity s = spawnShadow(p, level, ShadowEntity.Mode.HUNT, 12, 16, lookAngle(p) + Math.PI, false, false);
        if (s == null) return false;
        s.expireTick = now + 20L * 120;
        return true;
    }

    static List<String> clues(ServerPlayer p) {
        Data.PlayerData pd = Data.of(p.getUUID());
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            out.add(pd.clues.contains(i) ? "[" + Lines.ROMAN[i] + "] " + Lines.CLUES[i] : "[" + Lines.ROMAN[i] + "] ???");
        }
        return out;
    }

    static void reset(ServerPlayer p) {
        clearShadows(p.getUUID());
        Data.reset(p.getUUID());
        Data.of(p.getUUID()).met = true;
        Rt r = rt(p.getUUID());
        r.tension = 5;
        r.waveUntil = 0;
        r.nextEvent = now + 20L * 60;
        r.cooldown.clear();
    }

    /** Modalità incubo: si sblocca dopo aver sconfitto l'Ombra. */
    static boolean nightmare(ServerPlayer p) {
        Data.PlayerData pd = Data.of(p.getUUID());
        if (!pd.defeated && !pd.nightmare) return false;
        clearShadows(p.getUUID());
        pd.nightmare = true;
        pd.defeated = false;
        pd.bossHp = 1.0;
        pd.phase = 3;
        pd.nextTimeWaveAt = 0;
        pd.flags.removeIf(f -> !f.equals("drago"));
        Rt r = rt(p.getUUID());
        r.waveUntil = 0;
        r.nextEvent = now + 20L * 40;
        return true;
    }
}
