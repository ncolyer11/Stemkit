package dev.stemkit.mixin;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to the 48-bit state of the world's random generator. */
@Mixin(LegacyRandomSource.class)
public interface LegacyRandomSourceAccessor {
	@Accessor("seed")
	AtomicLong stemkit$seed();
}
