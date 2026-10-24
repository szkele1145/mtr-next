package mtrnext.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtrnext.MtrNext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MTR Next 客户端入口。
 *
 * <p>客户端职责：
 * <ul>
 *   <li>4.0 车包解析（资源重载钩子，见 {@code CustomResourcesReloadMixin}）；</li>
 *   <li>编组编辑屏的打开入口。</li>
 * </ul>
 *
 * <p><b>★ [0.40.9] 轨道预览不再注册在 {@code WorldRenderEvents} 上</b>：
 * <ul>
 *   <li>{@code AFTER_ENTITIES}/{@code LAST} 的 {@code ctx.consumers()} 可能为 null；</li>
 *   <li>用 {@code MultiBufferSource.immediate(Tesselator...)} 会 flush MC 自己的批次
 *       ⇒ 整个世界渲染异常（真机：车辆与预览全部消失）。</li>
 * </ul>
 * ⇒ 改回由 {@code RenderTrainsPreviewMixin} 在 MTR 渲染末尾调用，
 * 使用自持 Tesselator + camera-relative 坐标（0.38.x 已验证「能看到、位置对」）。
 */
public final class MtrNextClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		MtrNext.LOGGER.info("MTR 4car to 3 client initializing");
		// 预览渲染由 RenderTrainsPreviewMixin 驱动（见该类注释）
		MtrNext.LOGGER.info("MTR 4car to 3: 4.0 vehicle pack support");


		// [0.43.3] 进世界后把持久化编组自动同步到服务端 Siding
		mtrnext.formation.FormationAutoSync.register();
		// ★ [0.57.0] 编组界面自测驱动（仅 -Dmtrnext.selftest=true 时注册，正常游玩零影响）
		mtrnext.screen.FormationSelfTest.register();
		// ★ [0.56.0] 把 YMTR 仪表盘的**真实地址**写进日志。
		//
		//   用户反馈「仪表盘都打不开了」，排查结论是**端口认知错位**而不是服务没起：
		//     · YMTR 的 dashboard 后端（含 /api/*）监听的是
		//       run/config/mtr_web_dashboard.json 里的 port（本工程 = 8890）；
		//     · 8888（run/config/mtr_webserver_port.txt）是 MTR 自己的静态 web server，
		//       只在**进入世界**后由集成服务端启动，并且**没有** /api/* 路由
		//       （实测 http://127.0.0.1:8888/api/status → 404）。
		//   ⇒ 在标题界面打开 8888 必然「打不开」；本行日志让玩家一眼看到该点哪个地址。
		logDashboardUrl();
	}

	private static void logDashboardUrl() {
		try {
			final Path cfg = FabricLoader.getInstance().getConfigDir().resolve("mtr_web_dashboard.json");
			if (!Files.exists(cfg)) {
				MtrNext.LOGGER.info("MTR 4car to 3: 未找到 YMTR 仪表盘配置（{}），跳过地址提示", cfg);
				return;
			}
			try (final Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
				final JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
				final int port = o.has("port") && o.get("port").isJsonPrimitive() ? o.get("port").getAsInt() : 8890;
				MtrNext.LOGGER.info("MTR-Next: YMTR 仪表盘 = http://127.0.0.1:{}/ （来自 config/mtr_web_dashboard.json；"
						+ "8888 是 MTR 静态 web server，只在进世界后可用且没有 /api/*）", port);
			}
		} catch (final Throwable t) {
			MtrNext.LOGGER.warn("MTR 4car to 3: 读取仪表盘端口失败（不影响游戏）", t);
		}
	}
}
