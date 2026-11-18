package dev.stemkit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * Glyph trees: bone-meal (planted) trees whose shroomlights draw a character in one hat layer, read from BELOW.
 *
 *   /stemkit char <glyph> [pos]     one glyph: a letter (case-sensitive), a digit or a punctuation name (e.g. question_mark)
 *   /stemkit char ascii [pos]     everything in four separate lines (lower, upper, digits, punctuation), gaps between them
 *   /stemkit char all_alpha | ALL_ALPHA | all_num | all_punct [pos]    every lowercase / uppercase letter, digit, punctuation mark
 *   /stemkit message <text>                    the text as one straight line of trees
 *
 * Every glyph tree is ROTATED as it is pasted so that it reads upright when you stand under it facing the direction you
 * faced when you ran the command and look straight up. (The rotated tree is the vanilla tree turned by 90/180/270 degrees.)
 * The `all_*` commands and `message` lay the trees out in a straight line running away from you (7x7 cells with a 1-block
 * separator, no signs), so you can walk under the whole line without turning. Glyphs that are the same shape turned
 * around (W/M, i/!, ...) share one tree.
 *
 * Data: mod/glyphs.json (baked in as /stemkit/glyphs.json): id, set (upper|lower|digit|punct), ch, name,
 * variant, s0, layerY (layer offset above the base block), heading (compass direction that reads upright in vanilla).
 */
public final class GlyphCommand {
	/** xform: optional position normalisation applied before the reading rotation (see GridLayout.transformColumn); 0 = none. */
	record Glyph(String id, String set, String ch, String name, String variant, long s0, int layerY, Direction heading, int xform) {}

	static List<Glyph> GLYPHS = List.of();


	private GlyphCommand() {}

	/** Editable copy: <game dir>/config/stemkit/glyphs.json (written from the built-in one on first run and whenever the mod is newer than the copy, see ConfigFiles; re-read on /reload). */
	static java.nio.file.Path configFile() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("stemkit").resolve("glyphs.json");
	}

	private static List<Glyph> load() {
		List<Glyph> out = new ArrayList<>();
		java.nio.file.Path cfg = configFile();
		ConfigFiles.ensure(cfg, "/stemkit/glyphs.json");   // created / refreshed when the mod is newer than the copy
		try (InputStream in = java.nio.file.Files.exists(cfg) ? java.nio.file.Files.newInputStream(cfg)
				: GlyphCommand.class.getResourceAsStream("/stemkit/glyphs.json")) {
			if (in == null) return out;
			JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			for (JsonElement e : root.getAsJsonArray("glyphs")) {
				JsonObject o = e.getAsJsonObject();
				Direction h = Direction.byName(o.get("heading").getAsString().toLowerCase());
				if (h == null || h.getAxis().isVertical()) continue;
				out.add(new Glyph(o.get("id").getAsString(), o.get("set").getAsString(), o.get("ch").getAsString(),
					o.has("name") ? o.get("name").getAsString() : o.get("ch").getAsString(),
					o.has("variant") ? o.get("variant").getAsString() : "warped", o.get("s0").getAsLong(), o.get("layerY").getAsInt(), h,
					o.has("xform") ? o.get("xform").getAsInt() : 0));
			}
		} catch (Exception ex) {
			com.mojang.logging.LogUtils.getLogger().error("[stemkit] bad glyphs.json", ex);
		}
		return out;
	}

	static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
		GLYPHS = load();
		LiteralArgumentBuilder<CommandSourceStack> chr = Commands.literal("char").executes(c -> list(c.getSource()));
		Map<String, Glyph> seen = new LinkedHashMap<>();
		for (Glyph g : GLYPHS) {
			String lit = g.set().equals("punct") ? g.name() : g.ch();
			if (seen.containsKey(lit) || lit.startsWith("all_") || lit.startsWith("ALL_") || !lit.matches("[0-9A-Za-z_\\-.+]+")) continue;
			seen.put(lit, g);
			chr.then(Commands.literal(lit)
				.executes(c -> growOne(c.getSource(), Targeting.defaultPos(c.getSource()), g))
				.then(Commands.argument("pos", BlockPosArgument.blockPos())
					.executes(c -> growOne(c.getSource(), BlockPosArgument.getLoadedBlockPos(c, "pos"), g))));
		}
		chr.then(allCmd("ascii", "everything"));      // four lines: lowercase, uppercase, digits, punctuation
		chr.then(allCmd("all_alpha", "lower"));     // every lowercase letter
		chr.then(allCmd("ALL_ALPHA", "upper"));     // every uppercase letter
		chr.then(allCmd("all_num", "numbers"));
		chr.then(allCmd("all_punct", "punctuation"));
		root.then(chr);
		root.then(Commands.literal("message")
			.executes(c -> {
				c.getSource().sendSuccess(() -> Component.literal("/stemkit message <text>").withStyle(ChatFormatting.WHITE), false);
				c.getSource().sendSuccess(() -> Component.literal("Spells the text as one straight line of glyph trees running away from you. "
					+ "Spaces become blank trees, characters with no tree are skipped. Example: /stemkit message hello world").withStyle(ChatFormatting.GRAY), false);
				return 0;
			})
			.then(Commands.argument("text", StringArgumentType.greedyString())
				.executes(c -> message(c.getSource(), Targeting.defaultPos(c.getSource()), StringArgumentType.getString(c, "text")))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> allCmd(String literal, String which) {
		return Commands.literal(literal)
			.executes(c -> all(c.getSource(), Targeting.defaultPos(c.getSource()), which))
			.then(Commands.argument("pos", BlockPosArgument.blockPos())
				.executes(c -> all(c.getSource(), BlockPosArgument.getLoadedBlockPos(c, "pos"), which)));
	}

	private static List<Glyph> setOf(String which) {
		List<Glyph> out = new ArrayList<>();
		for (Glyph g : GLYPHS) {
			boolean in = switch (which) {
					case "lower" -> g.set().equals("lower");
				case "upper" -> g.set().equals("upper");
				case "numbers" -> g.set().equals("digit");
				default -> g.set().equals("punct");
			};
			if (in) out.add(g);
		}
		return out;
	}

	private static int list(CommandSourceStack src) {
		StringBuilder letters = new StringBuilder(), digits = new StringBuilder(), marks = new StringBuilder();
		for (Glyph g : GLYPHS) {
			switch (g.set()) {
				case "upper", "lower" -> letters.append(g.ch()).append(' ');
				case "digit" -> digits.append(g.ch()).append(' ');
				default -> marks.append(g.name()).append(' ');
			}
		}
		src.sendSuccess(() -> Component.literal(GLYPHS.size() + " glyph trees").withStyle(ChatFormatting.WHITE)
			.append(Component.literal("  letters: ").withStyle(ChatFormatting.GRAY)).append(Component.literal(letters.toString().trim()).withStyle(ChatFormatting.GOLD))
			.append(Component.literal("  digits: ").withStyle(ChatFormatting.GRAY)).append(Component.literal(digits.toString().trim()).withStyle(ChatFormatting.GOLD)), false);
		src.sendSuccess(() -> Component.literal("punctuation: ").withStyle(ChatFormatting.GRAY).append(Component.literal(marks.toString().trim()).withStyle(ChatFormatting.GOLD)), false);
		src.sendSuccess(() -> Component.literal("/stemkit char <glyph> [pos] | ascii | all_alpha | ALL_ALPHA | all_num | all_punct   /stemkit message <text>").withStyle(ChatFormatting.GRAY), false);
		return GLYPHS.size();
	}

	/** Clockwise quarter turns (seen from above) that make a glyph reading upright facing `heading` read upright facing `facing`. */
	static int turns(Direction heading, Direction facing) {
		return Math.floorMod(facing.get2DDataValue() - heading.get2DDataValue(), 4);
	}

	/** Grows the glyph's tree at pos and turns it to read upright for a viewer facing `facing`. */
	static boolean growRotated(ServerLevel level, BlockPos pos, Glyph g, Direction facing) {
		StemkitCommand.Grown r = StemkitCommand.growTree(level, pos, g.variant().equals("warped"), g.s0());
		if (r.failure() != null) return false;
		GridLayout.transformColumn(level, pos, 3, 27, g.xform());          // put the mark where it belongs (e.g. ' high, , low)
		GridLayout.rotateColumn(level, pos, 3, 27, turns(g.heading(), facing));
		return true;
	}

	private static int growOne(CommandSourceStack src, BlockPos pos, Glyph g) {
		pos = Targeting.fit(src, pos, 28);
		Direction facing = GridLayout.facing(src);
		if (!growRotated(src.getLevel(), pos, g, facing)) {
			src.sendFailure(Component.literal("Could not grow the tree for " + g.ch() + " here (needs build height y-1 .. y+28)."));
			return 0;
		}
		int n = turns(g.heading(), facing);
		// net change from vanilla: the xform (mirror m, then k counterclockwise quarter turns) followed by n clockwise turns
		int net = Math.floorMod(n - g.xform() % 4, 4);
		boolean mirrored = g.xform() >= 4;
		String rot = net == 0 ? "" : net == 2 ? "rotated 180°" : "rotated 90° " + (net == 1 ? "cw" : "ccw");
		String turned = rot.isEmpty() && !mirrored ? "vanilla orientation"
			: (rot.isEmpty() ? "mirrored" : rot + (mirrored ? " and mirrored" : "")) + " from vanilla";
		src.sendSuccess(() -> Component.literal(g.ch() + ": " + turned).withStyle(ChatFormatting.GOLD), true);
		return 1;
	}

	/** Cell that grows glyph g rotated for `facing`; a null glyph is a blank cell (a space). */
	private static GridLayout.Cell cell(Glyph g, Direction facing) {
		if (g == null) return new GridLayout.Cell(List.of(), false, (lv, pos) -> true);
		return new GridLayout.Cell(List.of(), g.variant().equals("warped"), (lv, pos) -> growRotated(lv, pos, g, facing));
	}

	/** Empty blocks between the lines of `char all` (same spacing as the rows of `grow all`). */
	private static final int LINE_GAP = 9;

	private static int all(CommandSourceStack src, BlockPos corner, String which) {
		corner = Targeting.fit(src, corner, 28);
		ServerLevel level = src.getLevel();
		if (!GridLayout.fits(level, corner, 27)) {
			src.sendFailure(Component.literal("Not enough room: need build height from y-1 to y+28."));
			return 0;
		}
		if (which.equals("everything")) {
			// four straight lines (lowercase, uppercase, digits, punctuation), each running away from the player like the single
			// sets; the lines stack sideways with LINE_GAP empty blocks between them
			Direction facing = GridLayout.facing(src);
			Direction stack = facing.getCounterClockWise();
			int total = 0;
			BlockPos at = corner;
			for (String w : new String[] {"lower", "upper", "numbers", "punctuation"}) {
				List<GridLayout.Cell> cells = new ArrayList<>();
				for (Glyph g : setOf(w)) cells.add(cell(g, facing));
				if (cells.isEmpty()) continue;
				GridLayout.Result r = GridLayout.grow(level, at, stack, 3, 27, cells.size(), 0, false, cells);
				total += r.grown();
				at = r.behind(LINE_GAP);
			}
			final int grown = total;
			src.sendSuccess(() -> Component.literal("Grew " + grown + " glyph trees in 4 lines.").withStyle(ChatFormatting.GREEN), true);
			return grown;
		}
		List<Glyph> gs = setOf(which);
		Direction facing = GridLayout.facing(src);
		List<GridLayout.Cell> cells = new ArrayList<>();
		for (Glyph g : gs) cells.add(cell(g, facing));
		// one straight line running AWAY from the player (the walking direction), like /stemkit message
		GridLayout.Result r = GridLayout.grow(level, corner, facing.getCounterClockWise(), 3, 27, Math.max(1, cells.size()), 0, false, cells);
		src.sendSuccess(() -> Component.literal("Grew " + r.grown() + " " + (which.equals("lower") ? "lowercase" : which.equals("upper") ? "uppercase" : which) + " trees.").withStyle(ChatFormatting.GREEN), true);
		return r.grown();
	}

	private static int message(CommandSourceStack src, BlockPos corner, String text) {
		corner = Targeting.fit(src, corner, 28);
		ServerLevel level = src.getLevel();
		if (!GridLayout.fits(level, corner, 27)) {
			src.sendFailure(Component.literal("Not enough room: need build height from y-1 to y+28."));
			return 0;
		}
		Direction facing = GridLayout.facing(src);
		List<GridLayout.Cell> cells = new ArrayList<>();
		StringBuilder missing = new StringBuilder();
		// a space is the "space" glyph tree (a solid wart underside, no shroomlights) once one is in glyphs.json, else an empty cell
		Glyph space = null;
		for (Glyph x : GLYPHS) if (x.ch().equals(" ") || x.name().equals("space")) { space = x; break; }
		final Glyph spaceTree = space;
		text.codePoints().forEach(cp -> {
			String ch = new String(Character.toChars(cp));
			if (ch.isBlank()) { cells.add(cell(spaceTree, facing)); return; }
			Glyph g = null;
			for (Glyph x : GLYPHS) if (x.ch().equals(ch)) { g = x; break; }
			if (g == null) { missing.append(ch); cells.add(cell(null, facing)); return; }
			cells.add(cell(g, facing));
		});
		if (cells.isEmpty()) return 0;
		GridLayout.Result r = GridLayout.grow(level, corner, facing.getCounterClockWise(), 3, 27, cells.size(), 0, false, cells);
		src.sendSuccess(() -> Component.literal("Grew \"" + text + "\".").withStyle(ChatFormatting.GREEN), true);
		if (missing.length() > 0) {
			src.sendFailure(Component.literal("No tree yet for: " + missing));
		}
		return r.grown();
	}
}
