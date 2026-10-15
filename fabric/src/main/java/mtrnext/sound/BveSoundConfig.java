package mtrnext.sound;

import mtrnext.MtrNext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [0.8] BVE 车辆声音配置解析器。
 * <p>
 * 实证（ERIDU-DG1000A 样本）：
 * <pre>
 * BveTs Vehicle Sound 3.01:utf-8     ← 首行标记
 * [MTR] DoorCloseSoundLength/BreakerDelay/MotorOutputAtCoast/RegenerationLimit
 * [Run] 0 = run.wav                   ← 速度相关循环音
 * [Flange] 0 = flange.wav             ← 曲线音
 * [Joint] 0 = point.wav              ← 轨道节点
 * [Motor] 0..3 = motor0..3.wav        ← 牵引档位音
 * [Brake] BcReleaseFull/BcReleaseHigh/Emergency = *.wav
 * [Door] Open/Close = *.wav
 * [Others] Noise/Shoe = *.wav         ← 常驻/闸瓦
 * </pre>
 * csv（powerfreq/powervol/brakefreq/brakevol）：
 * <pre>
 * bvets motor noise table 0.01,,,,
 * #,0,1,2,3            ← 档位列头
 * 0,,,,                ← 速度行, 值 = 该速度该档位的 pitch/vol
 * 3.2,1,,
 * 11.2,,,0.5,
 * </pre>
 * 语义：行首为速度（km/h），单元格为该速度下该 motor 档位的
 * 频率倍率（freq）或音量（vol）；空格=该档不播。
 */
public final class BveSoundConfig {

	/** 档位→资源名（[Motor]/[Run]/[Flange]/[Joint] 的键值） */
	public final Map<String, String> motor = new LinkedHashMap<>();
	public final Map<String, String> run = new LinkedHashMap<>();
	public final Map<String, String> flange = new LinkedHashMap<>();
	public final Map<String, String> joint = new LinkedHashMap<>();
	public String brakeReleaseFull = "";
	public String brakeReleaseHigh = "";
	/** [0.25] [Brake] Emergency — 紧急制动音（ERIDU 样本该键为空，可选） */
	public String emergency = "";
	public String doorOpen = "";
	public String doorClose = "";
	public String noise = "";
	public String shoe = "";
	/** [MTR] 段 */
	public double motorOutputAtCoast = 0.5;
	public double doorCloseSoundLength = 1.0;

	/** csv 曲线：行=速度, 列=档位, 值=pitch(freq) 或 vol */
	public final List<double[]> powerFreqTable = new ArrayList<>();
	public final List<double[]> powerVolTable = new ArrayList<>();
	public final List<double[]> brakeFreqTable = new ArrayList<>();
	public final List<double[]> brakeVolTable = new ArrayList<>();

	/**
	 * 解析 sound.cfg（INI 子集：section + key = value）。
	 */
	public static BveSoundConfig parseCfg(java.util.List<String> lines) {
		final BveSoundConfig cfg = new BveSoundConfig();
		String section = "";
		for (String raw : lines) {
			final String line = raw.trim();
			if (line.isEmpty() || line.startsWith("#") || line.startsWith("BveTs")) {
				continue;
			}
			if (line.startsWith("[") && line.endsWith("]")) {
				section = line.substring(1, line.length() - 1).trim();
				continue;
			}
			final int eq = line.indexOf('=');
			if (eq < 0) {
				continue;
			}
			final String key = line.substring(0, eq).trim();
			final String value = line.substring(eq + 1).trim();
			switch (section) {
				case "MTR" -> {
					if ("MotorOutputAtCoast".equals(key)) cfg.motorOutputAtCoast = dbl(value, 0.5);
					if ("DoorCloseSoundLength".equals(key)) cfg.doorCloseSoundLength = dbl(value, 1.0);
				}
				case "Run" -> cfg.run.put(key, value);
				case "Flange" -> cfg.flange.put(key, value);
				case "Joint" -> cfg.joint.put(key, value);
				case "Motor" -> cfg.motor.put(key, value);
				case "Brake" -> {
					if ("BcReleaseFull".equals(key)) cfg.brakeReleaseFull = value;
					if ("BcReleaseHigh".equals(key)) cfg.brakeReleaseHigh = value;
					if ("Emergency".equals(key)) cfg.emergency = value;
				}
				case "Door" -> {
					if ("Open".equals(key)) cfg.doorOpen = value;
					if ("Close".equals(key)) cfg.doorClose = value;
				}
				case "Others" -> {
					if ("Noise".equals(key)) cfg.noise = value;
					if ("Shoe".equals(key)) cfg.shoe = value;
				}
				default -> { }
			}
		}
		return cfg;
	}

	/**
	 * 解析 csv 调速表：首行标记行跳过，第二行 # 列头跳过，
	 * 之后每行 [速度, 档0值, 档1值, ...]（空=0=不播）。
	 * <p>
	 * [0.31 修复] 必须用 {@code split(",", -1)}：Java 的 split 默认丢弃
	 * <b>尾部空串</b>，而 BVE 表大量使用尾部空单元格（{@code 3.2,1,,}）。
	 * 旧写法下 {@code 0,,,,}（该速度全档不播）会被解析成长度 1 的数组，
	 * 随后被 sampleTable 的 {@code row.length <= motorIndex} 跳过，
	 * 导致 0~3.2 km/h 区间错误沿用下一个速度点的档位音量。
	 * 保留空串后行长度恒等于列数，语义才正确。
	 */
	public static List<double[]> parseTable(java.util.List<String> lines) {
		final List<double[]> out = new ArrayList<>();
		for (String raw : lines) {
			final String line = raw.trim();
			if (line.isEmpty() || line.startsWith("bvets") || line.startsWith("#")) {
				continue;
			}
			final String[] parts = line.split(",", -1);
			final double[] row = new double[parts.length];
			for (int i = 0; i < parts.length; i++) {
				final String p = parts[i].trim();
				row[i] = p.isEmpty() ? 0 : dbl(p, 0);
			}
			out.add(row);
		}
		return out;
	}

	/** 按速度查表：返回该速度档位索引的插值（线性）——0=不播 */
	public static double sampleTable(List<double[]> table, double speedKmh, int motorIndex) {
		if (table.isEmpty() || motorIndex < 0) {
			return 0;
		}
		double[] prev = null;
		for (final double[] row : table) {
			if (row.length <= motorIndex) {
				continue;
			}
			if (row[0] >= speedKmh) {
				if (prev == null || prev.length <= motorIndex) {
					return row[motorIndex];
				}
				// 线性插值
				final double s0 = prev[0], s1 = row[0];
				final double t = s1 - s0 < 1e-9 ? 1 : (speedKmh - s0) / (s1 - s0);
				return prev[motorIndex] + (row[motorIndex] - prev[motorIndex]) * t;
			}
			prev = row;
		}
		return prev != null && prev.length > motorIndex ? prev[motorIndex] : 0;
	}

	private static double dbl(String s, double def) {
		try {
			return Double.parseDouble(s);
		} catch (Exception e) {
			return def;
		}
	}
}
