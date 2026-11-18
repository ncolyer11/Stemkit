package dev.stemkit;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealSource;
import net.minecraft.world.level.block.NetherFungusBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;

/**
 * /stemkit grow <crimson|warped> <s0> [pos]   grow a bone-meal tree from a raw level-RNG state
 * /stemkit sibling <s0> [pos]                 the same s0 as warped (left) and crimson (right), side by side, compared
 * /stemkit xray [radius]                      toggle wart blocks to stained glass (default radius 42)
 * /stemkit grow [<code name> [pos] | all [pos] | <s0> [pos] | warped|crimson <s0> [pos]]   named-tree dictionary (no argument: the list); a name wins over a seed, see TreeDictionary
 *
 * Grows a huge fungus with vanilla's own NetherFungusBlock bone-meal code (isBonemealSuccess + performBonemeal ->
 * HugeFungusFeature, planted=true), feeding it a LegacyRandomSource whose internal 48-bit state is s0, i.e. exactly the
 * state level.getRandom() would have at the moment a player uses bone meal (BoneMealItem.growCrop). The level random is a
 * LegacyRandomSource (RandomSource.create), a full-period LCG mod 2^48, so every s0 in [0, 2^48) is a state vanilla
 * can be in: every tree this command grows is one vanilla bone meal can produce. Nothing about the tree is edited.
 *
 * The 7x7 column (fungus y .. y+27) is cleared to air and a 7x7 nylium floor is placed under it first, matching the
 * search's assumption (open air, solid ground), so block counts equal the reference simulation's numbers.
 */
public final class StemkitCommand {
	private static final long MASK48 = (1L << 48) - 1;
	private static final long MULTIPLIER = 0x5DEECE66DL;

	private StemkitCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("stemkit")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.executes(c -> about(c.getSource()));

		LiteralArgumentBuilder<CommandSourceStack> growCmd = Commands.literal("grow");
		for (boolean warped : new boolean[] {false, true}) {
			growCmd.then(Commands.literal(warped ? "warped" : "crimson")
				.then(Commands.argument("s0", LongArgumentType.longArg(0L, MASK48))
					.executes(c -> grow(c.getSource(), feet(c), warped, LongArgumentType.getLong(c, "s0")))
					.then(Commands.argument("pos", BlockPosArgument.blockPos())
						.executes(c -> grow(c.getSource(), BlockPosArgument.getLoadedBlockPos(c, "pos"), warped,
							LongArgumentType.getLong(c, "s0"))))));
		}
		// grow <seed> [pos]: a bare number is a bone-meal s0 (warped, the variant the sweeps search). Named trees are literal
		// children added by TreeDictionary.register, and Brigadier tries literals before arguments, so a name always wins over a seed.
		growCmd.then(Commands.argument("s0", LongArgumentType.longArg(0L, MASK48))
			.executes(c -> grow(c.getSource(), feet(c), true, LongArgumentType.getLong(c, "s0")))
			.then(Commands.argument("pos", BlockPosArgument.blockPos())
				.executes(c -> grow(c.getSource(), BlockPosArgument.getLoadedBlockPos(c, "pos"), true,
					LongArgumentType.getLong(c, "s0")))));
		root.then(growCmd);

		// seed: the random state of the nearest tree (or `seed last` for the latest bone meal tree), saved as a trees.json entry
		root.then(Commands.literal("seed")
			.executes(c -> TreeCapture.command(c, false))
			.then(Commands.literal("last").executes(c -> TreeCapture.command(c, true))));

		root.then(Commands.literal("sibling")
			.executes(c -> {
				c.getSource().sendSuccess(() -> Component.literal("/stemkit sibling <seed> [pos]").withStyle(ChatFormatting.WHITE), false);
				c.getSource().sendSuccess(() -> Component.literal("Grows the seed as warped (left) and crimson (right) and compares them, vines ignored. "
					+ "Seed is a whole number from 0 to 281474976710655. Try 69221 for an identical pair.").withStyle(ChatFormatting.GRAY), false);
				return 0;
			})
			.then(Commands.argument("s0", LongArgumentType.longArg(0L, MASK48))
				.executes(c -> sibling(c.getSource(), feet(c), LongArgumentType.getLong(c, "s0")))
				.then(Commands.argument("pos", BlockPosArgument.blockPos())
					.executes(c -> sibling(c.getSource(), BlockPosArgument.getLoadedBlockPos(c, "pos"),
						LongArgumentType.getLong(c, "s0"))))));

		TreeDictionary.register(root);   // grow (list), grow <name>, grow all
		GlyphCommand.register(root);     // char <glyph> [pos] | all_alpha | ALL_ALPHA | all_num | all_punct, message <text>

		root.then(Commands.literal("xray")
			.executes(c -> xray(c.getSource(), 42))
			.then(Commands.argument("radius", IntegerArgumentType.integer(1, 48))
				.executes(c -> xray(c.getSource(), IntegerArgumentType.getInteger(c, "radius")))));

		dispatcher.register(root);
	}

	/** Wart blocks this mod turned into glass, per player: where, which dimension, and the original state. */
	private record XraySession(ResourceKey<Level> dimension, Map<BlockPos, BlockState> originals) {}

	private static final Map<UUID, XraySession> XRAY = new HashMap<>();

	/** No neighbour or shape updates: nothing (vines etc.) reacts to the swap, and the swap back restores the tree exactly. */
	private static final int SWAP_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	/**
	 * Toggle: turn every nether/warped wart block within `radius` (cube) of the player into red/cyan stained glass, so
	 * shroomlights hidden inside hats become visible; run again to put the exact original blocks back.
	 * Only blocks that are still the glass we placed are restored (anything you changed meanwhile is left alone).
	 */
	private static int xray(CommandSourceStack src, int radius) throws CommandSyntaxException {
		var player = src.getPlayerOrException();
		ServerLevel level = src.getLevel();
		XraySession active = XRAY.remove(player.getUUID());
		if (active != null) {
			ServerLevel orig = src.getServer().getLevel(active.dimension());
			int restored = 0;
			if (orig != null) {
				for (Map.Entry<BlockPos, BlockState> e : active.originals().entrySet()) {
					BlockState now = orig.getBlockState(e.getKey());
					if (now.getBlock() == glassFor(e.getValue().getBlock())) {
						orig.setBlock(e.getKey(), e.getValue(), SWAP_FLAGS);
						restored++;
					}
				}
			}
			int r = restored;
			src.sendSuccess(() -> Component.literal("Wart x-ray off: restored " + r + " wart blocks."), false);
			return r;
		}

		Map<BlockPos, BlockState> originals = new HashMap<>();
		BlockPos center = player.blockPosition();
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
			if (!level.isInsideBuildHeight(p)) continue;
			BlockState s = level.getBlockState(p);
			Block glass = glassFor(s.getBlock());
			if (glass != null) {
				BlockPos key = p.immutable();
				originals.put(key, s);
				level.setBlock(key, glass.defaultBlockState(), SWAP_FLAGS);
			}
		}
		XRAY.put(player.getUUID(), new XraySession(level.dimension(), originals));
		int n = originals.size();
		src.sendSuccess(() -> Component.literal("Wart x-ray on: " + n + " wart blocks within " + radius
			+ " are now stained glass. Run /stemkit xray again to restore them (before leaving the world)."), false);
		return n;
	}

	private static Block glassFor(Block b) {
		if (b == Blocks.NETHER_WART_BLOCK) return Blocks.STAINED_GLASS.pick(DyeColor.RED);
		if (b == Blocks.WARPED_WART_BLOCK) return Blocks.STAINED_GLASS.pick(DyeColor.CYAN);
		return null;
	}

	private static BlockPos feet(CommandContext<CommandSourceStack> c) {
		return Targeting.defaultPos(c.getSource());
	}

	/** Colours for the /stemkit intro: the same softened warped and crimson as the tree list, a pale text colour, a dim separator. */
	private static final int C_WARPED = 0x5FB8AE, C_CRIMSON = 0xC96A6A, C_SHROOM = 0xF7A44B, C_TEXT = 0xD6DCE6, C_DIM = 0x9AA3B2, C_TITLE = 0xC8D7F0;

	private static MutableComponent tint(String text, int rgb) {
		return Component.literal(text).withStyle(s -> s.withColor(rgb));
	}

	/** Plain /stemkit: who made it and the commands. */
	private static int about(CommandSourceStack src) {
		String version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("stemkit")
			.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("");
		src.sendSuccess(() -> tint("Stemkit " + version, C_SHROOM)
			.append(tint("  made by ", C_TEXT)).append(tint("ncolyer", C_WARPED))
			.append(tint(", with support from ", C_TEXT)).append(tint("plamiasty", C_CRIMSON)), false);
		MutableComponent cmds = tint("/stemkit ", C_TITLE);
		String[] names = {"grow", "sibling", "char", "message", "seed", "xray"};
		for (int i = 0; i < names.length; i++) {
			if (i > 0) cmds.append(tint(" | ", C_DIM));
			cmds.append(tint(names[i], C_TEXT));
		}
		src.sendSuccess(() -> cmds, false);
		return 1;
	}

	/** Result of growing one bone-meal tree: failure message (null on success) and the block counts. */
	record Grown(String failure, int shroom, int wart, int stem, int vines) {}

	private static int grow(CommandSourceStack src, BlockPos pos, boolean warped, long s0) {
		pos = Targeting.fit(src, pos, 28);
		Grown r = growTree(src.getLevel(), pos, warped, s0);
		if (r.failure() != null) {
			src.sendFailure(Component.literal(r.failure()));
			return 0;
		}
		src.sendSuccess(() -> Component.literal((warped ? "Warped" : "Crimson") + " fungus grown from s0=" + s0 + ": " + r.shroom()
			+ " shroomlights, " + r.wart() + " wart blocks, " + r.stem() + " stem, " + r.vines() + " vines."), true);
		return r.shroom();
	}

	/** Centre-to-centre distance of the two sibling trees: each clears a 7x7 column, so 8 leaves a 1-block gap. */
	private static final int SIBLING_GAP = 8;

	/**
	 * /stemkit sibling <s0> [pos]: the same s0 grown as warped (left) and crimson (right), side by side across
	 * the player's view, then compared block for block with weeping vines ignored.
	 */
	private static int sibling(CommandSourceStack src, BlockPos pos, long s0) {
		pos = Targeting.fit(src, pos, 28);
		Siblings s = growSiblings(src.getLevel(), pos, GridLayout.facing(src).getClockWise(), s0);
		if (s.failure() != null) {
			src.sendFailure(Component.literal(s.failure()));
			return 0;
		}
		ChatFormatting colour = s.identical() ? ChatFormatting.GREEN : s.sameShape() ? ChatFormatting.GOLD : ChatFormatting.GRAY;
		src.sendSuccess(() -> Component.literal(s.verdict() + ": warped " + s.warped().shroom() + " / crimson " + s.crimson().shroom()
			+ " shroomlights").withStyle(colour), true);
		return s.sameShape() ? 1 : 0;
	}

	/** A warped + crimson pair from one s0 and how they compare (vines ignored). */
	record Siblings(String failure, Grown warped, Grown crimson, int shapeDiff, int typeDiff) {
		boolean sameShape() { return failure == null && shapeDiff == 0; }
		boolean identical() { return sameShape() && typeDiff == 0; }
		String verdict() {
			return identical() ? "IDENTICAL siblings"
				: sameShape() ? "same shape, " + typeDiff + " wart/shroomlight blocks differ"
				: "not siblings (" + shapeDiff + " blocks differ in shape)";
		}
	}

	/** Grows the pair centred on pos: warped on the `right.getOpposite()` side, crimson on the `right` side, 8 apart. */
	static Siblings growSiblings(ServerLevel level, BlockPos pos, Direction right, long s0) {
		BlockPos wPos = pos.relative(right.getOpposite(), SIBLING_GAP / 2);
		BlockPos cPos = pos.relative(right, SIBLING_GAP / 2);
		Grown w = growTree(level, wPos, true, s0);
		if (w.failure() != null) return new Siblings(w.failure(), w, w, 0, 0);
		Grown c = growTree(level, cPos, false, s0);
		if (c.failure() != null) return new Siblings(c.failure(), w, c, 0, 0);
		int shapeDiff = 0, typeDiff = 0;
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				for (int dy = 0; dy <= 27; dy++) {
					int a = role(level.getBlockState(wPos.offset(dx, dy, dz)).getBlock());
					int b = role(level.getBlockState(cPos.offset(dx, dy, dz)).getBlock());
					if ((a == 0) != (b == 0)) shapeDiff++;
					else if (a != b) typeDiff++;
				}
			}
		}
		siblingBorder(level, pos, right);
		return new Siblings(null, w, c, shapeDiff, typeDiff);
	}

	/**
	 * Quartz frame in the floor (y-1) around a sibling pair centred on pos, like the regrow-all grid: quartz-brick lines
	 * around both 7x7 trees and between them, chiseled quartz where lines meet. Spans 17 x 9 blocks (a = -8..8 along right).
	 */
	static void siblingBorder(ServerLevel level, BlockPos pos, Direction right) {
		Direction fwd = right.getCounterClockWise();
		int half = SIBLING_GAP / 2 + 4;      // 8: one block outside each tree's 7x7
		for (int a = -half; a <= half; a++) {
			for (int b = -4; b <= 4; b++) {
				boolean la = a == -half || a == 0 || a == half, lb = b == -4 || b == 4;
				if (!la && !lb) continue;
				Block blk = la && lb ? GridLayout.SIBLING.cross() : GridLayout.SIBLING.line();
				level.setBlock(pos.relative(right, a).relative(fwd, b).below(), blk.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		}
	}

	/** Block role for the sibling comparison: 0 air (vines count as air), 1 stem, 2 wart, 3 shroomlight. */
	private static int role(Block b) {
		if (b == Blocks.SHROOMLIGHT) return 3;
		if (b == Blocks.NETHER_WART_BLOCK || b == Blocks.WARPED_WART_BLOCK) return 2;
		if (b == Blocks.CRIMSON_STEM || b == Blocks.WARPED_STEM) return 1;
		return 0;
	}

	/** The one place a bone-meal tree is grown (used by `grow` and by `regrow` of planted dictionary entries). */
	static Grown growTree(ServerLevel level, BlockPos pos, boolean warped, long s0) {
		if (!level.isInsideBuildHeight(pos.below()) || !level.isInsideBuildHeight(pos.above(28))) {
			return new Grown("Not enough room: need build height from y-1 to y+28.", 0, 0, 0, 0);
		}
		Block nylium = warped ? Blocks.WARPED_NYLIUM : Blocks.CRIMSON_NYLIUM;
		Block fungus = warped ? Blocks.WARPED_FUNGUS : Blocks.CRIMSON_FUNGUS;

		// Same surroundings the search assumed: open air above, solid floor below.
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				for (int dy = 0; dy <= 27; dy++) {
					level.setBlock(pos.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
				}
				level.setBlock(pos.offset(dx, -1, dz), nylium.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		}
		BlockState state = fungus.defaultBlockState();
		level.setBlock(pos, state, Block.UPDATE_ALL);
		NetherFungusBlock block = (NetherFungusBlock) state.getBlock();

		// Internal LCG state = s0 (the LegacyRandomSource constructor XORs its argument with the multiplier).
		LegacyRandomSource random = new LegacyRandomSource(s0 ^ MULTIPLIER);

		if (!block.isValidBonemealTarget(level, pos, state, BonemealSource.INTERACTION)) {
			return new Grown("Vanilla would not accept bone meal here.", 0, 0, 0, 0);
		}
		// Vanilla order (BoneMealItem.growCrop): the 40% roll, then the feature, on the same random.
		if (!block.isBonemealSuccess(level, random, pos, state, BonemealSource.INTERACTION)) {
			return new Grown("s0=" + s0 + " fails the 40% bone-meal roll: in vanilla this bone meal does nothing.", 0, 0, 0, 0);
		}
		block.performBonemeal(level, random, pos, state, BonemealSource.INTERACTION);

		int shroom = 0, wart = 0, stem = 0, vines = 0;
		Block wartBlock = warped ? Blocks.WARPED_WART_BLOCK : Blocks.NETHER_WART_BLOCK;
		Block stemBlock = warped ? Blocks.WARPED_STEM : Blocks.CRIMSON_STEM;
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				for (int dy = 0; dy <= 27; dy++) {
					Block b = level.getBlockState(pos.offset(dx, dy, dz)).getBlock();
					if (b == Blocks.SHROOMLIGHT) shroom++;
					else if (b == wartBlock) wart++;
					else if (b == stemBlock) stem++;
					else if (b == Blocks.WEEPING_VINES || b == Blocks.WEEPING_VINES_PLANT) vines++;
				}
			}
		}
		return new Grown(null, shroom, wart, stem, vines);
	}
}
