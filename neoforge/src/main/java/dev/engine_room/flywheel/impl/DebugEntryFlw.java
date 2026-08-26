package dev.engine_room.flywheel.impl;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import dev.engine_room.flywheel.api.Flywheel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Minecraft 26.2 replaced the free-form debug text lines that
 * {@code CustomizeGuiOverlayEvent.DebugText} appended to with registered debug screen entries.
 */
public final class DebugEntryFlw implements DebugScreenEntry {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(Flywheel.ID, "debug_info");

	private static final Identifier GROUP = Identifier.fromNamespaceAndPath(Flywheel.ID, "group");

	@Override
	public void display(DebugScreenDisplayer displayer, @Nullable Level serverOrClientLevel, @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
		List<String> systemInfo = new ArrayList<>();

		FlwDebugInfo.addDebugInfo(Minecraft.getInstance(), systemInfo);

		displayer.addToGroup(GROUP, systemInfo);
	}
}
