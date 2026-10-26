package youtube.varmite.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

/** Mod Menu's "Configure" button for OptiGen. Without YACL the button is simply absent. */
public final class OptiGenModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		if (!FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3")) {
			return parent -> null;
		}
		return OptiGenConfigScreen::create;
	}
}
