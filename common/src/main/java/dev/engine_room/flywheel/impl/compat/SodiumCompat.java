package dev.engine_room.flywheel.impl.compat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.jetbrains.annotations.Nullable;

import dev.engine_room.flywheel.api.visualization.BlockEntityVisualizer;
import dev.engine_room.flywheel.impl.FlwImpl;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Tells Sodium which block entities Flywheel has taken over.
 *
 * <h2>Why this matters beyond skipping a draw</h2>
 * <p>Sodium asks its predicates about every block entity it considers while building a section, and
 * {@link VisualizationHelper#tryAddBlockEntity} queues the block entity as it answers. That is the
 * only thing that offers a block entity to Flywheel more than once: {@code LevelChunk.setBlockEntity}
 * fires when the chunk loads and never again. Without a predicate registered, a visualization manager
 * created or replaced after a chunk has loaded never learns about anything in it, and every machine
 * already in the world renders nothing while one placed afterwards is fine. On the vanilla chunk
 * renderer the same job is done by {@code SectionCompilerMixin}, which Sodium replaces.
 *
 * <h2>26.2 note</h2>
 * <p>This was reduced to detection only, on the grounds that Sodium's {@code api} package was not
 * shipped in the 26.2 builds. It is shipped -- but nested inside the jar-in-jar rather than in the
 * outer artifact, so it is absent from the compile classpath and easy to conclude is missing. Hence
 * the reflection: the API cannot be named at compile time, and binding it this way also means a
 * Sodium build that genuinely lacked it would degrade to the old detection-only behaviour rather
 * than fail to load.
 */
public final class SodiumCompat {
	public static final boolean ACTIVE = CompatMod.SODIUM.isLoaded && Internals.AVAILABLE;

	static {
		if (CompatMod.SODIUM.isLoaded) {
			if (Internals.AVAILABLE) {
				FlwImpl.LOGGER.debug("Detected Sodium");
			} else {
				FlwImpl.LOGGER.warn(
						"Detected Sodium, but its block entity render API is missing. Block entities Flywheel visualizes may render twice, and visuals will not be re-offered after a reload.");
			}
		}
	}

	private SodiumCompat() {
	}

	@Nullable
	public static <T extends BlockEntity> Object onSetBlockEntityVisualizer(BlockEntityType<T> type, @Nullable BlockEntityVisualizer<? super T> oldVisualizer, @Nullable BlockEntityVisualizer<? super T> newVisualizer, @Nullable Object predicate) {
		if (!ACTIVE) {
			return null;
		}

		if (oldVisualizer == null && newVisualizer != null) {
			if (predicate != null) {
				throw new IllegalArgumentException("Sodium predicate must be null when old visualizer is null");
			}

			return Internals.addPredicate(type);
		}

		if (oldVisualizer != null && newVisualizer == null) {
			if (predicate == null) {
				throw new IllegalArgumentException("Sodium predicate must not be null when old visualizer is not null");
			}

			Internals.removePredicate(type, predicate);
			return null;
		}

		return predicate;
	}

	private static final class Internals {
		private static final String PREDICATE_NAME = "net.caffeinemc.mods.sodium.api.blockentity.BlockEntityRenderPredicate";
		private static final String HANDLER_NAME = "net.caffeinemc.mods.sodium.api.blockentity.BlockEntityRenderHandler";

		static final boolean AVAILABLE;
		private static final Class<?> PREDICATE;
		private static final Object HANDLER;
		private static final Method ADD;
		private static final Method REMOVE;

		static {
			Class<?> predicate = null;
			Object handler = null;
			Method add = null;
			Method remove = null;

			try {
				predicate = Class.forName(PREDICATE_NAME);
				Class<?> handlerClass = Class.forName(HANDLER_NAME);
				handler = handlerClass.getMethod("instance")
						.invoke(null);
				add = handlerClass.getMethod("addRenderPredicate", BlockEntityType.class, predicate);
				remove = handlerClass.getMethod("removeRenderPredicate", BlockEntityType.class, predicate);
			} catch (Throwable t) {
				predicate = null;
				handler = null;
				add = null;
				remove = null;
			}

			PREDICATE = predicate;
			HANDLER = handler;
			ADD = add;
			REMOVE = remove;
			AVAILABLE = predicate != null && handler != null && add != null && remove != null;
		}

		/**
		 * {@code shouldRender} answers whether Sodium should draw it itself, so a block entity Flywheel
		 * takes over answers false. Queuing it is the point: this runs for every block entity in every
		 * section Sodium builds.
		 */
		static Object addPredicate(BlockEntityType<?> type) {
			InvocationHandler handler = (proxy, method, args) -> {
				if (method.getDeclaringClass() == Object.class) {
					return switch (method.getName()) {
						case "equals" -> proxy == args[0];
						case "hashCode" -> System.identityHashCode(proxy);
						default -> "FlywheelSodiumPredicate";
					};
				}

				return !VisualizationHelper.tryAddBlockEntity((BlockEntity) args[2]);
			};

			Object predicate = Proxy.newProxyInstance(PREDICATE.getClassLoader(), new Class<?>[] { PREDICATE }, handler);

			try {
				ADD.invoke(HANDLER, type, predicate);
			} catch (Throwable t) {
				FlwImpl.LOGGER.warn("Could not register a Sodium render predicate for {}", type, t);
			}

			return predicate;
		}

		static void removePredicate(BlockEntityType<?> type, Object predicate) {
			try {
				REMOVE.invoke(HANDLER, type, predicate);
			} catch (Throwable t) {
				FlwImpl.LOGGER.warn("Could not remove a Sodium render predicate for {}", type, t);
			}
		}
	}
}
