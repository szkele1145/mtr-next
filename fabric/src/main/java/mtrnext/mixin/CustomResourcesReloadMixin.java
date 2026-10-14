package mtrnext.mixin;

import mtrnext.MtrNext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * [4.0 车包重载钩子] 挂在 YMTR 的 CustomResources.reload 之后。
 * <p>
 * javap 实证：{@code public static void reload(class_3300)}——
 * class_3300 = ResourceManager（mojmap）。
 * <p>
 * 与 YMTR 原生 3.x 车包同资源重载时机解析 4.0 车包，保证：
 * <ul>
 *   <li>资源包增删（含 /reload 与进存档加载）自动同步；</li>
 *   <li>3.2 车包先由 YMTR 原生处理（我们 RETURN 注入不干扰其流程）；</li>
 *   <li>bbmodel 缓存在此一并失效（下个渲染帧重新 bake）。</li>
 * </ul>
 */
@Mixin(targets = "mtr.client.CustomResources", remap = false)
public abstract class CustomResourcesReloadMixin {

	@Inject(method = "reload", at = @At("RETURN"))
	private static void mtrnext$reload4xPacks(net.minecraft.server.packs.resources.ResourceManager manager, CallbackInfo ci) {
		try {
			mtrnext.client.render.BbModelLoader.invalidate();
			mtrnext.pack.VehiclePackLoader.reload(manager);
			// [0.5] 4.0 车辆注册进 YMTR 车型表（发车/渲染可解析 family 名）
			mtrnext.formation.TrainTypeBridge.registerAll4xVehicles();
			if (mtrnext.pack.VehiclePackLoader.has4xVehicles()) {
				MtrNext.LOGGER.info("MTR-Next: 4.0-format vehicles active alongside native 3.x resources");
			}
		} catch (final Throwable t) {
			MtrNext.LOGGER.error("4.x pack reload failed", t);
		}
	}
}
