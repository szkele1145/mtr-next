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

	private static final Map<String, BveSoundConfig> CFG_CACHE = new ConcurrentHashMap<>();

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

	/** [0.9.1] 反射字段缓存——播放是热路径（每 tick 多次），Field 解析只做一次 */
	private static final java.util.Map<Class<?>, java.lang.reflect.Field> SPEED_FIELD_CACHE =
			new java.util.concurrent.ConcurrentHashMap<>();

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
		return 40.0;
	}

	private static java.lang.reflect.Field findSpeedField(Class<?> cls) throws NoSuchFieldException {
		final java.lang.reflect.Field cached = SPEED_FIELD_CACHE.get(cls);
		if (cached != null) {
			return cached;
		}
		for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
			try {
				final java.lang.reflect.Field f = c.getDeclaredField("speed");
				f.setAccessible(true);
				SPEED_FIELD_CACHE.put(cls, f);
				return f;
			} catch (NoSuchFieldException ignore) {
				// 沿父类链找(Train.speed 在两层上)
			}
		}
		throw new NoSuchFieldException("speed");
	}

	private double speedField(TrainClient train) {
		try {
			final java.lang.reflect.Field f = findSpeedField(train.getClass());
			f.setAccessible(true);
			return f.getDouble(train);
		} catch (final Throwable t) {
			return 0.55; // ≈40km/h 的米/tick 基准
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
		playMotor(world, pos);
		playBrakeRelease(world, pos); // [0.16] 低速缓解音
		playAmbient(world, pos);      // [0.17] Run/Flange/Noise 环境音
	}

	@Override
	public void playAllCars(Level world, BlockPos pos, int unused) {
		playMotor(world, pos);
		playBrakeRelease(world, pos);
		playAmbient(world, pos);
	}

	@Override
	public void playAllCarsDoorOpening(Level world, BlockPos pos, int unused) {
		final BveSoundConfig cfg = config();
		if (!cfg.doorOpen.isEmpty()) {
			play(world, pos, cfg.doorOpen, 1.0F, 1.0F);
		}
	}

	/**
	 * [0.19] 关门音：doorCloseSoundLength 秒后播 Door Close。
	 * YMTR 的 TrainSoundBase 只有开门回调（playAllCarsDoorOpening）——
	 * 关门时机无独立回调，用开门音播放时反向调度：开门 = 关门音的前奏结束。
	 * 简化实现：开门回调里同时播 close 音（BVE 的 close 音在门关时才该响，
	 * 但 3.x 无关门回调 —— 播 close 的延迟调度需要 tick 计时器，
	 * 0.19 先做开门回调的同帧双播标记，0.20 若找到关门信号再拆分）。
	 */
	private void playDoorCloseIfNeeded(Level world, BlockPos pos) {
		final BveSoundConfig cfg = config();
		// 关门音暂缓：无关门回调，盲目双播会造成开门+关门音重叠。
		// 记录为已知限制，等 0.20 评估 TrainClient.doorValue 由增转减的边沿。
	}

	/** [0.9] 实时速度驱动：查表取 pitch/vol 播 motor 档位 1 */
	private void playMotor(Level world, BlockPos pos) {
		final BveSoundConfig cfg = config();
		if (cfg.motor.isEmpty()) {
			return;
		}
		final double speed = speedKmh();
		final float pitch = (float) Math.max(0.5,
				BveSoundConfig.sampleTable(cfg.powerFreqTable, speed, 1));
		final float vol = (float) Math.max(0.1,
				BveSoundConfig.sampleTable(cfg.powerVolTable, speed, 1));
		final String clip = cfg.motor.get("1");
		if (clip != null && !clip.isEmpty() && vol > 0.01F) {
			play(world, pos, clip, vol, pitch);
		}

		// [0.16] Brake 段接线：制动调速表采样（速度 > 表内"再生制动截止"
		// 时段播 brake 档位音；近零速播 BcReleaseFull 排气缓解音）
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

	/** [0.16] 低速缓解音：速度 < 5km/h 且配置有 BcReleaseFull 时播（制动缓解排气） */
	private void playBrakeRelease(Level world, BlockPos pos) {
		final BveSoundConfig cfg = config();
		if (cfg.brakeReleaseFull.isEmpty() || speedKmh() > 5.0) {
			return;
		}
		play(world, pos, cfg.brakeReleaseFull, 0.9F, 1.0F);
	}

	/**
	 * [0.17] 环境音组：Run（行走音，速度相关 pitch）+ Flange（曲线音，
	 * 简化为速度 >30km/h 触发）+ Noise（常驻底噪）。
	 * 0.17 的曲线检测用简化阈值——YMTR 侧线数据的曲线状态不可直接取
	 * （TrainClient 无曲线字段），完整版需 hook Rail 几何，0.18 评估。
	 */
	private void playAmbient(Level world, BlockPos pos) {
		final BveSoundConfig cfg = config();
		final double speed = speedKmh();

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

		// [0.19] Joint：轨道节点经过音 — 速度 >5km/h 时按概率触发
		//（BVE 语义是每经过一个节点播一次；无节点边沿信号，用速度相关的
		// 概率近似：40km/h ≈ 每 4 tick 一次 ≈ 节点间距 0.8 格的通过频率）
		if (!cfg.joint.isEmpty() && speed > 5.0) {
			final String jointClip = cfg.joint.get("0");
			if (jointClip != null && !jointClip.isEmpty()
					&& mtrnext.util.TickRandom.chance(speed / 160.0)) {
				play(world, pos, jointClip, 0.5F, 1.0F);
			}
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
