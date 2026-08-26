package dev.engine_room.flywheel.impl.compat;

import org.jetbrains.annotations.Nullable;

import dev.engine_room.flywheel.api.visualization.BlockEntityVisualizer;
import dev.engine_room.flywheel.impl.FlwImpl;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Sodium's {@code net.caffeinemc.mods.sodium.api} package, and with it
 * {@code BlockEntityRenderHandler}'s render predicates, is not shipped by the 26.2 builds
 * (0.9.2-alpha). Until it comes back there is no supported way to tell Sodium to skip block
 * entities Flywheel has taken over, so this is detection only.
 */
public final class SodiumCompat {
	public static final boolean ACTIVE = CompatMod.SODIUM.isLoaded;

	static {
		if (ACTIVE) {
			FlwImpl.LOGGER.debug("Detected Sodium");
		}
	}

	private SodiumCompat() {
	}

	@Nullable
	public static <T extends BlockEntity> Object onSetBlockEntityVisualizer(BlockEntityType<T> type, @Nullable BlockEntityVisualizer<? super T> oldVisualizer, @Nullable BlockEntityVisualizer<? super T> newVisualizer, @Nullable Object predicate) {
		return null;
	}
}
