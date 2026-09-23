package dev.engine_room.flywheel.backend.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.blaze3d.shaders.ShaderType;

import dev.engine_room.flywheel.backend.engine.blaze.GeneratedShaders;
import net.minecraft.resources.Identifier;

/**
 * Lets Flywheel answer for its own shaders.
 *
 * <p>A {@code RenderPipeline} names its shaders by {@link Identifier} and Minecraft resolves them
 * out of the resource packs. Flywheel's cannot live there: what a vertex shader has to do depends
 * on the instance type being drawn, so the set of them is a combination rather than a list. The
 * OpenGL backend sidesteps this by compiling its own programs and never asking Minecraft, which is
 * the freedom the Vulkan backend does not have.
 *
 * <p>This is the inner {@code CompilationCache} rather than {@code ShaderManager.getShader}
 * deliberately: {@code getShader} delegates here, and so does the device's own source lookup, so
 * one injection covers both. Injecting into the outer method would leave pipeline precompilation
 * resolving through a path this never sees.
 *
 * <p>Everything Flywheel did not generate falls through untouched, and the check is a namespace
 * compare before any map lookup -- this runs for every shader in the game.
 */
@Mixin(targets = "net.minecraft.client.renderer.ShaderManager$CompilationCache")
public class ShaderManagerCompilationCacheMixin {
	@Inject(method = "getShaderSource", at = @At("HEAD"), cancellable = true)
	private void flywheel$generatedSources(Identifier id, ShaderType type,
			CallbackInfoReturnable<String> callback) {
		String generated = GeneratedShaders.get(id, type);

		if (generated != null) {
			callback.setReturnValue(generated);
		}
	}
}
