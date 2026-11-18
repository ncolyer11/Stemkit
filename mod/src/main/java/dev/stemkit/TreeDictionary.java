package dev.stemkit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * The tree dictionary: every known huge fungus by code name (mod/trees.json, baked into the jar at build time).
 *
 *   /stemkit grow                  all trees, colour-coded by kind and variant (click a name to grow it)
 *   /stemkit grow <name> [pos]     rebuild the tree with vanilla's own code (see the kinds below)
 *   /stemkit grow all [pos]        every regrowable tree in framed rows
 *
 * Kinds (the "kind" field of trees.json):
 *   natural  vanilla's natural feature (TreeFeatures.CRIMSON_FUNGUS / WARPED_FUNGUS, planted=false, so thick stems happen),
 *            fed a WorldgenRandom over Xoroshiro whose state is given in one of three ways:
 *              derived     worldSeed + x + z + featureIndex + step + callsBefore, exactly like chunk decoration
 *                          (setDecorationSeed -> setFeatureSeed -> callsBefore draws): a tree that exists in a real world;
 *              recorded    atPlaceLo/atPlaceHi, the state at place();
 *              model       featureSeed + skip: new XoroshiroRandomSource(featureSeed), then `skip` raw nextLong steps
 *                          (our natural search's definition): a tree the natural code makes from that state, in no real world.
 *   planted  variant + s0: vanilla bone meal (StemkitCommand.growTree, the same code as `grow <variant> <s0>`).
 *
 * Regrow happens in open air on a nylium floor, like the searches. A real tree can differ if terrain blocked parts of it.
 */
public final class TreeDictionary {
	private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

	/** One dictionary entry; fields not used by its kind are null. expected* is -1 when not given. */
	record Tree(String name, String description, String highlight, String kind, String variant,
				Long worldSeed, Integer x, Integer y, Integer z,
				Integer featureIndex, Integer step, Integer callsBefore, Long atLo, Long atHi,
				Long featureSeed, Integer skip, Long s0, int expectedShroom, int expectedWart) {
		boolean planted() {
			return kind.equals("planted");
		}

		boolean hasWorld() {
			return worldSeed != null && x != null && z != null;
		}

		boolean derivable() {
			return hasWorld() && featureIndex != null && step != null && callsBefore != null;
		}

		boolean isModel() {
			return featureSeed != null && skip != null;
		}

		boolean hasVariant() {
			return variant.equals("crimson") || variant.equals("warped") || sibling();
		}

		/** variant "sibling" (planted only): the s0 is grown as warped AND crimson side by side, see StemkitCommand.growSiblings. */
		boolean sibling() {
			return variant.equals("sibling") && kind.equals("planted");
		}

		boolean canRegrow() {
			if (!hasVariant()) return false;
			if (planted()) return s0 != null;
			return derivable() || isModel() || (atLo != null && atHi != null);
		}

		/** Short tag for lists: natural (real world), model (natural code, no world) or planted. */
		String tag() {
			return planted() ? "planted" : isModel() ? "model" : "natural";
		}
	}

	/** Re-read every time commands are (re)built: game start, world load and /reload. */
	static List<Tree> TREES = List.of();

	/** Editable copy: <game dir>/config/stemkit/trees.json (written from the built-in one on first run and whenever the mod is newer than the copy, see ConfigFiles). */
	static java.nio.file.Path configFile() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("stemkit").resolve("trees.json");
	}

	private TreeDictionary() {}

	private static List<Tree> load() {
		List<Tree> out = new ArrayList<>();
		java.nio.file.Path cfg = configFile();
		ConfigFiles.ensure(cfg, "/stemkit/trees.json");   // created / refreshed when the mod is newer than the copy
		try (InputStream in = java.nio.file.Files.exists(cfg) ? java.nio.file.Files.newInputStream(cfg)
				: TreeDictionary.class.getResourceAsStream("/stemkit/trees.json")) {
			if (in == null) return out;
			JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			for (JsonElement e : root.getAsJsonArray("trees")) {
				JsonObject o = e.getAsJsonObject();
				String kind = o.has("kind") ? o.get("kind").getAsString() : "natural";
				if (!kind.equals("natural") && !kind.equals("planted")) {
					LOG.warn("[stemkit] {}: unknown kind '{}' (natural | planted), entry skipped", o.get("name"), kind);
					continue;
				}
				if (RESERVED.contains(o.get("name").getAsString().toLowerCase())) {
					// these are words the grow command already uses (grow all, grow warped <seed>, grow crimson <seed>)
					LOG.warn("[stemkit] {}: a tree cannot be named all, warped or crimson (grow uses those words), entry skipped", o.get("name"));
					continue;
				}
				if (o.get("name").getAsString().matches("[0-9]+")) {
					// grow <number> is a raw seed, so a purely numeric tree name could never be reached by name
					LOG.warn("[stemkit] {}: a tree name cannot be just digits (grow <number> grows that seed), entry skipped", o.get("name"));
					continue;
				}
				out.add(new Tree(o.get("name").getAsString(),
					o.has("description") ? o.get("description").getAsString() : "",
					o.has("highlight") ? o.get("highlight").getAsString() : "",
					kind,
					o.has("variant") ? o.get("variant").getAsString() : "unknown",
					optL(o, "worldSeed"), optI(o, "x"), optI(o, "y"), optI(o, "z"),
					optI(o, "featureIndex"), optI(o, "step"), optI(o, "callsBefore"),
					optL(o, "atPlaceLo"), optL(o, "atPlaceHi"),
					optL(o, "featureSeed"), optI(o, "skip"), optL(o, "s0"),
					o.has("expectedShroomlights") ? o.get("expectedShroomlights").getAsInt() : -1,
					o.has("expectedWart") ? o.get("expectedWart").getAsInt() : -1));
			}
		} catch (Exception ex) {
			LOG.error("[stemkit] bad {} - the tree list is empty until it is fixed (then /reload)", cfg, ex);
		}
		LOG.info("[stemkit] loaded {} trees from {}", out.size(), cfg);
		return out;
	}

	private static Integer optI(JsonObject o, String k) {
		return o.has(k) ? o.get(k).getAsInt() : null;
	}

	private static Long optL(JsonObject o, String k) {
		return o.has(k) ? o.get(k).getAsLong() : null;
	}

	/** Words the grow command uses itself, so no tree can take them as a name. */
	private static final java.util.Set<String> RESERVED = java.util.Set.of("all", "warped", "crimson");

	static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
		TREES = load();
		LiteralArgumentBuilder<CommandSourceStack> regrow = Commands.literal("grow").executes(c -> list(c.getSource()));   // no argument: the list (merged with the seed forms of grow in StemkitCommand)
		regrow.then(Commands.literal("all")
			.executes(c -> regrowAll(c.getSource(), Targeting.defaultPos(c.getSource())))
			.then(Commands.argument("pos", BlockPosArgument.blockPos())
				.executes(c -> regrowAll(c.getSource(), BlockPosArgument.getLoadedBlockPos(c, "pos")))));
		for (Tree t : TREES) {
			regrow.then(Commands.literal(t.name())
				.executes(c -> regrow(c.getSource(), Targeting.defaultPos(c.getSource()), t))
				.then(Commands.argument("pos", BlockPosArgument.blockPos())
					.executes(c -> regrow(c.getSource(), BlockPosArgument.getLoadedBlockPos(c, "pos"), t))));
		}
		root.then(regrow);
	}

	/** Shroomlight orange (close to the block's own colour) for every shroomlight count. */
	private static final int SHROOM_ORANGE = 0xF7A44B;
	/** Section titles: light blue-grey, distinct from the shroomlight orange and the variant colours. */
	private static final int TITLE = 0xC8D7F0;
	/** Variant colours, softened (vanilla RED/AQUA are very bright). */
	private static final int CRIMSON = 0xC96A6A;
	private static final int WARPED = 0x5FB8AE;
	private static final int SIBLING = 0xB48FD6;   // warped + crimson pair
	private static final int UNKNOWN = 0xA8A8A8;
	/** Highlights that are not about shroomlights (wart counts, shapes, patterns). */
	private static final int HIGHLIGHT = 0xA8E6A1;

	/**
	 * Compact, colour-coded list: a colour key, then one short line per tree, grouped by kind and sorted by name.
	 * After the name comes the entry's "highlight" (what makes the tree interesting), in shroomlight orange if it is about
	 * shroomlights, otherwise in a light green. Name colour = variant (crimson red, warped aqua, unknown grey); hover shows the description;
	 * click puts the regrow command in the chat box (trees without RNG data are listed but cannot be regrown).
	 */
	private static int list(CommandSourceStack src) {
		src.sendSuccess(() -> Component.literal(TREES.size() + " trees").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)
			.append(Component.literal("   hover a name for details, click it to grow").withStyle(ChatFormatting.GRAY)), false);
		src.sendSuccess(() -> Component.literal("Key: ").withStyle(ChatFormatting.GRAY)
			.append(Component.literal("■ crimson  ").withStyle(s -> s.withColor(CRIMSON)))
			.append(Component.literal("■ warped  ").withStyle(s -> s.withColor(WARPED)))
			.append(Component.literal("■ sibling pair  ").withStyle(s -> s.withColor(SIBLING)))
			.append(Component.literal("■ variant unknown  ").withStyle(s -> s.withColor(UNKNOWN)))
			.append(Component.literal("shroomlights  ").withStyle(s -> s.withColor(SHROOM_ORANGE)))
			.append(Component.literal("shape / pattern").withStyle(s -> s.withColor(HIGHLIGHT))), false);
		String[][] groups = {
			{"natural", "Natural trees from real worlds"},
			{"model", "Natural trees from simulation (no world location)"},
			{"planted", "Bone-meal trees (player-grown)"}};
		for (String[] g : groups) {
			List<Tree> ts = new ArrayList<>(TREES.stream().filter(t -> t.tag().equals(g[0])).toList());
			if (ts.isEmpty()) continue;
			ts.sort((a, b) -> a.name().compareTo(b.name()));
			src.sendSuccess(() -> Component.literal(g[1]).withStyle(s -> s.withColor(TITLE).withBold(true).withUnderlined(true)), false);
			for (Tree t : ts) {
				int color = t.variant().equals("crimson") ? CRIMSON : t.variant().equals("warped") ? WARPED : t.sibling() ? SIBLING : UNKNOWN;
				String cmd = t.canRegrow() ? "/stemkit grow " + t.name() : "";
				MutableComponent line = Component.literal(" ■ ").withStyle(s -> s.withColor(color))
					.append(Component.literal(t.name()).withStyle(s -> s.withColor(color)
						.withClickEvent(cmd.isEmpty() ? null : new ClickEvent.SuggestCommand(cmd))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal(t.description()
							+ (t.expectedWart() < 0 ? "" : "\nWart blocks: " + t.expectedWart()) + (cmd.isEmpty() ? "" : "\n" + cmd))))));
				if (!t.highlight().isEmpty()) {
					int hl = t.highlight().contains("shroomlight") ? SHROOM_ORANGE : HIGHLIGHT;
					line.append(Component.literal("  " + t.highlight()).withStyle(s -> s.withColor(hl)));
				}
				if (!t.canRegrow()) line.append(Component.literal("  (no RNG data, cannot regrow)").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
				src.sendSuccess(() -> line, false);
			}
		}
		return TREES.size();
	}

	/** Natural trees: the WorldgenRandom exactly as the feature gets it (derived, recorded or model state; see class comment). */
	static WorldgenRandom stateAtPlace(Tree t) {
		if (t.isModel()) {
			WorldgenRandom r = new WorldgenRandom(new XoroshiroRandomSource(t.featureSeed()));   // seed upgraded to 128 bit
			for (int i = 0; i < t.skip(); i++) r.next(1);
			return r;
		}
		if (!t.derivable()) {
			return new WorldgenRandom(new XoroshiroRandomSource(t.atLo(), t.atHi()));   // recorded state only
		}
		WorldgenRandom r = new WorldgenRandom(new XoroshiroRandomSource(0L));
		long decorationSeed = r.setDecorationSeed(t.worldSeed(), t.x() & ~15, t.z() & ~15);
		r.setFeatureSeed(decorationSeed, t.featureIndex(), t.step());
		for (int i = 0; i < t.callsBefore(); i++) {
			r.next(1);   // one underlying nextLong per call, whatever the bound (as nextInt(16) in CountOnEveryLayer)
		}
		return r;
	}

	/** "N label (matches)" / "N label (EXPECTED M)" / just "N label" when the entry has no expected value. */
	private static String cmp(int actual, int expected, String label) {
		return actual + " " + label + (expected < 0 ? "" : actual == expected ? " (matches)" : " (EXPECTED " + expected + ")");
	}

	/** Result of one regrow: ok = the tree was grown; matches = counts equal the expected ones (or none are given). */
	record Outcome(boolean ok, boolean matches, String msg, int shroom) {}

	private static int regrow(CommandSourceStack src, BlockPos pos, Tree t) {
		pos = Targeting.fit(src, pos, t.planted() ? 28 : 30);   // bone-meal trees and sibling pairs need y+28, natural ones y+30
		Outcome o = regrowAt(src.getLevel(), pos, t, GridLayout.facing(src).getClockWise());
		if (!o.ok()) {
			src.sendFailure(Component.literal(o.msg()));
			return 0;
		}
		LOG.info("[stemkit] {}", o.msg());
		src.sendSuccess(() -> Component.literal(o.msg()), true);
		return o.shroom();
	}

	/** Grows tree t at pos (bone-meal trees in a 7x7 column, natural ones in a 9x9 column) without sending chat messages. */
	static Outcome regrowAt(ServerLevel level, BlockPos pos, Tree t) {
		return regrowAt(level, pos, t, Direction.EAST);
	}

	/** `right` = the direction the crimson half of a sibling pair goes (warped goes the other way); unused for other trees. */
	static Outcome regrowAt(ServerLevel level, BlockPos pos, Tree t, Direction right) {
		if (!t.canRegrow()) {
			return new Outcome(false, false, t.name() + " cannot be regrown: trees.json has no RNG data for it yet."
				+ (t.hasWorld() ? " It is in a world with seed " + t.worldSeed() + " at " + t.x() + " " + t.z() + "." : ""), 0);
		}
		boolean warped = t.variant().equals("warped");
		String desc = t.name() + " [" + t.tag() + ", " + t.variant() + "]";

		if (t.sibling()) {
			StemkitCommand.Siblings s = StemkitCommand.growSiblings(level, pos, right, t.s0());
			if (s.failure() != null) return new Outcome(false, false, desc + ": " + s.failure(), 0);
			StemkitCommand.Grown w = s.warped(), c = s.crimson();
			boolean m = s.sameShape() && matches(w.shroom(), t.expectedShroom()) && matches(w.wart(), t.expectedWart());
			return new Outcome(true, m, desc + " regrown (warped left, crimson right): " + s.verdict() + ", "
				+ cmp(w.shroom(), t.expectedShroom(), "shroomlights") + " / " + c.shroom() + ", "
				+ cmp(w.wart(), t.expectedWart(), "wart") + ", " + w.stem() + " stem, crimson vines " + c.vines() + ".", w.shroom());
		}
		if (t.planted()) {
			StemkitCommand.Grown r = StemkitCommand.growTree(level, pos, warped, t.s0());
			if (r.failure() != null) return new Outcome(false, false, desc + ": " + r.failure(), 0);
			boolean m = matches(r.shroom(), t.expectedShroom()) && matches(r.wart(), t.expectedWart());
			return new Outcome(true, m, desc + " regrown: " + cmp(r.shroom(), t.expectedShroom(), "shroomlights") + ", "
				+ cmp(r.wart(), t.expectedWart(), "wart") + ", " + r.stem() + " stem.", r.shroom());
		}

		if (!level.isInsideBuildHeight(pos.below()) || !level.isInsideBuildHeight(pos.above(30))) {
			return new Outcome(false, false, "Not enough room: need build height from y-1 to y+30.", 0);
		}
		String check = "";
		if (t.derivable() && t.atLo() != null && t.atHi() != null) {
			WorldgenRandom mine = stateAtPlace(t);
			WorldgenRandom given = new WorldgenRandom(new XoroshiroRandomSource(t.atLo(), t.atHi()));
			boolean same = true;
			for (int i = 0; i < 8; i++) same &= mine.nextLong() == given.nextLong();
			if (!same) {
				return new Outcome(false, false, "Derived RNG state does not match atPlaceLo/Hi in trees.json - check the entry.", 0);
			}
			check = " RNG state derived from seed+coords matches the recorded state.";
		}
		Block nylium = warped ? Blocks.WARPED_NYLIUM : Blocks.CRIMSON_NYLIUM;
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				for (int dy = 0; dy <= 30; dy++) {
					level.setBlock(pos.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
				}
				level.setBlock(pos.offset(dx, -1, dz), nylium.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		}
		ResourceKey<Feature> key = warped ? TreeFeatures.WARPED_FUNGUS : TreeFeatures.CRIMSON_FUNGUS;
		Holder<Feature> feature = level.registryAccess().lookupOrThrow(Registries.FEATURE).getOrThrow(key);
		boolean placed;
		TreeCapture.SELF.set(true);
		try {
			placed = feature.value().place(level, level.getChunkSource().getGenerator(), stateAtPlace(t), pos);
		} finally {
			TreeCapture.SELF.set(false);
		}
		int[] n = count(level, pos, 4, 30, warped);
		boolean m = placed && matches(n[0], t.expectedShroom()) && matches(n[1], t.expectedWart());
		return new Outcome(true, m, desc + (placed ? " regrown: " : " - feature refused to place: ") + cmp(n[0], t.expectedShroom(), "shroomlights")
			+ ", " + cmp(n[1], t.expectedWart(), "wart") + ", " + n[2] + " stem." + check, n[0]);
	}

	private static boolean matches(int actual, int expected) {
		return expected < 0 || actual == expected;
	}

	/**
	 * /stemkit grow all [pos]: every regrowable tree side by side, shortest first, in rows of 16 running to the player's right
	 * (only the last row shorter), each next row 9 blocks further away. Bone-meal trees first (7x7 cells, blackstone-brick border,
	 * chiseled blackstone corners), then the natural trees behind them (9x9 cells, plain polished blackstone border).
	 * Each cell has a sign: code name and seed (bone-meal RNG state, world seed + coordinates on the back, or feature seed + skip).
	 */
	private static int regrowAll(CommandSourceStack src, BlockPos corner) {
		corner = Targeting.fit(src, corner, 30);
		ServerLevel level = src.getLevel();
		if (!GridLayout.fits(level, corner, 30)) {
			src.sendFailure(Component.literal("Not enough room: need build height from y-1 to y+30."));
			return 0;
		}
		List<Tree> planted = new ArrayList<>(), natural = new ArrayList<>(), siblings = new ArrayList<>();
		for (Tree t : TREES) if (t.canRegrow() && !failsBoneMealRoll(t)) (t.sibling() ? siblings : t.planted() ? planted : natural).add(t);
		planted.sort((a, b) -> a.name().compareTo(b.name()));
		natural.sort((a, b) -> a.name().compareTo(b.name()));
		siblings.sort((a, b) -> a.name().compareTo(b.name()));
		List<String> bad = new ArrayList<>();
		Direction fwd = GridLayout.facing(src);
		GridLayout.Result r1 = GridLayout.grow(level, corner, fwd, 30, ROW, ROW_GAP, true, GridLayout.PLANTED, cells(level, planted, bad, fwd.getClockWise()));
		BlockPos behind = planted.isEmpty() ? corner : r1.behind(ROW_GAP);
		int sibGrown = 0;
		if (!siblings.isEmpty()) {
			// sibling pairs: their own rows of quartz-framed 17x9 pairs (adjacent frames share a line), then the natural grid behind
			Direction right = fwd.getClockWise();
			for (int i = 0; i < siblings.size(); i++) {
				Tree t = siblings.get(i);
				BlockPos c = behind.relative(right, 8 + 16 * (i % SIB_ROW)).relative(fwd, 4 + (9 + ROW_GAP) * (i / SIB_ROW));
				for (int a = -8; a <= 8; a++) for (int b = -4; b <= 4; b++) for (int dy = 0; dy <= 30; dy++)
					level.setBlock(c.relative(right, a).relative(fwd, b).above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
				Outcome o = regrowAt(level, c, t, right);
				if (o.ok()) sibGrown++;
				if (!o.ok() || !o.matches()) bad.add(o.msg());
				LOG.info("[stemkit] {}", o.msg());
				GridLayout.sign(level, c.relative(fwd, -4), signFront(t), signBack(t), true, fwd.getOpposite());
			}
			behind = behind.relative(fwd, (9 + ROW_GAP) * ((siblings.size() + SIB_ROW - 1) / SIB_ROW));
		}
		GridLayout.Result r2 = GridLayout.grow(level, behind, fwd, 30, NATURAL_ROW, ROW_GAP, true, GridLayout.NATURAL, cells(level, natural, bad));
		int grown = r1.grown() + sibGrown + r2.grown();
		src.sendSuccess(() -> Component.literal("Grew " + grown + " trees.").withStyle(ChatFormatting.GREEN), true);
		for (String b : bad) src.sendFailure(Component.literal(b));
		return grown;
	}

	/** A planted tree whose seed fails the 40% bone meal roll grows nothing (like infinity_tree), so grow all leaves it out. */
	private static boolean failsBoneMealRoll(Tree t) {
		if (!t.planted() || t.s0() == null) return false;
		long s1 = (t.s0() * 0x5DEECE66DL + 11L) & ((1L << 48) - 1);
		return (s1 >>> 24) >= 6710887L;
	}

	/** regrow all: trees per row, and empty blocks between rows. */
	private static final int ROW = 16, ROW_GAP = 9;
	/** Sibling pairs per row in regrow all (each pair is 17 blocks wide incl. its quartz frame; 8 pairs = 129 blocks, like a planted row). */
	private static final int SIB_ROW = 8;
	/** Natural trees per row: 13 cells of 9x9 are about as long as 16 cells of 7x7 (131 vs 129 blocks). */
	private static final int NATURAL_ROW = 13;

	/**
	 * Sign text for regrow all (a sign line holds about 15 characters; 4 lines per side).
	 * Bone-meal trees: code name, then the level RNG state you must be at when you use the bone meal (s0).
	 * Real-world natural trees: code name + world seed on the front, the coordinates on the back.
	 * Model natural trees (no world): code name + feature seed and skip.
	 */
	private static List<String> signFront(Tree t) {
		// line 1: code name, line 2: what the number is, lines 3-4: the number, split evenly
		String label, number;
		if (t.planted()) { label = "bonemeal RNG:"; number = String.valueOf(t.s0()); }
		else if (t.isModel()) { label = "feature seed:"; number = String.valueOf(t.featureSeed()); }
		else if (t.worldSeed() != null) { label = "seed:"; number = String.valueOf(t.worldSeed()); }
		else { label = ""; number = ""; }
		List<String> out = new ArrayList<>();
		out.add(t.name().replace('_', ' '));
		out.add(label);
		int cut = (number.length() + 1) / 2;
		out.add(number.substring(0, cut));
		out.add(number.substring(cut));
		return out;
	}

	private static List<String> signBack(Tree t) {
		if (!t.planted() && t.hasWorld() && t.x() != null && t.z() != null) {
			List<String> out = new ArrayList<>();
			out.add(GridLayout.labelLines(t.name()).get(0));
			out.add("x " + t.x());
			if (t.y() != null && t.y() >= 0) out.add("y " + t.y());
			out.add("z " + t.z());
			return out;
		}
		if (t.isModel()) {
			List<String> out = new ArrayList<>(GridLayout.labelLines(t.name()).subList(0, 1));
			out.add("model tree");
			out.add("skip " + t.skip());
			return out;
		}
		return signFront(t);
	}

	private static void split(List<String> out, String s) {
		if (s.length() <= 15) { out.add(s); return; }
		out.add(s.substring(0, 15));
		out.add(s.substring(15));
	}

	private static List<GridLayout.Cell> cells(ServerLevel level, List<Tree> trees, List<String> bad) {
		return cells(level, trees, bad, Direction.EAST);
	}

	/** `right` = the grid's row direction: a sibling pair is laid out along it (cell half-width 7 holds both 7x7 trees). */
	private static List<GridLayout.Cell> cells(ServerLevel level, List<Tree> trees, List<String> bad, Direction right) {
		List<GridLayout.Cell> out = new ArrayList<>();
		for (Tree t : trees) {
			int half = t.sibling() ? 7 : t.planted() ? 3 : 4;
			out.add(new GridLayout.Cell(signFront(t), t.variant().equals("warped"), (lv, pos) -> {
				Outcome o = regrowAt(lv, pos, t, right);
				if (!o.ok() || !o.matches()) bad.add(o.msg());
				LOG.info("[stemkit] {}", o.msg());
				return o.ok();
			}, (lv, pos) -> regrowAt(lv, pos, t, right).ok(), half, signBack(t)));
		}
		return out;
	}

	/** {shroomlights, wart, stem} in the box |dx|,|dz| <= r, 0 <= dy <= h. */
	private static int[] count(ServerLevel level, BlockPos pos, int r, int h, boolean warped) {
		Block wart = warped ? Blocks.WARPED_WART_BLOCK : Blocks.NETHER_WART_BLOCK;
		Block stem = warped ? Blocks.WARPED_STEM : Blocks.CRIMSON_STEM;
		int[] n = new int[3];
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dy = 0; dy <= h; dy++) {
					Block b = level.getBlockState(pos.offset(dx, dy, dz)).getBlock();
					if (b == Blocks.SHROOMLIGHT) n[0]++;
					else if (b == wart) n[1]++;
					else if (b == stem) n[2]++;
				}
			}
		}
		return n;
	}
}
