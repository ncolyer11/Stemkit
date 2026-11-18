package dev.stemkit;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Where a tree grows when the command is given no [pos]. */
final class Targeting {
	/** Crosshair reach for non-player entities; players use their own block reach (the outlined block). */
	static final double REACH = 5.0;
	/** Distance in front of the player when the crosshair hits nothing. */
	static final double AHEAD = 6.0;

	private Targeting() {}

	/**
	 * Looking at a block within normal reach (the block with the highlight outline): it becomes the nylium base and
	 * the tree grows on top of it. Anything else (sky, or ground further away, e.g. flying high): the tree grows
	 * 6 blocks in front of the player at foot level.
	 * No entity (command block, console): the command's position.
	 */
	static BlockPos defaultPos(CommandSourceStack src) {
		Entity e = src.getEntity();
		if (e == null) return BlockPos.containing(src.getPosition());
		double reach = e instanceof Player p ? p.blockInteractionRange() : REACH;
		HitResult hit = e.pick(reach, 1.0F, false);
		if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bh) {
			return bh.getBlockPos().above();
		}
		Vec3 look = e.getLookAngle();
		Vec3 flat = new Vec3(look.x, 0.0, look.z);
		flat = flat.lengthSqr() < 1.0E-6 ? Vec3.ZERO : flat.normalize().scale(AHEAD);
		return BlockPos.containing(e.position().add(flat));
	}

	/**
	 * Every grow command calls this first: if the spot is too high for a tree `height` blocks tall (plus its floor at y-1),
	 * the spot is moved straight down until the top fits under the build limit (and up if the floor would be below the
	 * world). Tells the player in one short line when it moved. A world too short for the tree is left as is (the grow
	 * code then reports that there is not enough room).
	 */
	static BlockPos fit(CommandSourceStack src, BlockPos pos, int height) {
		ServerLevel level = src.getLevel();
		int y = pos.getY();
		int minFloor = level.getMinY() + 1;                       // the floor block (y-1) must be inside the world
		while (!level.isInsideBuildHeight(y + height) && y > minFloor) y--;
		while (!level.isInsideBuildHeight(y - 1) && level.isInsideBuildHeight(y + height + 1)) y++;
		if (y == pos.getY()) return pos;
		int moved = pos.getY() - y;
		src.sendSuccess(() -> Component.literal(moved > 0 ? "Too high here: grown " + moved + " blocks lower."
			: "Too low here: grown " + (-moved) + " blocks higher.").withStyle(ChatFormatting.GRAY), false);
		return pos.atY(y);
	}
}
