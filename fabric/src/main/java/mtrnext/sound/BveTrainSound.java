package mtrnext.sound;

import mtr.data.TrainClient;
import mtr.sound.TrainSoundBase;
import mtrnext.MtrNext;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.Level;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [0.8] BVE 声音桥——4.0 车包的声音在 YMTR 里播放。
 * <p>
 * 解析链：{@code assets/<ns>/sounds/<base>/sound.cfg}（INI）+
 * powerfreq/powervol/brakefreq/brakevol.csv（速度-档位调速表）。
 * <p>
 * 播放策略（0.8 基础版）：
 * <ul>
 *   <li>playNearestCar：按当前速度查 powerFreq/powerVol 表选 motor 档位播放
 *       （pitch=freq，volume=vol）；</li>
 *   <li>playAllCars：同上（就近车为锚点）；</li>
 *   <li>playAllCarsDoorOpening：门开/关门音（doorOpen/doorClose）；</li>
 *   <li>run/flange/noise 常驻循环音后续按 YMTR 声音管线的 tick 语义接入。</li>
 * </ul>
 * 资源名约定：cfg 里的 {@code motor0.wav} → 实际资源 {@code sounds/<base>/motor0.ogg}
 * （车包打包时 wav 已转 ogg，样本实证）。
 */
public final class BveTrainSound extends TrainSoundBase {

	private final String soundBase;   // 如 mtr:s700_dg1000a
	private final String cfgFolder;   // 资源域前缀，如 mtr

	private static final Map<String, BveSoundConfig> CFG_CACHE = new ConcurrentHashMap<>();

	private BveTrainSound(String soundBase, String cfgFolder) {
		this.soundBase = soundBase;
		this.cfgFolder = cfgFolder;
	}

	public static BveTrainSound create(String bveSoundBaseResource) {
		// bveSoundBaseResource 如 "s700_dg1000a"（车包 vehicles[].bveSoundBaseResource）
		// 资源路径约定: assets/<ns>/sounds/<base>/sound.cfg — ns 车包主体是 mtr
		return new BveTrainSound(bveSoundBaseResource, "mtr");
	}

	@Override
	public TrainSoundBase createTrainInstance(TrainClient train) {
		return new BveTrainSound(soundBase, cfgFolder);
	}

	private BveSoundConfig config() {
		return CFG_CACHE.computeIfAbsent(soundBase, k -> load());
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
		playMotor(world, pos, 1); // 0.8 基础: 档位 1
	}

	@Override
	public void playAllCars(Level world, BlockPos pos, int unused) {
		playMotor(world, pos, 1);
	}

	@Override
	public void playAllCarsDoorOpening(Level world, BlockPos pos, int unused) {
		final BveSoundConfig cfg = config();
		if (!cfg.doorOpen.isEmpty()) {
			play(world, pos, cfg.doorOpen, 1.0F, 1.0F);
		}
	}

	private void playMotor(Level world, BlockPos pos, int motorIndex) {
		final BveSoundConfig cfg = config();
		if (cfg.motor.isEmpty()) {
			return;
		}
		// 0.8 基础版: 无速度输入接口(TrainSoundBase 的回调无速度参数),
		// 用固定中速采样; 0.9 接 TrainClient 的 speed 字段
		final float pitch = (float) Math.max(0.5,
				BveSoundConfig.sampleTable(cfg.powerFreqTable, 40.0, motorIndex));
		final float vol = (float) Math.max(0.1,
				BveSoundConfig.sampleTable(cfg.powerVolTable, 40.0, motorIndex));
		final String clip = cfg.motor.get(String.valueOf(motorIndex));
		if (clip != null && !clip.isEmpty() && vol > 0.01F) {
			play(world, pos, clip, vol, pitch);
		}
	}

	private void play(Level world, BlockPos pos, String clipName, float volume, float pitch) {
		try {
			final String res = clipName.endsWith(".wav") || clipName.endsWith(".ogg")
					? clipName.substring(0, clipName.lastIndexOf('.')) : clipName;
			// 资源: assets/<ns>/sounds/<base>/<clip> — SoundEvent 走sounds.json 注册或
			// 直接 ResourceLocation 播放(1.20.1 支持未注册 id 播 raw 文件)
			final ResourceLocation id = new ResourceLocation(cfgFolder, soundBase + "/" + res);
			world.playSound(null, pos,
					SoundEvent.createVariableRangeEvent(id), // 未经 sounds.json 的直通播放
					net.minecraft.sounds.SoundSource.BLOCKS, volume, pitch);
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("bve play failed: {}", clipName);
		}
	}
}
