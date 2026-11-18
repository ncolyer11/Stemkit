package dev.stemkit.mixin;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to the generator a WorldgenRandom wraps. */
@Mixin(WorldgenRandom.class)
public interface WorldgenRandomAccessor {
	@Accessor("randomSource")
	RandomSource stemkit$inner();
}
