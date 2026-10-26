package youtube.varmite.mixin;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import youtube.varmite.world.OptiChunkGenerator;
import youtube.varmite.world.parallel.ParallelSteps;

/** Moves OptiGen levels' serial chunk steps onto the OptiGen worker pool. Other generators are untouched. */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksMixin {
	@Inject(method = "generateFeatures", at = @At("HEAD"), cancellable = true)
	private static void optigen$parallelFeatures(
		WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
		CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
	) {
		if (ParallelSteps.active() && context.generator() instanceof OptiChunkGenerator<?> generator) {
			cir.setReturnValue(ParallelSteps.features(generator, context, step, chunks, chunk));
		}
	}

	@Inject(method = "initializeLight", at = @At("HEAD"), cancellable = true)
	private static void optigen$parallelInitializeLight(
		WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
		CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
	) {
		if (ParallelSteps.active() && context.generator() instanceof OptiChunkGenerator<?> generator) {
			cir.setReturnValue(ParallelSteps.initializeLight(generator, context, chunk));
		}
	}

	@Inject(method = "generateSpawn", at = @At("HEAD"), cancellable = true)
	private static void optigen$parallelSpawn(
		WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
		CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
	) {
		if (ParallelSteps.active() && context.generator() instanceof OptiChunkGenerator<?> generator) {
			cir.setReturnValue(ParallelSteps.spawn(generator, context, step, chunks, chunk));
		}
	}
}
