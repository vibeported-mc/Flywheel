package dev.engine_room.flywheel.backend.engine.blaze;

import dev.engine_room.flywheel.api.layout.ElementType;
import dev.engine_room.flywheel.api.layout.FloatRepr;
import dev.engine_room.flywheel.api.layout.IntegerRepr;
import dev.engine_room.flywheel.api.layout.Layout;
import dev.engine_room.flywheel.api.layout.MatrixElementType;
import dev.engine_room.flywheel.api.layout.ScalarElementType;
import dev.engine_room.flywheel.api.layout.UnsignedIntegerRepr;
import dev.engine_room.flywheel.api.layout.ValueRepr;
import dev.engine_room.flywheel.api.layout.VectorElementType;

/**
 * Turns an instance {@link Layout} into the GLSL that reads it.
 *
 * <p>An instance type declares its fields once, in Java, and three things have to agree about them
 * afterwards: the stride the CPU writes at, the struct a mod's shader body names, and the code that
 * gets from bytes to that struct. Writing any of them by hand means saying the same thing three
 * times, and a field inserted in the middle without updating all three does not fail -- it draws
 * the right number of things with quietly wrong data. So all of it is derived from the layout.
 *
 * <h2>Why unpacking by hand at all</h2>
 *
 * <p>The OpenGL backend does not need this: instance data is a vertex buffer there, and
 * {@code glVertexAttribPointer} turns bytes into attributes in the driver. That is exactly what is
 * unavailable here. Blaze3D has no storage buffers, so per-instance data arrives as a
 * <em>uniform texel buffer</em> -- a flat array of {@code uvec4} -- and the shader gets raw words.
 * Everything below is the cost of that substitution, and it is the price of working on Vulkan at
 * all.
 */
public final class InstanceGlsl {
	/** One texel is four 32-bit words. */
	public static final int WORDS_PER_TEXEL = 4;

	private InstanceGlsl() {
	}

	/**
	 * The struct a mod's {@code flw_instanceVertex} body receives.
	 *
	 * <p>Field names come straight from the layout, because that is the contract a mod wrote
	 * against: Create's rotating shader says {@code instance.axis} and {@code instance.speed}, and
	 * those names exist here only because they were declared there.
	 */
	public static String struct(Layout layout) {
		StringBuilder out = new StringBuilder("struct FlwInstance {\n");

		for (Layout.Element element : layout.elements()) {
			out.append('\t')
					.append(glslType(element.type()))
					.append(' ')
					.append(element.name())
					.append(";\n");
		}

		return out.append("};\n")
				.toString();
	}

	/**
	 * How the draw path reaches a word: a uniform texel buffer of {@code uvec4}.
	 *
	 * @param stride the instance stride in bytes, which the caller has already aligned to a texel;
	 *               an unaligned stride would start instances partway through a texel and read
	 *               every field from the wrong place
	 */
	public static String texelAccessor(int stride) {
		if (stride % (WORDS_PER_TEXEL * Integer.BYTES) != 0) {
			throw new IllegalArgumentException(
					"instance stride " + stride + " is not a whole number of texels");
		}

		int texelsPerInstance = stride / (WORDS_PER_TEXEL * Integer.BYTES);

		return "uint _flw_word(uint instance, uint index) {\n"
				+ "\tuint texel = instance * " + texelsPerInstance + "u + (index >> 2u);\n"
				+ "\treturn texelFetch(_flw_instances, int(texel))[index & 3u];\n"
				+ "}\n";
	}

	/**
	 * How the cull path reaches one: a storage buffer of flat {@code uint}.
	 *
	 * <p>The same fields read two different ways, because the two passes are handed different kinds
	 * of buffer -- compute can have a storage buffer and a draw cannot. Both go through
	 * {@code _flw_word}, so the unpacking itself is generated once and the two cannot drift apart.
	 */
	public static String storageAccessor(int stride) {
		if (stride % Integer.BYTES != 0) {
			throw new IllegalArgumentException("instance stride " + stride + " is not whole words");
		}

		return "uint _flw_word(uint instance, uint index) {\n"
				+ "\treturn _flw_instances[instance * " + (stride / Integer.BYTES) + "u + index];\n"
				+ "}\n";
	}

	/** The function that builds a {@code FlwInstance}, using whichever accessor is in scope. */
	public static String unpack(Layout layout) {
		StringBuilder out = new StringBuilder();
		out.append("FlwInstance _flw_unpackInstance(uint instance) {\n")
				.append("\tFlwInstance instance_;\n");

		for (Layout.Element element : layout.elements()) {
			out.append('\t')
					.append("instance_.")
					.append(element.name())
					.append(" = ")
					.append(read(element.type(), element.byteOffset()))
					.append(";\n");
		}

		return out.append("\treturn instance_;\n")
				.append("}\n")
				.toString();
	}

	private static String glslType(ElementType type) {
		if (type instanceof ScalarElementType scalar) {
			return scalarType(scalar.repr());
		}
		if (type instanceof VectorElementType vector) {
			return vectorPrefix(vector.repr()) + "vec" + vector.size();
		}
		if (type instanceof MatrixElementType matrix) {
			return matrix.rows() == matrix.columns()
					? "mat" + matrix.columns()
					: "mat" + matrix.columns() + "x" + matrix.rows();
		}
		throw new IllegalArgumentException("no GLSL type for " + type);
	}

	private static String scalarType(ValueRepr repr) {
		if (repr instanceof IntegerRepr) {
			return "int";
		}
		if (repr instanceof UnsignedIntegerRepr) {
			return "uint";
		}
		return "float";
	}

	private static String vectorPrefix(ValueRepr repr) {
		if (repr instanceof IntegerRepr) {
			return "i";
		}
		if (repr instanceof UnsignedIntegerRepr) {
			return "u";
		}
		return "";
	}

	private static String read(ElementType type, int byteOffset) {
		if (type instanceof ScalarElementType scalar) {
			return component(scalar.repr(), byteOffset);
		}

		if (type instanceof VectorElementType vector) {
			StringBuilder out = new StringBuilder(glslType(type)).append('(');
			int size = vector.repr()
					.byteSize();

			for (int i = 0; i < vector.size(); i++) {
				if (i > 0) {
					out.append(", ");
				}
				out.append(component(vector.repr(), byteOffset + i * size));
			}

			return out.append(')')
					.toString();
		}

		if (type instanceof MatrixElementType matrix) {
			// Column major, which is what GLSL's constructor takes and what the CPU writer emits --
			// JOML's Matrix4f.get writes columns in order. Transposing here would be invisible on an
			// identity or a pure scale and wrong on every rotation.
			StringBuilder out = new StringBuilder(glslType(type)).append('(');
			int size = matrix.repr()
					.byteSize();

			for (int column = 0; column < matrix.columns(); column++) {
				for (int row = 0; row < matrix.rows(); row++) {
					if (column > 0 || row > 0) {
						out.append(", ");
					}
					out.append(component(matrix.repr(),
							byteOffset + (column * matrix.rows() + row) * size));
				}
			}

			return out.append(')')
					.toString();
		}

		throw new IllegalArgumentException("cannot unpack " + type);
	}

	/**
	 * One value, read out of whichever word holds it.
	 *
	 * <p>A field is not necessarily word-aligned: a three-byte normalized vector can start at any
	 * offset the layout put it at, so each component finds its own word and shifts within it.
	 */
	private static String component(ValueRepr repr, int byteOffset) {
		int word = byteOffset >> 2;
		int shift = (byteOffset & 3) * 8;
		String raw = "_flw_word(instance, " + word + "u)";

		return switch (repr) {
			case FloatRepr floatRepr -> floatComponent(floatRepr, raw, shift);
			case IntegerRepr integerRepr -> switch (integerRepr) {
				case BYTE -> signExtend(raw, shift, 8);
				case SHORT -> signExtend(raw, shift, 16);
				case INT -> "int(" + raw + ")";
			};
			case UnsignedIntegerRepr unsigned -> switch (unsigned) {
				case UNSIGNED_BYTE -> mask(raw, shift, 0xFF);
				case UNSIGNED_SHORT -> mask(raw, shift, 0xFFFF);
				case UNSIGNED_INT -> raw;
			};
			default -> throw new IllegalArgumentException("unknown representation " + repr);
		};
	}

	private static String floatComponent(FloatRepr repr, String raw, int shift) {
		return switch (repr) {
			case FLOAT -> "uintBitsToFloat(" + raw + ")";
			case BYTE -> "float(" + signExtend(raw, shift, 8) + ")";
			case NORMALIZED_BYTE ->
				// Divided by 127 rather than 128, and clamped: that is how a signed normalized byte
				// is defined, and -128 would otherwise come out below -1.
					"max(float(" + signExtend(raw, shift, 8) + ") / 127.0, -1.0)";
			case UNSIGNED_BYTE -> "float(" + mask(raw, shift, 0xFF) + ")";
			case NORMALIZED_UNSIGNED_BYTE -> "float(" + mask(raw, shift, 0xFF) + ") / 255.0";
			case SHORT -> "float(" + signExtend(raw, shift, 16) + ")";
			case NORMALIZED_SHORT -> "max(float(" + signExtend(raw, shift, 16) + ") / 32767.0, -1.0)";
			case UNSIGNED_SHORT -> "float(" + mask(raw, shift, 0xFFFF) + ")";
			case NORMALIZED_UNSIGNED_SHORT -> "float(" + mask(raw, shift, 0xFFFF) + ") / 65535.0";
			case INT -> "float(int(" + raw + "))";
			case UNSIGNED_INT -> "float(" + raw + ")";
			case NORMALIZED_INT -> "max(float(int(" + raw + ")) / 2147483647.0, -1.0)";
			case NORMALIZED_UNSIGNED_INT -> "float(" + raw + ") / 4294967295.0";
		};
	}

	private static String mask(String raw, int shift, int mask) {
		String shifted = shift == 0 ? raw : "(" + raw + " >> " + shift + "u)";
		return shifted + " & " + Integer.toHexString(mask)
				.toUpperCase()
				.transform(hex -> "0x" + hex + "u");
	}

	/** Sign extension by shifting up to the top of the word and arithmetic-shifting back down. */
	private static String signExtend(String raw, int shift, int bits) {
		int up = 32 - bits - shift;
		return "(int(" + raw + ") << " + up + " >> " + (32 - bits) + ")";
	}
}
