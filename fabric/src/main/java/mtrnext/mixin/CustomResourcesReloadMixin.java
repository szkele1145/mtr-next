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
 * [0.35.1] 目标类改用类字面量 {@code value = mtr.client.CustomResources.class}
 * （原 {@code targets = "..."} 字符串）。两者运行时等价，但 AP 对 targets 字符串
 * 目标只给"is public and should be specified in value"的建议性警告且**不解析
 * 目标**——注入点写错也编译通过、运行时静默不命中。改用类引用后 AP 真正解析
 * YMTR 目标类并校验注入点，把"永不命中"从静默失效提前成编译错误。
 * <p>
 * 目标类经 javap 实证为 public class（YMTR 3.6.4.2 发布 jar）；
 * {@code remap = false} 保留——mtr.* 是 YMTR 自有包名，非 net/minecraft
 * 前缀，不被 intermediary 重映射，两空间一致。
 */
@Mixin(value = mtr.client.CustomResources.class, remap = false)
public abstract class CustomResourcesReloadMixin {

	@Inject(method = "reload", at = @At("RETURN"))
	private static void mtrnext$reload4xPacks(net.minecraft.server.packs.resources.ResourceManager manager, CallbackInfo ci) {
		try {
			mtrnext.client.render.BbModelLoader.invalidate();
			mtrnext.client.render.Properties4xLoader.invalidate();
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
