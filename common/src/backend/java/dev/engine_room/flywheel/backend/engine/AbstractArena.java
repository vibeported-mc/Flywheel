package dev.engine_room.flywheel.backend.engine;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

public abstract class AbstractArena {
	protected final long elementSizeBytes;
	// List of free indices.
	private final IntList freeStack = new IntArrayList();
	// Monotonic index, generally represents the size of the arena.
	private int top = 0;

	/**
	 * How many bytes one element occupies.
	 *
	 * <p>Not a constant to its readers, and that is the point. Sable rewrites the argument this is
	 * constructed with so an embedded environment carries its lighting scene alongside the pose, so
	 * anything that copies a record out of here has to ask rather than assume.
	 */
	public long elementSize() {
		return elementSizeBytes;
	}

	public AbstractArena(long elementSizeBytes) {
		this.elementSizeBytes = elementSizeBytes;
	}

	public int alloc() {
		// First re-use freed elements.
		if (!freeStack.isEmpty()) {
			return freeStack.removeInt(freeStack.size() - 1);
		}

		// Make sure there's room to increment top.
		if (top * elementSizeBytes >= byteCapacity()) {
			grow();
		}

		// Return the top index and increment.
		return top++;
	}

	public void free(int i) {
		// That's it! Now pls don't try to use it.
		freeStack.add(i);
	}

	public long byteOffsetOf(int i) {
		return i * elementSizeBytes;
	}

	public int capacity() {
		return top;
	}

	public int occupancy() {
		return top - freeStack.size();
	}

	public abstract long byteCapacity();

	protected abstract void grow();
}
