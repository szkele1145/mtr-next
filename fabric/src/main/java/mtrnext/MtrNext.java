package mtrnext;

import net.fabricmc.api.ModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * MTR Next 主入口。
 * <p>
 * 定位：把 MTR 4.x 的两大特性带回 YMTR 3.x（MC 1.20.1）的独立 mixin 附属：
 * <ol>
 *   <li><b>轨道预览</b>——手持连接状态的轨道节点时，实时预览将要生成的轨道形状；</li>
 *   <li><b>自定义编组</b>——解析 4.0 格式车包（mtr_custom_resources.json 的 vehicles 段，
 *       含连接面语义 gangway/barrier/coupling），在车厂里可视化编排
 *       （头连头、重联等），编组数据挂到 Siding。
 *       <b>3.2 格式车包不接管</b>——识别 vehicles 段缺特征即跳过，YMTR 原样处理。</li>
 * </ol>
 */
public final class MtrNext implements ModInitializer {

	public static final String MOD_ID = "mtr-next";
	public static final Logger LOGGER = LogManager.getLogger("MTR-Next");

	@Override
	public void onInitialize() {
		LOGGER.info("MTR-Next initializing");
		// 服务端侧初始化：4.0 车包注册表预热（无行为，加载发生在客户端资源重载时）
	}
}
