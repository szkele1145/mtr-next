package mtrnext.sound;

import mtr.data.TrainClient;
import mtr.sound.TrainSoundBase;
import mtrnext.MtrNext;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [0.8→0.9] BVE 声音桥——4.0 车包的声音在 YMTR 里播放。
 * <p>
 * 解析链：{@code assets/<ns>/sounds/<base>/sound.cfg}（INI）+
 * powerfreq/powervol/brakefreq/brakevol.csv（速度-档位调速表）。
 * <p>
 * [0.9] 实时速度采样：createTrainInstance 持有 {@link TrainClient}，
 * 播放时经 {@code speed} 字段（米/tick → km/h ×3.6×20）取真实速度
 * 查调速表；门回调保持即时音。速度字段 javap 实证（TrainClient.speed
 * 继承自 Train，米/tick）。
 */
public final class BveTrainSound extends TrainSoundBase {

	private final String soundBase;
	private final String cfgFolder;

	/** 播放速度采样：createTrainInstance 时捕获宿主列车实例 */
	private TrainClient hostTrain;

	/** [0.20] doorValue 边沿检测：由增转减 = 真实关门信号 */
	private float lastDoorValue = -1.0F;
	/** [0.25] speed 边沿检测共享状态：发车排气/紧急制动共用 */
	private double lastSpeedKmh = -1.0;

	private static final Map<String, BveSoundConfig> CFG_CACHE = new ConcurrentHashMap<>();

	/** 反射/宿主均不可达时的缺省速度基准（米/tick，≈40 km/h） */
	private static final double DEFAULT_SPEED_M_PER_TICK = 0.55;

	private BveTrainSound(String soundBase, String cfgFolder) {
		this.soundBase = soundBase;
		this.cfgFolder = cfgFolder;
	}

	public static BveTrainSound create(String bveSoundBaseResource) {
		return new BveTrainSound(bveSoundBaseResource, "mtr");
	}

	@Override
	public TrainSoundBase createTrainInstance(TrainClient train) {
		final BveTrainSound s = new BveTrainSound(soundBase, cfgFolder);
		s.hostTrain = train; // [0.9] 持有宿主 → 实时速度
		return s;
	}

	private BveSoundConfig config() {
		return CFG_CACHE.computeIfAbsent(soundBase, k -> load());
	}

	/** [0.9] 宿主列车实时速度（km/h）——TrainClient.speed 为米/tick */
	private double speedKmh() {
		try {
			if (hostTrain != null) {
				final double mPerTick = speedField(hostTrain);
				return Math.abs(mPerTick) * 72.0; // 米/tick × 20 tick/s × 3.6 s·km/h
			}
		} catch (final Throwable ignore) {
			// 字段不可达时回退默认速度
		}
		return DEFAULT_SPEED_M_PER_TICK * 72.0; // 缺省速度：米/tick × 20 tick/s × 3.6 s·km/h
	}

	/** [0.22] 反射统一走 HierarchyFieldFinder（speed/doorValue 两处重复模式提取） */
	private double speedField(TrainClient train) {
		try {
			return mtrnext.util.HierarchyFieldFinder.find(train, "speed").getDouble(train);
		} catch (final Throwable t) {
			return DEFAULT_SPEED_M_PER_TICK; // ≈40km/h 的米/tick 基准
		}
	}

	private BveSoundConfig load() {
		try {
			final ResourceManager rm = net.minecraft.client.Minecraft.getInstance().getResourceManager();
			final ResourceLocation cfgId = new ResourceLocation(cfgFolder, "sounds/" + soundBase + "/sound.cfg");
			if (!rm.getResource(cfgId).isPresent()) {
				return new BveSoundConfig();
			}
			final List<String> lines = new ArrayList<>();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(
					rm.getResource(cfgId).get().open(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					lines.add(line);
				}
			}
			final BveSoundConfig cfg = BveSoundConfig.parseCfg(lines);
			cfg.powerFreqTable.addAll(readCsv(rm, "powerfreq"));
			cfg.powerVolTable.addAll(readCsv(rm, "powervol"));
			cfg.brakeFreqTable.addAll(readCsv(rm, "brakefreq"));
			cfg.brakeVolTable.addAll(readCsv(rm, "brakevol"));
			MtrNext.LOGGER.info("BVE sound loaded: {} ({} motor clips)", soundBase, cfg.motor.size());
			return cfg;
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("BVE sound load failed: {}", soundBase, t);
			return new BveSoundConfig();
		}
	}

	private List<String> readLines(ResourceManager rm, String name) {
		final List<String> lines = new ArrayList<>();
		try {
			final ResourceLocation id = new ResourceLocation(cfgFolder, "sounds/" + soundBase + "/" + name + ".csv");
			if (!rm.getResource(id).isPresent()) {
				return lines;
			}
			try (BufferedReader r = new BufferedReader(new InputStreamReader(
					rm.getResource(id).get().open(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					lines.add(line);
				}
			}
		} catch (final Throwable ignore) {
			// 单文件缺失不致命
		}
		return lines;
	}

	private List<double[]> readCsv(ResourceManager rm, String name) {
		return BveSoundConfig.parseTable(readLines(rm, name));
	}

	@Override
	public void playNearestCar(Level world, BlockPos pos, int carNumber) {
		// [0.26] 回调级单次取 speed（此前 3 个速度消费段各自反射取速 = 3 次）
		final BveSoundConfig cfg = config();
		final double speed = speedKmh();
		playMotor(world, pos, cfg, speed);
		playBrakeRelease(world, pos, cfg, speed);  // [0.16] 低速缓解音
		playAmbient(world, pos, cfg, speed);       // [0.17] Run/Flange/Noise 环境音
		tickDoorCloseEdge(world, pos, cfg);        // [0.20] 关门沿检测
		tickSpeedEdges(world, pos, cfg, speed);    // [0.25→0.26] 发车排气+紧急制动合并为单一边沿方法
	}

	@Override
	public void playAllCars(Level world, BlockPos pos, int unused) {
		final BveSoundConfig cfg = config();
		final double speed = speedKmh();
		playMotor(world, pos, cfg, speed);
		playBrakeRelease(world, pos, cfg, speed);
		playAmbient(world, pos, cfg, speed);
		tickDoorCloseEdge(world, pos, cfg);
		tickSpeedEdges(world, pos, cfg, speed);
	}

	@Override
	public void playAllCarsDoorOpening(Level world, BlockPos pos, int unused) {
		final BveSoundConfig cfg = config();
		if (!cfg.doorOpen.isEmpty()) {
			play(world, pos, cfg.doorOpen, 1.0F, 1.0F);
		}
	}

	/**
	 * [0.20] Door Close 边沿检测接线：TrainClient.doorValue 由增转减的瞬间
	 * 播 Door Close 音（此前 0.19 记录为限制，现用 javap 实证的 doorValue
	 * 字段做真实关门信号——开门时 doorValue 递增，关门时递减）。
	 * 挂在 playAllCarsDoorOpening 之外的每个播放回调里采样（tick 级）。
	 */
	private void tickDoorCloseEdge(Level world, BlockPos pos, BveSoundConfig cfg) {
		if (cfg.doorClose.isEmpty() || hostTrain == null) {
			return;
		}
		try {
			// [0.22] 反射统一走 HierarchyFieldFinder
			final float current = mtrnext.util.HierarchyFieldFinder
					.find(hostTrain, "doorValue").getFloat(hostTrain);
			if (lastDoorValue >= 0 && current < lastDoorValue - 0.05F && lastDoorValue > 0.1F) {
				// 由增转减 + 此前确实开着 → 关门沿
				play(world, pos, cfg.doorClose, 1.0F, 1.0F);
			}
			lastDoorValue = current;
		} catch (final Throwable ignore) {
			// doorValue 不可达时跳过边沿检测
		}
	}

	/** [0.26] cfg+speed 传入版：实时速度驱动 motor/brake 档位 */
	private void playMotor(Level world, BlockPos pos, BveSoundConfig cfg, double speed) {
		if (cfg.motor.isEmpty()) {
			return;
		}
		final float pitch = (float) Math.max(0.5,
				BveSoundConfig.sampleTable(cfg.powerFreqTable, speed, 1));
		final float vol = (float) Math.max(0.1,
				BveSoundConfig.sampleTable(cfg.powerVolTable, speed, 1));
		final String clip = cfg.motor.get("1");
		if (clip != null && !clip.isEmpty() && vol > 0.01F) {
			play(world, pos, clip, vol, pitch);
		}

		// [0.16] Brake 段：制动调速表采样
		if (!cfg.brakeFreqTable.isEmpty()) {
			final float bPitch = (float) Math.max(0.5,
					BveSoundConfig.sampleTable(cfg.brakeFreqTable, speed, 1));
			final float bVol = (float) Math.max(0.1,
					BveSoundConfig.sampleTable(cfg.brakeVolTable, speed, 1));
			if (bVol > 0.05F) {
				final String brakeClip = cfg.motor.get("1"); // brake 档位复用 motor 表位
				if (brakeClip != null && !brakeClip.isEmpty()) {
					play(world, pos, brakeClip, bVol * 0.8F, bPitch);
				}
			}
		}
	}

	/** [0.26] cfg+speed 传入版：低速缓解音 */
	private void playBrakeRelease(Level world, BlockPos pos, BveSoundConfig cfg, double speed) {
		if (cfg.brakeReleaseFull.isEmpty() || speed > 5.0) {
			return;
		}
		play(world, pos, cfg.brakeReleaseFull, 0.9F, 1.0F);
	}

	/**
	 * [0.26] 速度边沿合并：发车排气（静止→起动沿）+ 紧急制动（单 tick 降速 >3km/h）
	 * 共享 lastSpeedKmh 且单次取速——0.25 的两个独立方法各自反射取速已并。
	 */
	private void tickSpeedEdges(Level world, BlockPos pos, BveSoundConfig cfg, double speed) {
		if (lastSpeedKmh >= 0) {
			// 发车排气沿
			if (!cfg.brakeReleaseHigh.isEmpty() && lastSpeedKmh < 2.0 && speed >= 2.0) {
				play(world, pos, cfg.brakeReleaseHigh, 0.8F, 1.0F);
			}
			// 紧急制动沿
			if (!cfg.emergency.isEmpty() && speed < lastSpeedKmh - 3.0) {
				play(world, pos, cfg.emergency, 1.0F, 1.0F);
			}
		}
		lastSpeedKmh = speed;
	}

	/** [0.26] cfg+speed 传入版：环境音组（Run/Flange/Noise） */
	private void playAmbient(Level world, BlockPos pos, BveSoundConfig cfg, double speed) {

		// Run：速度归一化 pitch（0.8~1.4 随 40km/h 线性）
		if (!cfg.run.isEmpty()) {
			final String runClip = cfg.run.get("0");
			if (runClip != null && !runClip.isEmpty() && speed > 2.0) {
				final float pitch = (float) (0.8 + Math.min(0.6, speed / 40.0 * 0.6));
				play(world, pos, runClip, 0.4F, pitch);
			}
		}

		// Flange：中高速触发（曲线音的简化近似）
		if (!cfg.flange.isEmpty() && speed > 30.0) {
			final String flangeClip = cfg.flange.get("0");
			if (flangeClip != null && !flangeClip.isEmpty()) {
				play(world, pos, flangeClip, 0.35F, 1.0F);
			}
		}

		// Noise：常驻底噪（低速时也响）
		if (!cfg.noise.isEmpty()) {
			play(world, pos, cfg.noise, 0.15F, 1.0F);
		}

		// [0.21] Joint：轨道节点经过音 — 速度 >5km/h 时按概率触发
		//（BVE 语义是每经过一个节点播一次；无节点边沿信号，用速度相关的
		// 概率近似：40km/h ≈ 每 4 tick 一次 ≈ 节点间距 0.8 格的通过频率）
		if (!cfg.joint.isEmpty() && speed > 5.0) {
			final String jointClip = cfg.joint.get("0");
			if (jointClip != null && !jointClip.isEmpty()
					&& mtrnext.util.TickRandom.chance(speed / 160.0)) {
				play(world, pos, jointClip, 0.5F, 1.0F);
			}
		}

		// [0.21] Shoe：低速制动闸瓦音 — BVE 语义"低于再生制动截止速度的制动"。
		// 简化：5~15km/h 区间播（再生→空气制动的切换带），vol 随速度下降升高。
		if (!cfg.shoe.isEmpty() && speed > 5.0 && speed < 15.0) {
			final float shoeVol = (float) (0.3 + (15.0 - speed) / 10.0 * 0.3);
			play(world, pos, cfg.shoe, shoeVol, 1.0F);
		}
	}

	private void play(Level world, BlockPos pos, String clipName, float volume, float pitch) {
		try {
			final String res = clipName.endsWith(".wav") || clipName.endsWith(".ogg")
					? clipName.substring(0, clipName.lastIndexOf('.')) : clipName;
			final ResourceLocation id = new ResourceLocation(cfgFolder, soundBase + "/" + res);
			world.playSound(null, pos,
					SoundEvent.createVariableRangeEvent(id),
					SoundSource.BLOCKS, volume, pitch);
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("bve play failed: {}", clipName);
		}
	}
}
