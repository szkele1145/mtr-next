package mtrnext.client;

import mtrnext.MtrNext;
import net.fabricmc.api.ClientModInitializer;

/**
 * MTR Next 客户端入口。
 * <p>
 * 客户端职责：
 * <ul>
 *   <li>资源重载钩子——扫描资源包找 4.0 格式特征（mtr_custom_resources.json
 *       的 vehicles 段），命中则由 {@link mtrnext.pack.VehiclePackLoader} 解析；</li>
 *   <li>轨道预览渲染注册；</li>
 *   <li>编组编辑屏的打开入口。</li>
 * </ul>
 */
public final class MtrNextClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		MtrNext.LOGGER.info("MTR-Next client initializing");
		// 预览渲染与车包加载经 mixin/事件挂载，此处无直接注册
	}
}
