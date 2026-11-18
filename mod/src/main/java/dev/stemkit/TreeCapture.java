package dev.stemkit;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.serialization.JsonOps;
import dev.stemkit.mixin.LegacyRandomSourceAccessor;
import dev.stemkit.mixin.WorldgenRandomAccessor;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.HugeFungusFeature;

/**
 * /stemkit seed: reports the random state of a huge fungus and saves a trees.json entry for it.
 *
 * The game never stores a tree's seed, so this watches for fungi as they grow (a mixin on HugeFungusFeature.place):
 *  - bone meal (or a dispenser): the world's random is a 48-bit generator. The 40% success roll has already used one step
 *    when the tree starts, so the state before the roll, s0, is that state stepped back once.
 *  - natural generation: the feature gets a Xoroshiro generator. Its 128-bit state at that moment (atPlaceLo/Hi) plus the
 *    world seed and position is enough for `grow` to rebuild the tree.
 * Natural trees are only caught while their chunk is being generated with the mod loaded, and only the newest few thousand
 * are kept in memory.
 */
public final class TreeCapture {
	private static final long MASK48 = (1L << 48) - 1;
	private static final long MULTIPLIER = 0x5DEECE66DL;
	private static final long INCREMENT = 11L;
	private static final long INVERSE = BigInteger.valueOf(MULTIPLIER).modInverse(BigInteger.ONE.shiftLeft(48)).longValue();
	private static final int KEEP = 4000;
	/** Within this many blocks of you counts as "near". */
	private static final double NEAR = 48.0;

	/** Set by the mod while it grows its own trees, so those are not mistaken for natural ones. */
	public static final ThreadLocal<Boolean> SELF = ThreadLocal.withInitial(() -> false);

	private record Seen(boolean planted, boolean warped, ResourceKey<Level> dim, BlockPos pos, long s0, long worldSeed, long lo, long hi) {}

	private static final ConcurrentLinkedDeque<Seen> SEEN = new ConcurrentLinkedDeque<>();
	private static final AtomicInteger SIZE = new AtomicInteger();
	private static volatile Seen lastPlanted;

	private TreeCapture() {}

	/** Called at the start of every huge fungus placement. Must never throw, worldgen threads call it. */
	public static void onPlace(HugeFungusFeature f, WorldGenLevel level, RandomSource random, BlockPos origin) {
		try {
			if (!level.getBlockState(origin.below()).is(f.validBaseState().getBlock())) return;
			boolean warped = f.stemState().is(Blocks.WARPED_STEM);
			ServerLevel sl = level.getLevel();
			Seen seen = null;
			if (f.planted()) {
				if (!(random instanceof LegacyRandomSource) || random instanceof WorldgenRandom || random != sl.getRandom()) return;
				long s1 = ((LegacyRandomSourceAccessor) random).stemkit$seed().get();
				long s0 = ((s1 - INCREMENT) * INVERSE) & MASK48;
				seen = new Seen(true, warped, sl.dimension(), origin.immutable(), s0, 0L, 0L, 0L);
				lastPlanted = seen;
			} else if (random instanceof WorldgenRandom wr && !SELF.get()) {
				RandomSource inner = ((WorldgenRandomAccessor) wr).stemkit$inner();
				if (!(inner instanceof XoroshiroRandomSource xo)) return;
				JsonElement e = XoroshiroRandomSource.CODEC.encodeStart(JsonOps.INSTANCE, xo).result().orElse(null);
				if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 2) return;
				JsonArray a = e.getAsJsonArray();
				seen = new Seen(false, warped, sl.dimension(), origin.immutable(), 0L, level.getSeed(), a.get(0).getAsLong(), a.get(1).getAsLong());
			}
			if (seen == null) return;
			SEEN.addLast(seen);
			if (SIZE.incrementAndGet() > KEEP) {
				SEEN.pollFirst();
				SIZE.decrementAndGet();
			}
		} catch (Throwable ignored) {
			// never break world generation over a log entry
		}
	}

	/** /stemkit seed [last] */
	public static int command(CommandContext<CommandSourceStack> c, boolean last) {
		CommandSourceStack src = c.getSource();
		Seen found = null;
		if (last) {
			found = lastPlanted;
		} else {
			ResourceKey<Level> dim = src.getLevel().dimension();
			double best = NEAR * NEAR;
			for (Seen s : SEEN) {
				if (!s.dim().equals(dim)) continue;
				double d = s.pos().distToCenterSqr(src.getPosition());
				if (d < best) {
					best = d;
					found = s;
				}
			}
		}
		if (found == null) {
			src.sendFailure(Component.literal(last
				? "No bone meal tree seen yet this session. Bone meal a fungus first."
				: "No tree recorded within 48 blocks. Bone meal one, or walk into fresh Nether chunks (natural trees are only caught as they generate). "
					+ "/stemkit seed last shows the latest bone meal tree."));
			return 0;
		}
		final Seen s = found;
		String variant = s.warped() ? "warped" : "crimson";
		String where = s.pos().getX() + " " + s.pos().getY() + " " + s.pos().getZ();
		JsonObject entry = entry(s, variant);
		String name = entry.get("name").getAsString();
		String json = new GsonBuilder().create().toJson(entry);
		if (s.planted()) {
			src.sendSuccess(() -> Component.literal("Bone meal " + variant + " fungus at " + where + ": seed " + s.s0()).withStyle(ChatFormatting.WHITE), false);
			src.sendSuccess(() -> Component.literal("/stemkit grow " + variant + " " + s.s0()).withStyle(ChatFormatting.GRAY), false);
		} else {
			src.sendSuccess(() -> Component.literal("Natural " + variant + " fungus at " + where + ": world seed " + s.worldSeed()
				+ ", state " + s.lo() + " " + s.hi()).withStyle(ChatFormatting.WHITE), false);
		}
		try {
			Path file = save(entry);
			src.sendSuccess(() -> Component.literal("Saved as " + name + " in " + file.getFileName() + ". Click here to copy the entry for trees.json.")
				.withStyle(st -> st.withColor(ChatFormatting.GREEN).withClickEvent(new ClickEvent.CopyToClipboard(json))), false);
		} catch (Exception ex) {
			src.sendFailure(Component.literal("Could not save the entry: " + ex.getMessage()));
		}
		return 1;
	}

	private static String coord(int v) {
		return v < 0 ? "m" + (-v) : String.valueOf(v);
	}

	private static JsonObject entry(Seen s, String variant) {
		JsonObject o = new JsonObject();
		BlockPos p = s.pos();
		String at = p.getX() + " " + p.getY() + " " + p.getZ();
		if (s.planted()) {
			o.addProperty("name", "captured_" + variant + "_" + s.s0());
			o.addProperty("kind", "planted");
			o.addProperty("description", "Captured in game: a bone meal " + variant + " fungus at " + at + ".");
			o.addProperty("highlight", "");
			o.addProperty("variant", variant);
			o.addProperty("s0", s.s0());
		} else {
			o.addProperty("name", "captured_" + variant + "_" + coord(p.getX()) + "_" + coord(p.getY()) + "_" + coord(p.getZ()));
			o.addProperty("kind", "natural");
			o.addProperty("description", "Captured in game: a natural " + variant + " fungus at " + at + ".");
			o.addProperty("highlight", "");
			o.addProperty("variant", variant);
			o.addProperty("worldSeed", s.worldSeed());
			o.addProperty("x", p.getX());
			o.addProperty("y", p.getY());
			o.addProperty("z", p.getZ());
			o.addProperty("atPlaceLo", s.lo());
			o.addProperty("atPlaceHi", s.hi());
		}
		return o;
	}

	/** Adds the entry to config/stemkit/captured_trees.json (same format as trees.json), unless that name is already there. */
	private static synchronized Path save(JsonObject entry) throws Exception {
		Path file = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("stemkit").resolve("captured_trees.json");
		Files.createDirectories(file.getParent());
		JsonObject root = new JsonObject();
		JsonArray trees = new JsonArray();
		if (Files.exists(file)) {
			JsonObject old = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			if (old.has("trees")) trees = old.getAsJsonArray("trees");
		}
		String name = entry.get("name").getAsString();
		for (JsonElement e : trees) {
			if (e.getAsJsonObject().get("name").getAsString().equals(name)) return file;
		}
		trees.add(entry);
		root.add("trees", trees);
		Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
		return file;
	}
}
