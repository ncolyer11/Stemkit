package dev.stemkit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;

/**
 * Grows many trees side by side in a grid (used by `grow all`, `char all_alpha` and the other glyph sets).
 *
 * Layout relative to the player who runs the command: `corner` is the near-left corner; a row runs to the player's RIGHT
 * (first cell on the left, like reading), further rows go AWAY from the player. Every cell is a (2*half+1) square
 * (7x7 for bone-meal trees, half = 3); a row's cells are framed by 1-block lines of polished blackstone bricks at nylium
 * level (y-1), with chiseled polished blackstone where lines cross; rows are separated by an empty gap. Cells can be
 * sorted by tree height (shortest first). A glowing sign with the cell's label stands on the near line of each cell,
 * facing the player, both sides written. The whole area from y to y+height is cleared to air first.
 */
final class GridLayout {
	private GridLayout() {}

	/**
	 * One cell: sign text (up to 4 lines; empty = no sign), the grower (called with the cell's centre, the fungus / base
	 * position), an optional silent probe grower for height sorting, and the cell half-width (3 = 7x7, 4 = 9x9).
	 */
	record Cell(List<String> label, boolean warped, BiFunction<ServerLevel, BlockPos, Boolean> grow, BiFunction<ServerLevel, BlockPos, Boolean> probe,
			int half, List<String> back) {
		Cell(List<String> label, boolean warped, BiFunction<ServerLevel, BlockPos, Boolean> grow) {
			this(label, warped, grow, grow, 3, label);
		}
	}

	/** Outcome: cells grown / failed, layout size, and where the grid ends (to put another grid behind it). */
	record Result(int grown, int failed, int columns, int rows, BlockPos corner, Direction forward, int depth) {
		/** Near-left corner for a grid placed `gap` empty blocks behind this one. */
		BlockPos behind(int gap) {
			return corner.relative(forward, depth + 1 + gap);
		}
	}

	/** Border look: separator line block and the block where lines cross. */
	record Style(Block line, Block cross) {}

	/** Bone-meal trees and glyphs. */
	static final Style PLANTED = new Style(Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CHISELED_POLISHED_BLACKSTONE);
	/** Natural (world-generated) trees. */
	static final Style NATURAL = new Style(Blocks.POLISHED_BLACKSTONE, Blocks.CHISELED_POLISHED_BLACKSTONE);
	/** Sibling pairs (warped + crimson from one s0). */
	static final Style SIBLING = new Style(Blocks.QUARTZ_BRICKS, Blocks.CHISELED_QUARTZ_BLOCK);

	/** Checks build height for a grid at this y (y-1 .. y+height). */
	static boolean fits(ServerLevel level, BlockPos corner, int height) {
		return level.isInsideBuildHeight(corner.below()) && level.isInsideBuildHeight(corner.above(height));
	}

	/** The horizontal direction the command's player faces (south for a console / command block). */
	static Direction facing(CommandSourceStack src) {
		Entity e = src.getEntity();
		return e == null ? Direction.SOUTH : e.getDirection();
	}

	/** Same as below with every cell's own half-width (the `half` argument is ignored and kept for callers). */
	static Result grow(ServerLevel level, BlockPos corner, Direction forward, int half, int height, int perRow, int rowGap, boolean byHeight,
			List<Cell> input) {
		return grow(level, corner, forward, height, perRow, rowGap, byHeight, input);
	}

	/**
	 * @param corner   near-left corner of the grid at fungus level (the separator lines go at corner.y - 1)
	 * @param forward  rows stack along this direction; a row runs along forward.getClockWise()
	 * @param height   blocks above corner.y to clear (27 for bone-meal trees, 30 for natural ones)
	 * @param perRow   cells per row: rows are full (perRow cells) except the last one
	 * @param rowGap   empty blocks between one row's far line and the next row's near line
	 * @param byHeight sort the cells by tree height, shortest first (each tree is grown once at a probe spot to measure it)
	 */
	static Result grow(ServerLevel level, BlockPos corner, Direction forward, int height, int perRow, int rowGap, boolean byHeight,
			List<Cell> input) {
		return grow(level, corner, forward, height, perRow, rowGap, byHeight, PLANTED, input);
	}

	static Result grow(ServerLevel level, BlockPos corner, Direction forward, int height, int perRow, int rowGap, boolean byHeight,
			Style style, List<Cell> input) {
		Direction right = forward.getClockWise();
		List<Cell> cells = new ArrayList<>(input);
		int n = cells.size();
		if (n == 0) return new Result(0, 0, 0, 0, corner, forward, -1);
		int cols = Math.max(1, Math.min(perRow, n));
		int rows = (n + cols - 1) / cols;
		int maxHalf = 0;
		for (Cell c : cells) maxHalf = Math.max(maxHalf, c.half());

		if (byHeight) {
			// the probe spot is inside the first cell, which is cleared below anyway
			BlockPos probe = at(corner, right, forward, 1 + maxHalf, 1 + maxHalf);
			List<int[]> keyed = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				Cell c = cells.get(i);
				int h = -1;
				try {
					if (Boolean.TRUE.equals(c.probe().apply(level, probe))) h = topOf(level, probe, c.half(), height);
				} catch (Exception ex) {
					h = -1;
				}
				clearColumn(level, probe, maxHalf, height);
				keyed.add(new int[] {h, i});
			}
			keyed.sort((u, v) -> u[0] != v[0] ? Integer.compare(u[0], v[0]) : Integer.compare(u[1], v[1]));
			List<Cell> sorted = new ArrayList<>();
			for (int[] k : keyed) sorted.add(cells.get(k[1]));
			cells = sorted;
		}

		// layout: per row, cells side by side (width 2*half+1 each, one line between), row depth = widest cell in it
		int[] cellA = new int[n], rowB = new int[rows], rowD = new int[rows], rowW = new int[rows];
		int b = 0, wMax = 0;
		for (int row = 0; row < rows; row++) {
			int a = 0, depth = 0;
			for (int i = row * cols; i < Math.min(n, (row + 1) * cols); i++) {
				int wc = 2 * cells.get(i).half() + 1;
				cellA[i] = a;                      // the line before the cell
				a += wc + 1;
				depth = Math.max(depth, wc);
			}
			rowB[row] = b; rowD[row] = depth; rowW[row] = a;
			wMax = Math.max(wMax, a);
			b += depth + 1 + 1 + rowGap;          // near line, cells, far line, gap
		}
		int d = rowB[rows - 1] + rowD[rows - 1] + 1;
		int y0 = corner.getY();

		for (int a = 0; a <= wMax; a++) {
			for (int bb = 0; bb <= d; bb++) {
				BlockPos base = at(corner, right, forward, a, bb);
				for (int y = 0; y <= height; y++) {
					level.setBlock(base.above(y), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
				}
			}
		}
		for (int row = 0; row < rows; row++) {
			java.util.Set<Integer> vlines = new java.util.HashSet<>();
			for (int i = row * cols; i < Math.min(n, (row + 1) * cols); i++) vlines.add(cellA[i]);
			vlines.add(rowW[row]);
			for (int a = 0; a <= rowW[row]; a++) {
				for (int bb = 0; bb <= rowD[row] + 1; bb++) {
					boolean la = vlines.contains(a), lb = bb == 0 || bb == rowD[row] + 1;
					if (la || lb) {
						level.setBlock(at(corner, right, forward, a, rowB[row] + bb).below(), (la && lb ? style.cross() : style.line()).defaultBlockState(), Block.UPDATE_CLIENTS);
					}
				}
			}
		}

		int grown = 0, failed = 0;
		for (int i = 0; i < n; i++) {
			int row = i / cols;
			Cell c = cells.get(i);
			int ca = cellA[i] + 1 + c.half();
			BlockPos center = at(corner, right, forward, ca, rowB[row] + 1 + rowD[row] / 2);
			boolean ok;
			try {
				ok = Boolean.TRUE.equals(c.grow().apply(level, center));
			} catch (Exception ex) {
				ok = false;
			}
			if (ok) grown++; else failed++;
			if (!c.label().isEmpty()) sign(level, at(corner, right, forward, ca, rowB[row]), c.label(), c.back(), c.warped(), forward.getOpposite());
		}
		return new Result(grown, failed, cols, rows, corner, forward, d);
	}

	/**
	 * Turns everything in the column around c (|dx|,|dz| <= half, 0 <= dy <= height) by `turns` clockwise quarter turns
	 * (seen from above) about the centre column. Block states are rotated too (stems are vertical, so unchanged).
	 */
	static void rotateColumn(ServerLevel level, BlockPos c, int half, int height, int turns) {
		int n = Math.floorMod(turns, 4);
		if (n == 0) return;
		Rotation rot = n == 1 ? Rotation.CLOCKWISE_90 : n == 2 ? Rotation.CLOCKWISE_180 : Rotation.COUNTERCLOCKWISE_90;
		int size = 2 * half + 1;
		BlockState[][][] buf = new BlockState[size][height + 1][size];
		for (int dx = -half; dx <= half; dx++) {
			for (int dz = -half; dz <= half; dz++) {
				for (int dy = 0; dy <= height; dy++) {
					buf[dx + half][dy][dz + half] = level.getBlockState(c.offset(dx, dy, dz));
				}
			}
		}
		// clear first (no updates, so vines etc. don't pop off), then place the turned copy
		for (int dx = -half; dx <= half; dx++) {
			for (int dz = -half; dz <= half; dz++) {
				for (int dy = 0; dy <= height; dy++) {
					level.setBlock(c.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				}
			}
		}
		for (int dx = -half; dx <= half; dx++) {
			for (int dz = -half; dz <= half; dz++) {
				BlockPos off = new BlockPos(dx, 0, dz).rotate(rot);
				for (int dy = 0; dy <= height; dy++) {
					BlockState st = buf[dx + half][dy][dz + half];
					if (st.isAir()) continue;
					level.setBlock(c.offset(off.getX(), dy, off.getZ()), st.rotate(rot), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				}
			}
		}
	}

	/**
	 * Applies a glyph's position-normalising transform about the centre column: xform = m*4 + k means first mirror
	 * dz -> -dz if m == 1, then k times (dx, dz) -> (dz, -dx) (a counterclockwise quarter turn seen from above).
	 * Same convention as the publish script that writes glyphs.json. Block states are left as they are (warped hats have no
	 * orientation-dependent blocks; stems are vertical).
	 */
	static void transformColumn(ServerLevel level, BlockPos c, int half, int height, int xform) {
		int m = (xform >> 2) & 1, k = xform & 3;
		if (m == 0 && k == 0) return;
		int size = 2 * half + 1;
		BlockState[][][] buf = new BlockState[size][height + 1][size];
		for (int dx = -half; dx <= half; dx++)
			for (int dz = -half; dz <= half; dz++)
				for (int dy = 0; dy <= height; dy++)
					buf[dx + half][dy][dz + half] = level.getBlockState(c.offset(dx, dy, dz));
		for (int dx = -half; dx <= half; dx++)
			for (int dz = -half; dz <= half; dz++)
				for (int dy = 0; dy <= height; dy++)
					level.setBlock(c.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
		for (int dx = -half; dx <= half; dx++) {
			for (int dz = -half; dz <= half; dz++) {
				int x = dx, z = m == 1 ? -dz : dz;
				for (int i = 0; i < k; i++) { int t = x; x = z; z = -t; }
				for (int dy = 0; dy <= height; dy++) {
					BlockState st = buf[dx + half][dy][dz + half];
					if (!st.isAir()) level.setBlock(c.offset(x, dy, z), st, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				}
			}
		}
	}

	/** corner + a blocks to the right + b blocks forward (same y). */
	private static BlockPos at(BlockPos corner, Direction right, Direction forward, int a, int b) {
		return corner.relative(right, a).relative(forward, b);
	}

	/** Highest dy (0..height) with a non-air block in the cell's column, -1 if none. */
	private static int topOf(ServerLevel level, BlockPos c, int half, int height) {
		for (int dy = height; dy >= 0; dy--) {
			for (int dx = -half; dx <= half; dx++) {
				for (int dz = -half; dz <= half; dz++) {
					if (!level.getBlockState(c.offset(dx, dy, dz)).isAir()) return dy;
				}
			}
		}
		return -1;
	}

	private static void clearColumn(ServerLevel level, BlockPos c, int half, int height) {
		for (int dx = -half - 1; dx <= half + 1; dx++) {
			for (int dz = -half - 1; dz <= half + 1; dz++) {
				for (int dy = 0; dy <= height; dy++) {
					level.setBlock(c.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
				}
			}
		}
	}

	/** A standing sign (crimson or warped wood) with glowing text: `front` facing `towards`, `back` on the other side. */
	static void sign(ServerLevel level, BlockPos pos, List<String> front, List<String> back, boolean warped, Direction towards) {
		Block block = warped ? Blocks.WARPED_SIGN : Blocks.CRIMSON_SIGN;
		int rot = RotationSegment.convertToSegment(towards.toYRot());
		level.setBlock(pos, block.defaultBlockState().setValue(StandingSignBlock.ROTATION, rot), Block.UPDATE_ALL);
		if (level.getBlockEntity(pos) instanceof SignBlockEntity be) {
			be.setText(text(front), SignTextSlot.FRONT);
			be.setText(text(back), SignTextSlot.BACK);
			be.setWaxed(true);
		}
	}

	private static SignText text(List<String> lines) {
		SignText.Mutable t = SignText.EMPTY.asMutable();
		for (int k = 0; k < Math.min(4, lines.size()); k++) t.setLine(k, Component.literal(lines.get(k)));
		return t.asImmutable().withColor(DyeColor.WHITE).withGlowingText(true);
	}

	/** Splits a code name like "corner_beacon" into sign lines of at most 15 characters. */
	static List<String> labelLines(String name) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for (String part : name.split("_")) {
			if (cur.length() > 0 && cur.length() + 1 + part.length() > 15) {
				out.add(cur.toString());
				cur.setLength(0);
			}
			if (cur.length() > 0) cur.append(' ');
			cur.append(part);
		}
		if (cur.length() > 0) out.add(cur.toString());
		return out;
	}
}
