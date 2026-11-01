package mtrnext.screen;

import mtr.client.ClientData;
import mtr.data.Siding;
import mtr.data.TrainClient;
import mtrnext.MtrNext;
import mtrnext.formation.Formation;
import mtrnext.formation.FormationStore;
import mtrnext.pack.VehiclePackLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.Map;
import java.util.UUID;

/**
 * ★ [0.57.0] 编组界面<b>自测驱动</b>（只在 {@code -Dmtrnext.selftest=true}
 * 或环境变量 {@code MTRNEXT_SELFTEST=1} 时启用）。
 *
 * <p><b>为什么需要它</b>：编组界面的按钮只能靠鼠标点，自动验证无从下手；
 * 而「编组加了就消失 / 重开又变回去」必须用<b>玩家真正走的那条代码路径</b>来证明。
 * 本类在进入世界后自动走一遍：
 * <ol>
 *   <li>打开编组界面（等价于点 SidingScreen 里的「编组 [MTR-Next]」按钮）；</li>
 *   <li>记录可用车数量 / 默认选中项；</li>
 *   <li>调用 {@link FormationScreen#addSelectedVehicle()} 两次（与按钮同一个方法）；</li>
 *   <li>关闭界面（触发 {@code onClose} → {@code saveNow} → 下发 Siding）；</li>
 *   <li>重新打开，读回编组节数，并对比 sidecar 文件大小。</li>
 * </ol>
 * 每一步都打 {@code [selftest]} 日志，结论只从日志读，不靠肉眼。
 *
 * <p><b>[0.57.0] 新增「世界对账探针」</b>：周期性打印每列车的
 * {@code trainId / trainCars / spacing} 与「该车所属编组的节数」，
 * 这是判断「车厢数 = 编组节数」的唯一客观依据——
 * 界面里配了 3 节、世界里只跑 2 节，只有这行日志能看出来。
 *
 * <p><b>安全性</b>：未设置系统属性/环境变量时 {@link #register()} 直接 return，
 * 不注册任何 tick 回调，对正常游玩零影响。也<b>不</b>读写任何 flag 文件
 * （历史上的 {@code run/mtrnext-preview-selftest.flag} 会劫持生产路径，已废弃）。
 */
public final class FormationSelfTest {

	/** 进世界后等 40 tick（约 2 秒）再开始，避开 RailwayData 尚未就绪的窗口 */
	private static final int WARMUP_TICKS = 40;
	/** 世界对账探针：每 PROBE_TICKS tick 打一次（约 5 秒） */
	private static final int PROBE_TICKS = 100;
	/** 对账探针最多打几次（避免刷屏） */
	private static final int PROBE_MAX = 12;

	private static int ticks;
	private static int phase;
	private static int probeTicks;
	private static int probes;
	private static UUID sidingId;
	private static long fileBefore = -1;

	private FormationSelfTest() {
	}

	public static boolean enabled() {
		if (Boolean.getBoolean("mtrnext.selftest")) {
			return true;
		}
		final String env = System.getenv("MTRNEXT_SELFTEST");
		return "1".equals(env) || "true".equalsIgnoreCase(env) || "yes".equalsIgnoreCase(env);
	}

	public static void register() {
		if (!enabled()) {
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(FormationSelfTest::tick);
		MtrNext.LOGGER.warn("[selftest] 已启用（-Dmtrnext.selftest / MTRNEXT_SELFTEST）");
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null) {
			// 退出世界：复位，方便反复进
			phase = 0;
			ticks = 0;
			probeTicks = 0;
			probes = 0;
			return;
		}
		// ★ 世界对账探针：与界面自测阶段无关，独立计数
		probeTick();

		if (++ticks < WARMUP_TICKS) {
			return;
		}
		ticks = 0;
		try {
			switch (phase) {
				case 0 -> open();
				case 1 -> addAndMeasure();
				case 2 -> closeAndSave();
				case 3 -> reopen(mc);
				default -> {
					// 已完成
				}
			}
		} catch (final Throwable t) {
			MtrNext.LOGGER.error("[selftest] 失败", t);
			phase = 99;
		}
	}

	/**
	 * ★ [0.57.0] 世界对账探针：把「编组节数」和「MTR 真正发出来的车厢数」并排打印。
	 *
	 * <p>3.x 的 {@code trainCars} 由 {@code floor(railLength / spacing)} 决定，
	 * 与编组无关；{@code FormationSidingBridge} 会反算 {@code baseTrainType} 的
	 * 车长来凑出目标节数。这行日志是那件事<b>是否真的生效</b>的唯一证据。
	 */
	private static void probeTick() {
		if (++probeTicks < PROBE_TICKS) {
			return;
		}
		probeTicks = 0;
		if (probes >= PROBE_MAX) {
			return;
		}
		probes++;
		try {
			final int sidings = ClientData.SIDINGS == null ? -1 : ClientData.SIDINGS.size();
			final int trains = ClientData.TRAINS == null ? -1 : ClientData.TRAINS.size();
			MtrNext.LOGGER.info("[selftest][probe] 第 {} 次：客户端 SIDINGS={} TRAINS={} 内存编组={} 条",
					probes, sidings, trains, FormationStore.all().size());
			if (ClientData.TRAINS == null) {
				return;
			}
			for (final TrainClient train : ClientData.TRAINS) {
				final Formation f = FormationStore.get(new UUID(0L, train.sidingId));
				final String res = VehiclePackLoader.getVehicle(train.trainId) == null
						? "（非 4.0 车型）" : "";
				MtrNext.LOGGER.info("[selftest][probe]   列车 siding={} trainId='{}'{} "
								+ "trainCars={} spacing={} 编组节数={} renderer={}",
						train.sidingId, train.trainId, res, train.trainCars, train.spacing,
						f == null ? -1 : f.cars.size(),
						train.trainRenderer == null ? "null" : train.trainRenderer.getClass().getSimpleName());
				// 逐节车型（编组里配的 vs 渲染时真正用的）
				if (f != null) {
					final StringBuilder sb = new StringBuilder();
					for (int i = 0; i < f.cars.size(); i++) {
						sb.append(i).append(':').append(f.cars.get(i).vehicleId)
								.append(f.cars.get(i).forward ? "→" : "←").append(' ');
						if (i >= 7) {
							sb.append("...");
							break;
						}
					}
					MtrNext.LOGGER.info("[selftest][probe]     编组内容: {}", sb.toString().trim());
				}
			}
		} catch (final Throwable t) {
			MtrNext.LOGGER.warn("[selftest][probe] 失败", t);
		}
	}

	private static void open() {
		final Map<UUID, Formation> all = FormationStore.all();
		if (all.isEmpty()) {
			// ★ [0.57.0] 没有已存编组时，退回「世界里的第一个 Siding」，
			//   这样干净存档也能跑完整自测（自测世界专用，不污染玩家存档）。
			UUID fallback = null;
			try {
				if (ClientData.SIDINGS != null) {
					for (final Siding siding : ClientData.SIDINGS) {
						if (siding != null && siding.railLength > 1.0F) {
							fallback = new UUID(0L, siding.id);
							MtrNext.LOGGER.info("[selftest] 内存无编组 → 取世界里的 Siding {}（railLength={}）",
									fallback, siding.railLength);
							break;
						}
					}
				}
			} catch (final Throwable t) {
				MtrNext.LOGGER.warn("[selftest] 取世界 Siding 失败", t);
			}
			if (fallback == null) {
				MtrNext.LOGGER.warn("[selftest] 既无编组也无 Siding，无法自测");
				phase = 99;
				return;
			}
			sidingId = fallback;
		} else {
			sidingId = all.keySet().iterator().next();
		}
		fileBefore = sizeOf();
		final Formation f = FormationStore.getOrCreate(sidingId);
		MtrNext.LOGGER.info("[selftest] ① 进世界后：编组 {} 条，目标 siding={}，起始 {} 节，"
						+ "sidecar={}B，可用车={} 种",
				all.size(), sidingId, f.cars.size(), fileBefore, VehiclePackLoader.getVehicles().size());
		FormationScreen.open(sidingId);
		phase = 1;
	}

	private static void addAndMeasure() {
		if (!(Minecraft.getInstance().screen instanceof FormationScreen screen)) {
			MtrNext.LOGGER.warn("[selftest] ① 打开界面失败：当前 screen 不是 FormationScreen（{}）",
					Minecraft.getInstance().screen == null ? "null" : Minecraft.getInstance().screen.getClass().getName());
			phase = 99;
			return;
		}
		MtrNext.LOGGER.info("[selftest] ② 界面已打开：可用车 {} 种，默认选中 index={}",
				screen.selftestAvailable(), screen.selftestSelected());
		// ★ [0.60.0] 布局自检：控件重叠数必须是 0（这是「字和按钮全重叠」的客观判据）
		final int overlaps = screen.selftestOverlapCount();
		MtrNext.LOGGER.info("[selftest] ②b 布局自检：控件 {} 个，重叠 {}", screen.selftestWidgetCount(), overlaps);
		if (overlaps > 0) {
			MtrNext.LOGGER.error("[selftest] ②b ★布局仍有 {} 处控件重叠（见上方的 [formation] 控件重叠 warn）", overlaps);
		}
		final int before = screen.selftestCars();
		final boolean ok1 = screen.addSelectedVehicle();
		MtrNext.LOGGER.info("[selftest] ③ 第一次点「添加 >>」：{} 节 → {} 节（返回值 {}）",
				before, screen.selftestCars(), ok1);
		final int mid = screen.selftestCars();
		final boolean ok2 = screen.addSelectedVehicle();
		MtrNext.LOGGER.info("[selftest] ④ 第二次点「添加 >>」：{} 节 → {} 节（返回值 {}）",
				mid, screen.selftestCars(), ok2);
		phase = 2;
	}

	private static void closeAndSave() {
		if (!(Minecraft.getInstance().screen instanceof FormationScreen screen)) {
			phase = 99;
			return;
		}
		final int cars = screen.selftestCars();
		screen.selftestClose(); // = 玩家点「保存并关闭」
		MtrNext.LOGGER.info("[selftest] ⑤ 保存并关闭：内存 {} 节；sidecar {}B → {}B",
				cars, fileBefore, sizeOf());
		phase = 3;
	}

	private static void reopen(Minecraft mc) {
		// 等上一屏真正关掉
		if (mc.screen != null) {
			return;
		}
		FormationStore.resetForWorld(); // 模拟「重开游戏/重进世界」：清内存
		FormationStore.loadOnce();      // 从 sidecar 读回
		final Formation reloaded = FormationStore.get(sidingId);
		MtrNext.LOGGER.info("[selftest] ⑥ 清内存 + 从磁盘重读：编组 {} 条，该 siding {} 节",
				FormationStore.all().size(), reloaded == null ? -1 : reloaded.cars.size());
		FormationScreen.open(sidingId);
		if (mc.screen instanceof FormationScreen screen) {
			MtrNext.LOGGER.info("[selftest] ⑦ 再次打开界面：列表显示 {} 节（应为上一步的值）",
					screen.selftestCars());
			screen.selftestClose();
		}
		MtrNext.LOGGER.info("[selftest] 完成");
		phase = 99;
	}

	private static long sizeOf() {
		try {
			return java.nio.file.Files.size(FormationStore.worldFile());
		} catch (final Throwable t) {
			return -1L;
		}
	}
}
