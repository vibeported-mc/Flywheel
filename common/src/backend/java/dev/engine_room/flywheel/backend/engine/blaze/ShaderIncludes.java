package dev.engine_room.flywheel.backend.engine.blaze;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Reads a Flywheel shader file and pastes in whatever it includes.
 *
 * <p>Flywheel's shader sources are not Minecraft's. They live under {@code assets/<ns>/flywheel/}
 * and they use their own directive -- {@code #include "namespace:path"} -- rather than vanilla's
 * {@code #moj_import}. That is the form mods wrote against: Create's rotating shader opens with
 * {@code #include "flywheel:util/quaternion.glsl"} and would not compile without it.
 *
 * <p>So the directive is honoured here rather than reinvented. This is the one piece of Flywheel's
 * shader pipeline that is pure text and has nothing to do with OpenGL, which is why it can be
 * reimplemented in thirty lines instead of ported.
 *
 * <p>Includes are resolved once each. A file pulled in twice is pasted once, and a cycle ends
 * rather than recursing -- both matter because these files include each other freely, on the
 * assumption that something upstream is keeping track.
 */
public final class ShaderIncludes {
	private static final Pattern INCLUDE = Pattern.compile(
			"^\\s*#include\\s+\"([a-z0-9_.-]+):([a-z0-9_./-]+)\"\\s*$", Pattern.MULTILINE);

	private ShaderIncludes() {
	}

	/**
	 * @param id a Flywheel shader identifier, such as {@code create:instance/rotating.vert}
	 * @throws IOException when the file, or anything it includes, is not there
	 */
	public static String read(Identifier id) throws IOException {
		return read(id, new HashSet<>());
	}

	private static String read(Identifier id, Set<Identifier> seen) throws IOException {
		if (!seen.add(id)) {
			// Already pasted, or we are inside it. Either way, emitting it again would at best
			// redefine every function in it.
			return "";
		}

		String source = load(id);
		Matcher matcher = INCLUDE.matcher(source);
		StringBuilder out = new StringBuilder();

		while (matcher.find()) {
			Identifier included = Identifier.fromNamespaceAndPath(matcher.group(1), matcher.group(2));
			matcher.appendReplacement(out, Matcher.quoteReplacement(read(included, seen)));
		}
		matcher.appendTail(out);

		return out.toString();
	}

	private static String load(Identifier id) throws IOException {
		ResourceManager resources = Minecraft.getInstance()
				.getResourceManager();

		// Flywheel's own directory, not vanilla's shader one: assets/<ns>/flywheel/<path>.
		Identifier path = Identifier.fromNamespaceAndPath(id.getNamespace(),
				"flywheel/" + id.getPath());

		Optional<Resource> resource = resources.getResource(path);
		if (resource.isEmpty()) {
			throw new IOException("no such shader: " + path);
		}

		try (InputStream in = resource.get()
				.open()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
