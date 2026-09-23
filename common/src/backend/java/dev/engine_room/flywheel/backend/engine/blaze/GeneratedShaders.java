package dev.engine_room.flywheel.backend.engine.blaze;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.shaders.ShaderType;

import net.minecraft.resources.Identifier;

/**
 * Shader sources Flywheel built at runtime, offered to Minecraft as though they were files.
 *
 * <p>Flywheel's shaders cannot be shipped as files, because there is no fixed set of them. What a
 * vertex shader has to do depends on the instance type it is drawing -- the struct, the code that
 * unpacks it from a buffer, and the body a mod supplied -- and that is a combination, not a list.
 * The OpenGL backend answers this by compiling its own programs and never involving Minecraft at
 * all, which is exactly the freedom Vulkan takes away: a pipeline names its shaders by
 * {@link Identifier} and Minecraft resolves them.
 *
 * <p>So they are resolved from here instead. Anything registered under a generated identifier is
 * served to {@code ShaderManager} by {@code ShaderManagerCompilationCacheMixin} before it looks in
 * the resource packs, and everything else falls through untouched.
 *
 * <p>Registration has to happen before the pipeline is first used rather than before it is built --
 * Blaze3D compiles lazily -- but the ordering is easiest to keep right by treating them as one
 * step, which {@link #pipeline} does.
 */
public final class GeneratedShaders {
	private static final String NAMESPACE = "flywheel";
	private static final String PREFIX = "generated/";

	private static final Map<Key, String> SOURCES = new ConcurrentHashMap<>();

	private GeneratedShaders() {
	}

	/**
	 * Registers a vertex and fragment shader under one name, and hands back the identifier a
	 * pipeline should be built with.
	 *
	 * @param name a name unique to this combination of instance type, material and anything else
	 *             that changes the generated source. Two different sources under one name is a
	 *             silent wrong-shader bug, so callers derive it rather than spelling it.
	 */
	public static Identifier pipeline(String name, String vertex, String fragment) {
		Identifier id = Identifier.fromNamespaceAndPath(NAMESPACE, PREFIX + name);

		SOURCES.put(new Key(id, ShaderType.VERTEX), vertex);
		SOURCES.put(new Key(id, ShaderType.FRAGMENT), fragment);

		return id;
	}

	/** What the mixin asks. Null for everything Flywheel did not generate. */
	public static @Nullable String get(Identifier id, ShaderType type) {
		if (!NAMESPACE.equals(id.getNamespace()) || !id.getPath()
				.startsWith(PREFIX)) {
			// Checked before the map lookup, so that the overwhelming majority of calls -- every
			// shader in the game that is not ours -- cost a string compare rather than a hash.
			return null;
		}

		return SOURCES.get(new Key(id, type));
	}

	/**
	 * Forgets everything generated so far.
	 *
	 * <p>Called when the backend is torn down. Sources are keyed by what they were generated from,
	 * so a stale one is not wrong so much as wasteful -- but a resource reload can change the body
	 * a mod supplied, and then it is wrong.
	 */
	public static void clear() {
		SOURCES.clear();
	}

	private record Key(Identifier id, ShaderType type) {
	}
}
