package dev.engine_room.flywheel.backend.engine.uniform;

import org.joml.Vector3f;

import dev.engine_room.flywheel.api.backend.RenderContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

public final class LevelUniforms extends UniformWriter {
	private static final int SIZE = 16 * 4 + 4 * 12;
	static final UniformBuffer BUFFER = new UniformBuffer(Uniforms.LEVEL_INDEX, SIZE);

	public static final Vector3f LIGHT0_DIRECTION = new Vector3f();
	public static final Vector3f LIGHT1_DIRECTION = new Vector3f();

	private LevelUniforms() {
	}

	public static void update(RenderContext context) {
		long ptr = BUFFER.ptr();

		ClientLevel level = context.level();
		LevelRenderState levelRenderState = context.levelRenderState();
		SkyRenderState skyRenderState = levelRenderState.skyRenderState;
		float partialTick = context.partialTick();

		// The sky and cloud colors are packed ARGB ints on the render state in 26.2, rather than
		// being computed from the level on demand.
		int skyColor = skyRenderState.skyColor;
		int cloudColor = levelRenderState.cloudColor;
		ptr = writeVec4(ptr, ARGB.redFloat(skyColor), ARGB.greenFloat(skyColor), ARGB.blueFloat(skyColor), 1f);
		ptr = writeVec4(ptr, ARGB.redFloat(cloudColor), ARGB.greenFloat(cloudColor), ARGB.blueFloat(cloudColor), 1f);

		ptr = writeVec3(ptr, LIGHT0_DIRECTION);
		ptr = writeVec3(ptr, LIGHT1_DIRECTION);

		long dayTime = level.getOverworldClockTime();
		long levelDay = dayTime / 24000L;
		float timeOfDay = (float) (dayTime - levelDay * 24000L) / 24000f;
		ptr = writeInt(ptr, (int) (levelDay % 0x7FFFFFFFL));
		ptr = writeFloat(ptr, timeOfDay);

		ptr = writeInt(ptr, level.dimensionType()
				.hasSkyLight() ? 1 : 0);

		float sunAngle = skyRenderState.sunAngle;
		ptr = writeFloat(ptr, sunAngle);

		ptr = writeFloat(ptr, DimensionType.MOON_BRIGHTNESS_PER_PHASE[skyRenderState.moonPhase.index()]);
		ptr = writeInt(ptr, skyRenderState.moonPhase.index());

		ptr = writeInt(ptr, level.isRaining() ? 1 : 0);
		ptr = writeFloat(ptr, level.getRainLevel(partialTick));
		ptr = writeInt(ptr, level.isThundering() ? 1 : 0);
		ptr = writeFloat(ptr, level.getThunderLevel(partialTick));

		ptr = writeFloat(ptr, skyBrightness(level, sunAngle, partialTick));

		// DimensionSpecialEffects#constantAmbientLight became the dimension's cardinal light type:
		// the nether-style table is the flat one that used to be flagged this way.
		ptr = writeInt(ptr, level.dimensionType()
				.cardinalLightType() != CardinalLighting.Type.DEFAULT ? 1 : 0);

		// TODO: use defines for custom dimension ids
        int dimensionId;
        ResourceKey<Level> dimension = level.dimension();
        if (Level.OVERWORLD.equals(dimension)) {
            dimensionId = 0;
        } else if (Level.NETHER.equals(dimension)) {
            dimensionId = 1;
        } else if (Level.END.equals(dimension)) {
            dimensionId = 2;
        } else {
            dimensionId = -1;
        }
        ptr = writeInt(ptr, dimensionId);

		BUFFER.markDirty();
    }

	/**
	 * {@code Level#getSkyDarken(float)} used to return the ambient sky brightness; the surviving
	 * no-argument overload returns the raw light subtraction instead. This reproduces the old
	 * calculation, using the sun angle already on the render state in place of the removed
	 * {@code getTimeOfDay} (the two only differ by a factor of 2*pi).
	 */
	private static float skyBrightness(ClientLevel level, float sunAngle, float partialTick) {
		float brightness = 1.0f - (Mth.cos(sunAngle) * 2.0f + 0.2f);
		brightness = Mth.clamp(brightness, 0.0f, 1.0f);
		brightness = 1.0f - brightness;
		brightness *= 1.0f - level.getRainLevel(partialTick) * 5.0f / 16.0f;
		brightness *= 1.0f - level.getThunderLevel(partialTick) * 5.0f / 16.0f;
		return brightness * 0.8f + 0.2f;
	}
}
