package dev.engine_room.flywheel.backend.engine.blaze;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.backend.compute.BarrierScope;
import dev.engine_room.flywheel.backend.compute.Compute;
import dev.engine_room.flywheel.backend.compute.ComputeBackend;
import dev.engine_room.flywheel.backend.compute.ComputePass;
import dev.engine_room.flywheel.backend.compute.ComputePipeline;
import dev.engine_room.flywheel.backend.compute.FlwBufferUsage;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.math.MoreMath;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;

/**
 * Does an instance come back out of the GPU as what went in?
 *
 * <p>The highest-value test in this backend, and the cheapest. An instance is written by the real
 * {@code InstanceWriter}, uploaded, and unpacked by the real generated GLSL -- so the CPU's idea of
 * the layout and the shader's are compared against each other rather than both against a comment.
 * Offsets, sign extension, normalisation divisors and endianness all fail here, and all of them
 * fail invisibly anywhere else: wrong instance data does not crash or warn, it draws the right
 * number of things slightly wrong, and slightly wrong on a spinning cog looks like a physics bug.
 *
 * <p>{@link InstanceTypes#TRANSFORMED} is used because one type covers all three representation
 * families at once: normalised unsigned bytes (colour), signed shorts (overlay), unsigned shorts
 * (light) and a float matrix (pose). Each is read differently and each has its own way of being
 * wrong.
 *
 * <p>The values are chosen to be unforgiving. The overlay is negative, so a missing sign extension
 * shows; the light is above 255, so reading it a byte at a time shows; the matrix translation is
 * fractional and differs per axis, so a transposed or shifted read shows. Nothing is zero and
 * nothing equals its own index.
 */
public final class LayoutSelfTest {
	private static final long TIMEOUT_NS = 5_000_000_000L;

	private static final int RED = 10;
	private static final int GREEN = 20;
	private static final int BLUE = 30;
	private static final int ALPHA = 40;

	private static final int OVERLAY_X = -5;
	private static final int OVERLAY_Y = 7;

	private static final int LIGHT_X = 100;
	private static final int LIGHT_Y = 240;

	private static final float POSE_X = 1.5f;
	private static final float POSE_Y = -2.25f;
	private static final float POSE_Z = 3.75f;

	/** colour 4, overlay 2, light 2, translation 3. */
	private static final int RESULTS = 11;

	private LayoutSelfTest() {
	}

	public record Result(boolean passed, List<String> lines) {
	}

	public static Result run() {
		List<String> lines = new ArrayList<>();
		ComputeBackend gpu = Compute.backend();

		if (!gpu.available()) {
			lines.add("no compute backend, so the generated unpacking cannot be run");
			return new Result(false, lines);
		}

		InstanceType<TransformedInstance> type = InstanceTypes.TRANSFORMED;
		int stride = MoreMath.align16(type.layout()
				.byteSize());

		TransformedInstance instance = type.create(NO_HANDLE);
		instance.color((byte) RED, (byte) GREEN, (byte) BLUE, (byte) ALPHA);
		instance.overlay = (OVERLAY_Y << 16) | (OVERLAY_X & 0xFFFF);
		instance.light = (LIGHT_Y << 16) | LIGHT_X;
		instance.pose.identity()
				.translate(POSE_X, POSE_Y, POSE_Z);

		String source = shader(type, stride);

		var device = RenderSystem.getDevice();
		MemoryBlock block = MemoryBlock.malloc(stride);

		try (GpuBuffer instances = device.createBuffer(() -> "flywheel layout selftest instance",
				FlwBufferUsage.STORAGE | GpuBuffer.USAGE_COPY_DST, stride);
				GpuBuffer results = device.createBuffer(() -> "flywheel layout selftest results",
						FlwBufferUsage.STORAGE | GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
						(long) RESULTS * Float.BYTES)) {

			type.writer()
					.write(block.ptr(), instance);
			Staging.upload(instances.slice(),
					MemoryUtil.memByteBuffer(block.ptr(), stride));

			try (ComputePipeline pipeline = gpu.createPipeline(
					ComputePipeline.Description.of("layout selftest", source))) {

				if (pipeline == null) {
					lines.add("the generated unpacking would not compile. Source:\n" + source);
					return new Result(false, lines);
				}

				try (ComputePass pass = gpu.beginPass("layout selftest")) {
					pass.setPipeline(pipeline);
					pass.bindStorageBuffer(0, instances.slice());
					pass.bindStorageBuffer(1, results.slice());
					pass.dispatch(1, 1, 1);
					pass.barrier(BarrierScope.STORAGE | BarrierScope.CLIENT_MAPPED_BUFFER);
				}
			}

			gpu.flush();
			if (!gpu.awaitGpu(TIMEOUT_NS)) {
				lines.add("the GPU did not signal within " + (TIMEOUT_NS / 1_000_000) + "ms");
				return new Result(false, lines);
			}

			try (GpuBufferSlice.MappedView mapped = results.map(true, false)) {
				FloatBuffer got = mapped.data()
						.asFloatBuffer();

				float[] expected = {
						RED / 255.0f, GREEN / 255.0f, BLUE / 255.0f, ALPHA / 255.0f,
						OVERLAY_X, OVERLAY_Y,
						LIGHT_X, LIGHT_Y,
						POSE_X, POSE_Y, POSE_Z,
				};
				String[] names = {
						"color.r", "color.g", "color.b", "color.a",
						"overlay.x", "overlay.y",
						"light.x", "light.y",
						"pose[3].x", "pose[3].y", "pose[3].z",
				};

				for (int i = 0; i < RESULTS; i++) {
					float actual = got.get(i);
					if (Math.abs(actual - expected[i]) > 1.0e-4f) {
						lines.add("MISMATCH " + names[i] + ": expected " + expected[i] + ", got "
								+ actual);
						return new Result(false, lines);
					}
				}
			}

			lines.add("all " + RESULTS + " fields survived the round trip through the GPU");
		} catch (RuntimeException e) {
			lines.add("threw: " + e);
			return new Result(false, lines);
		} finally {
			block.free();
		}

		return new Result(true, lines);
	}

	private static String shader(InstanceType<?> type, int stride) {
		return """
				layout(local_size_x = 1) in;

				layout(std430, FLW_SET(0) binding = 0) readonly buffer Instances {
					uint _flw_instances[];
				};

				layout(std430, FLW_SET(0) binding = 1) writeonly buffer Results {
					float result[];
				};

				"""
				+ InstanceGlsl.struct(type.layout())
				+ "\n"
				+ InstanceGlsl.storageAccessor(stride)
				+ "\n"
				+ InstanceGlsl.unpack(type.layout())
				+ """

						void main() {
							FlwInstance i = _flw_unpackInstance(0u);

							result[0] = i.color.r;
							result[1] = i.color.g;
							result[2] = i.color.b;
							result[3] = i.color.a;
							result[4] = float(i.overlay.x);
							result[5] = float(i.overlay.y);
							result[6] = i.light.x;
							result[7] = i.light.y;
							// The translation column, which is where a transposed read shows up.
							result[8] = i.pose[3].x;
							result[9] = i.pose[3].y;
							result[10] = i.pose[3].z;
						}
						""";
	}

	/** The layout does not care about handles, and nothing here ever asks this one anything. */
	private static final InstanceHandle NO_HANDLE = new InstanceHandle() {
		@Override
		public void setChanged() {
		}

		@Override
		public void setDeleted() {
		}

		@Override
		public void setVisible(boolean visible) {
		}

		@Override
		public boolean isVisible() {
			return true;
		}
	};
}
