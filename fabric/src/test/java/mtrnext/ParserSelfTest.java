package mtrnext;

import java.util.ArrayList;
import java.util.List;

/**
 * [0.31] 解析器/数据层纯逻辑自测——不依赖 MC 客户端，headless 可跑。
 * <p>
 * 背景：docs/client-verification-boundary.md 指出车包链路的端到端验证
 * 需要图形客户端；但<b>解析层与编组校验层是纯数据逻辑</b>，可以脱离
 * MC 离线验证。本文件把那部分从"只能靠客户端肉眼看"变成可回归。
 * <p>
 * 运行（无需 Gradle / MC classpath，直接 javac + java）：
 * <pre>
 * javac -encoding UTF-8 -d out -cp build/classes/java/main \
 *       fabric/src/test/java/mtrnext/ParserSelfTest.java
 * java -cp "out;build/classes/java/main" mtrnext.ParserSelfTest
 * </pre>
 */
public final class ParserSelfTest {

	private ParserSelfTest() {
	}

	private static int pass = 0;
	private static int fail = 0;

	public static void main(String[] args) {
		testSoundCfg();
		testSpeedTable();
		testFormationRules();
		System.out.println();
		System.out.println("PASS=" + pass + " FAIL=" + fail);
		if (fail > 0) {
			System.exit(1);
		}
	}

	/** sound.cfg 的 INI 子集解析：七段全部落位 + 空值语义 */
	private static void testSoundCfg() {
		System.out.println("[BveSoundConfig.parseCfg]");
		final List<String> lines = new ArrayList<>(List.of(
				"BveTs Vehicle Sound 3.01:utf-8",
				"# comment",
				"[MTR]",
				"DoorCloseSoundLength = 2.5",
				"MotorOutputAtCoast = 0.35",
				"[Run]",
				"0 = run.wav",
				"[Flange]",
				"0 = flange.wav",
				"[Joint]",
				"0 = point.wav",
				"[Motor]",
				"0 = motor0.wav",
				"1 = motor1.wav",
				"[Brake]",
				"BcReleaseFull = airzero.wav",
				"BcReleaseHigh = airhigh.wav",
				"Emergency = ",
				"[Door]",
				"Open = dooropn.wav",
				"Close = doorcls.wav",
				"[Others]",
				"Noise = noise.wav",
				"Shoe = shoe.wav"));

		final mtrnext.sound.BveSoundConfig cfg = mtrnext.sound.BveSoundConfig.parseCfg(lines);
		check("标记行/注释被跳过(未污染 motor)", !cfg.motor.containsKey("BveTs"));
		check("[MTR] DoorCloseSoundLength=2.5", cfg.doorCloseSoundLength == 2.5);
		check("[MTR] MotorOutputAtCoast=0.35", Math.abs(cfg.motorOutputAtCoast - 0.35) < 1e-9);
		check("[Run] 0 -> run.wav", "run.wav".equals(cfg.run.get("0")));
		check("[Flange] 0 -> flange.wav", "flange.wav".equals(cfg.flange.get("0")));
		check("[Joint] 0 -> point.wav", "point.wav".equals(cfg.joint.get("0")));
		check("[Motor] 档位 0/1 双键", "motor0.wav".equals(cfg.motor.get("0")) && "motor1.wav".equals(cfg.motor.get("1")));
		check("[Brake] BcReleaseFull", "airzero.wav".equals(cfg.brakeReleaseFull));
		check("[Brake] BcReleaseHigh", "airhigh.wav".equals(cfg.brakeReleaseHigh));
		check("[Brake] Emergency 空值保留(非 null)", "".equals(cfg.emergency));
		check("[Door] Open/Close", "dooropn.wav".equals(cfg.doorOpen) && "doorcls.wav".equals(cfg.doorClose));
		check("[Others] Noise/Shoe", "noise.wav".equals(cfg.noise) && "shoe.wav".equals(cfg.shoe));
		check("未定义段不误入(Motor 仅 2 键)", cfg.motor.size() == 2);
	}

	/** csv 调速表：列头/标记行跳过 + 线性插值 */
	private static void testSpeedTable() {
		System.out.println("[BveSoundConfig.parseTable/sampleTable]");
		final List<String> csv = new ArrayList<>(List.of(
				"bvets motor noise table 0.01,,,,",
				"#,0,1,2,3",
				"0,,,,",
				"3.2,1,,",
				"11.2,,,0.5,"));

		final List<double[]> table = mtrnext.sound.BveSoundConfig.parseTable(csv);
		check("表体 3 行(标记行+列头已跳)", table.size() == 3);
		check("列数 5(速度+4 档)", table.get(0).length == 5);
		check("首行速度=0", table.get(0)[0] == 0.0);
		check("3.2 @档1 = 1.0", table.get(1)[1] == 1.0);
		check("11.2 @档3 = 0.5", table.get(2)[3] == 0.5);
		check("空格单元格 = 0(不播)", table.get(2)[1] == 0.0);

		// 3.2~11.2 中点 7.2：档1 从 1.0 -> 0.0，线性 = 0.5
		final double mid = mtrnext.sound.BveSoundConfig.sampleTable(table, 7.2, 1);
		check("插值 7.2km/h 档1 = 0.5", Math.abs(mid - 0.5) < 1e-9);
		// 低于首行速度 -> 取首行
		check("速度 0 取首行档1 = 0", mtrnext.sound.BveSoundConfig.sampleTable(table, 0.0, 1) == 0.0);
		// 高于末行速度 -> 保持末行
		check("超速取末行档3 = 0.5", Math.abs(mtrnext.sound.BveSoundConfig.sampleTable(table, 99.0, 3) - 0.5) < 1e-9);
		// 空表/越界档位 -> 0（防 NPE）
		check("空表 -> 0", mtrnext.sound.BveSoundConfig.sampleTable(new ArrayList<>(), 5.0, 0) == 0.0);
		check("负档位 -> 0", mtrnext.sound.BveSoundConfig.sampleTable(table, 5.0, -1) == 0.0);
	}

	/** 编组：连接面规则 + family 重联兜底 + 总长 */
	private static void testFormationRules() {
		System.out.println("[Formation]");
		final mtrnext.formation.Formation f = new mtrnext.formation.Formation();
		check("空编组总长 0", f.getTotalLength() == 0.0);

		f.cars.add(new mtrnext.formation.Formation.Car("ERIDU_DG1000A(M_F)", true));
		// 未载入车包（VehiclePackLoader 空）-> res==null -> 兜底长度 12
		check("单车无车包 -> 兜底 12", f.getTotalLength() == 12.0);
		// canAddCar 的 null 防线：待插车辆不存在时必须拒绝（防 NPE）
		check("canAddCar(null) 尾部拒绝", !f.canAddCar(1, null, true));
		check("canAddCar(null) 头部拒绝", !f.canAddCar(0, null, true));

		f.cars.add(new mtrnext.formation.Formation.Car("ERIDU_DG1000B(T)", false));
		check("双车总长 24", f.getTotalLength() == 24.0);

		// 移除后长度回落（编组编辑器 Clear 的语义）
		f.cars.remove(1);
		check("移除后回 12", f.getTotalLength() == 12.0);
	}

	private static void check(String name, boolean ok) {
		if (ok) {
			pass++;
			System.out.println("  ok   " + name);
		} else {
			fail++;
			System.out.println("  FAIL " + name);
		}
	}
}
