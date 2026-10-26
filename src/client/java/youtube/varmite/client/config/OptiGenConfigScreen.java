package youtube.varmite.client.config;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import youtube.varmite.OptiGen;
import youtube.varmite.config.OptiGenConfig;

/** The YACL screen for {@link OptiGenConfig}. Only class-loaded when YACL is installed. */
public final class OptiGenConfigScreen {
	private static final String KEY = "config.optigen.";

	private OptiGenConfigScreen() {
	}

	public static Screen create(Screen parent) {
		OptiGenConfig config = OptiGenConfig.get();
		OptiGenConfig defaults = new OptiGenConfig();
		int cores = Runtime.getRuntime().availableProcessors();
		boolean dhLoaded = FabricLoader.getInstance().isModLoaded("distanthorizons");

		return YetAnotherConfigLib.createBuilder()
			.title(Component.translatable(KEY + "title"))
			.category(ConfigCategory.createBuilder()
				.name(Component.translatable(KEY + "category.performance"))
				.group(OptionGroup.createBuilder()
					.name(Component.translatable(KEY + "group.threads"))
					.description(OptionDescription.of(Component.translatable(KEY + "group.threads.description")))
					.option(toggle("parallelGeneration", defaults.parallelGeneration, () -> config.parallelGeneration, v -> config.parallelGeneration = v, true))
					.option(Option.<Integer>createBuilder()
						.name(Component.translatable(KEY + "workerThreads"))
						.description(describe("workerThreads", true))
						.binding(defaults.workerThreads, () -> config.workerThreads, v -> config.workerThreads = v)
						.controller(option -> IntegerSliderControllerBuilder.create(option)
							.range(0, cores)
							.step(1)
							.formatValue(v -> v == 0
								? Component.translatable(KEY + "workerThreads.auto", cores)
								: Component.translatable(KEY + "workerThreads.count", v)))
						.build())
					.option(toggle("lowerWorkerPriority", defaults.lowerWorkerPriority, () -> config.lowerWorkerPriority, v -> config.lowerWorkerPriority = v, true))
					.build())
				.build())
			.category(ConfigCategory.createBuilder()
				.name(Component.translatable(KEY + "category.worldgen"))
				.group(OptionGroup.createBuilder()
					.name(Component.translatable(KEY + "group.decoration"))
					.description(OptionDescription.of(Component.translatable(KEY + "group.decoration.description")))
					.option(toggle("trimOres", defaults.trimOres, () -> config.trimOres, v -> config.trimOres = v, true))
					.option(toggle("skipUndergroundDecoration", defaults.skipUndergroundDecoration, () -> config.skipUndergroundDecoration, v -> config.skipUndergroundDecoration = v, true))
					.build())
				.build())
			.category(ConfigCategory.createBuilder()
				.name(Component.translatable(KEY + "category.dh"))
				.tooltip(Component.translatable(dhLoaded ? KEY + "category.dh.tooltip" : KEY + "category.dh.missing"))
				.option(available(toggle("dhLodGenerator", defaults.dhLodGenerator, () -> config.dhLodGenerator, v -> config.dhLodGenerator = v, true), dhLoaded))
				.option(available(toggle("dhUseAllCores", defaults.dhUseAllCores, () -> config.dhUseAllCores, v -> config.dhUseAllCores = v, true), dhLoaded))
				.option(available(toggle("lodTreeCanopy", defaults.lodTreeCanopy, () -> config.lodTreeCanopy, v -> config.lodTreeCanopy = v, false), dhLoaded))
				.option(available(Option.<Integer>createBuilder()
					.name(Component.translatable(KEY + "dhMaxRenderDistance"))
					.description(OptionDescription.of(
						Component.translatable(KEY + "dhMaxRenderDistance.description"),
						Component.empty(),
						Component.translatable(KEY + "dhMaxRenderDistance.warning").withStyle(ChatFormatting.GOLD)))
					.binding(defaults.dhMaxRenderDistance, () -> config.dhMaxRenderDistance, v -> config.dhMaxRenderDistance = v)
					.controller(option -> IntegerSliderControllerBuilder.create(option)
						.range(OptiGenConfig.DH_STOCK_MAX_RENDER_DISTANCE, OptiGenConfig.DH_RENDER_DISTANCE_LIMIT)
						.step(1024)
						.formatValue(v -> Component.translatable(KEY + "dhMaxRenderDistance.value", v, String.format("%,d", v * 16))))
					.build(), dhLoaded))
				.build())
			.save(() -> {
				OptiGenConfig.save();
				OptiGen.applyLiveSettings();
			})
			.build()
			.generateScreen(parent);
	}

	private static Option<Boolean> toggle(String key, boolean defaultValue, Supplier<Boolean> getter, Consumer<Boolean> setter, boolean onWorldLoad) {
		return Option.<Boolean>createBuilder()
			.name(Component.translatable(KEY + key))
			.description(describe(key, onWorldLoad))
			.binding(defaultValue, getter, setter)
			.controller(option -> BooleanControllerBuilder.create(option).onOffFormatter().coloured(true))
			.build();
	}

	private static <T> Option<T> available(Option<T> option, boolean available) {
		option.setAvailable(available);
		return option;
	}

	private static OptionDescription describe(String key, boolean onWorldLoad) {
		Component text = Component.translatable(KEY + key + ".description");
		if (onWorldLoad) {
			return OptionDescription.of(text, Component.empty(), Component.translatable(KEY + "appliesOnWorldLoad").withStyle(ChatFormatting.YELLOW));
		}
		return OptionDescription.of(text);
	}
}
