package dev.stemkit.mixin;

import dev.stemkit.TreeCapture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.HugeFungusFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Notes the random state every time a huge fungus is about to grow, so /stemkit seed can report it. Changes nothing. */
@Mixin(HugeFungusFeature.class)
public abstract class HugeFungusFeatureMixin {
	@Inject(method = "place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z",
		at = @At("HEAD"))
	private void stemkit$capture(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin, CallbackInfoReturnable<Boolean> cir) {
		TreeCapture.onPlace((HugeFungusFeature) (Object) this, level, random, origin);
	}
}
