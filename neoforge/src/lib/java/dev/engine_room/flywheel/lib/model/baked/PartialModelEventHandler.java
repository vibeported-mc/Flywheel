package dev.engine_room.flywheel.lib.model.baked;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;

@ApiStatus.Internal
public final class PartialModelEventHandler {
	// Minecraft 26.2 replaced the "additional models" map keyed by ModelResourceLocation with
	// NeoForge's typed standalone models, so each partial needs a key to look its baked model up by.
	private static final Map<Identifier, StandaloneModelKey<BlockStateModel>> KEYS = new ConcurrentHashMap<>();

	private PartialModelEventHandler() {
	}

	public static void onRegisterStandalone(ModelEvent.RegisterStandalone event) {
		for (Identifier modelLocation : PartialModel.ALL.keySet()) {
			event.register(keyOf(modelLocation), SimpleUnbakedStandaloneModel.blockStateModel(modelLocation));
		}
	}

	public static void onBakingCompleted(ModelEvent.BakingCompleted event) {
		PartialModel.populateOnInit = true;
		ModelManager modelManager = event.getModelManager();

		for (PartialModel partial : PartialModel.ALL.values()) {
			partial.bakedModel = getBakedModel(modelManager, partial.modelLocation());
		}
	}

	@Nullable
	public static BlockStateModel getBakedModel(ModelManager modelManager, Identifier location) {
		return modelManager.getStandaloneModel(keyOf(location));
	}

	private static StandaloneModelKey<BlockStateModel> keyOf(Identifier modelLocation) {
		return KEYS.computeIfAbsent(modelLocation, location -> new StandaloneModelKey<>(location::toString));
	}
}
