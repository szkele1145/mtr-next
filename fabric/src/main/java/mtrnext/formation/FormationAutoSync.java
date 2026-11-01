package mtrnext.formation;

import mtrnext.MtrNext;
import mtrnext.pack.VehicleResource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.Map;
import java.util.UUID;

/**
 * [0.43.3] 进世界后自动把持久化的编组重新下发给服务端 Siding。
 *
 * <p><b>为什么需要它</b>（真机日志实证）：
 * <pre>
 *   [15:17:01] Applied formation (1 cars) to YMTR siding
 *   [15:17:15] Applied formation (6 cars) to YMTR siding
 * </pre>
 * 编组应用**是成功的**，字段也写进了客户端 Siding。但 MTR 的 Siding 是
 * <b>服务端权威</b>数据：客户端对象每次进世界都会被服务端数据覆盖回来，
 * 于是「配置过一次，之后车又不渲染 / 配置是空气」。
 *
 * <p>⇒ 每次进入世界（等集成服务器与 RailwayData 就绪后）遍历持久化编组，
 * 按 sidingId 重新发一次官方更新包。用户只需配置一次。
 *
 * <p><b>★ [0.44.1] 下发内容改为「trainId + baseTrainType」两件套</b>：
 * 旧版只写 baseTrainType（还是反射写服务端对象），trainId 没动 ⇒
 * 客户端 {@code getTrainProperties(trainId)} 仍命中原生车型 ⇒ 我们的渲染器不被调用。
 * 详见 {@link FormationSidingBridge} 的类注释。
 */
public final class FormationAutoSync {

	/** 首次尝试前的等待 tick 数（约 5 秒），确保服务端 RailwayData 已加载并同步完毕 */
	private static final int DELAY_TICKS = 100;

	/**
	 * [0.44.1] 失败重试窗口：客户端 {@code ClientData.SIDINGS} 可能在等待期内还没同步完，
	 * 因此一次没命中不算结束，每秒重试一次，最多 {@link #MAX_ATTEMPTS} 次。
	 */
	private static final int RETRY_TICKS = 20;
	private static final int MAX_ATTEMPTS = 30;

	private static int ticks = 0;
	private static int attempts = 0;
	private static boolean done = false;
	private static Object lastLevel = null;

	private FormationAutoSync() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(FormationAutoSync::tick);
		MtrNext.LOGGER.info("MTR-Next: formation auto-sync registered");
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null) {
			// 退出世界：重置，下次进入重新尝试
			if (lastLevel != null) {
				lastLevel = null;
				done = false;
				attempts = 0;
				ticks = 0;
				// ★ [0.56.0] 清空内存里的编组缓存，并允许下次进世界重新从磁盘读一次
				FormationStore.resetForWorld();
			}
			return;
		}
		if (done) {
			return;
		}
		if (lastLevel != mc.level) {
			lastLevel = mc.level;
			ticks = 0;
			attempts = 0;
			done = false;
		}
		if (!mc.hasSingleplayerServer()) {
			return;
		}
		if (++ticks < DELAY_TICKS) {
			return;
		}
		if (attempts >= MAX_ATTEMPTS) {
			done = true;
			MtrNext.LOGGER.warn("自动同步：连续 {} 次未能下发，放弃本轮", MAX_ATTEMPTS);
			return;
		}
		// 重试节流：首次立即尝试，之后每 RETRY_TICKS 一次
		if (attempts > 0 && ++ticks < DELAY_TICKS + RETRY_TICKS) {
			return;
		}
		attempts++;
		if (syncAll()) {
			done = true;
		} else {
			ticks = DELAY_TICKS; // 让下一轮只受 RETRY_TICKS 约束
		}
	}

	/**
	 * 遍历持久化编组并下发。
	 *
	 * @return 是否全部成功（false ⇒ 值得重试）
	 */
	private static boolean syncAll() {
		try {
			// [0.43.4] 先从存档读回编组（此前从未加载，导致这里永远是空表）
			// ★ [0.56.0] 改成「只读一次」：本方法会在失败重试里被调用最多 30 次，
			//   每次 loadNow() 都会 FORMATIONS.clear() —— 玩家在重试窗口内编辑编组
			//   就会被磁盘旧内容覆盖（「加了又变回原来的」的根因）。
			FormationStore.loadOnce();
			final Map<UUID, Formation> formations = FormationStore.all();
			if (formations.isEmpty()) {
				MtrNext.LOGGER.info("自动同步：没有已保存的编组");
				return true; // 没有编组可同步，不必重试
			}
			int ok = 0;
			int missing = 0;
			int skipped = 0;
			for (final Map.Entry<UUID, Formation> e : formations.entrySet()) {
				final Formation formation = e.getValue();
				if (formation == null || formation.cars.isEmpty()) {
					skipped++;
					continue;
				}
				// 3.x 一列车只有一个车型 ⇒ 取编组中占比最多的那个 4.0 车型
				final VehicleResource res = FormationSidingBridge.dominantVehicle(formation);
				if (res == null) {
					// ★ [0.56.0] 不再静默跳过：此前的日志是「成功 0 / 共 1（待同步 0）」，
					//   看起来像什么都没发生，实际是「车型 id 在当前车包里查不到」。
					skipped++;
					MtrNext.LOGGER.warn("自动同步跳过编组 {}：节数={}，但车型在车包里找不到（可能换过车包）",
							e.getKey(), formation.cars.size());
					continue;
				}
				final long sidingId = e.getKey().getLeastSignificantBits();
				if (FormationSidingBridge.syncBySidingId(sidingId, res, formation.cars.size())) {
					ok++;
				} else {
					missing++; // 客户端还没收到这个 Siding，值得重试
				}
			}
			MtrNext.LOGGER.info("自动同步编组到服务端：成功 {} / 共 {}（待同步 {}，跳过 {}）",
					ok, formations.size(), missing, skipped);
			return missing == 0;
		} catch (final Throwable t) {
			MtrNext.LOGGER.error("自动同步编组失败", t);
			return false;
		}
	}
}
