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
		for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
			try {
				final java.lang.reflect.Field f = c.getDeclaredField("speed");
				f.setAccessible(true);
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
	}

	@Override
	public void playAllCars(Level world, BlockPos pos, int unused) {
		playMotor(world, pos);
	}

	@Override
	public void playAllCarsDoorOpening(Level world, BlockPos pos, int unused) {
		final BveSoundConfig cfg = config();
		if (!cfg.doorOpen.isEmpty()) {
			play(world, pos, cfg.doorOpen, 1.0F, 1.0F);
		}
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
