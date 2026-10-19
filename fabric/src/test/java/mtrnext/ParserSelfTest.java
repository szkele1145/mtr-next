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
		testVehicleResource();
		testPropertiesParts();
		testOutlinerGroupPath();
		System.out.println();
		System.out.println("PASS=" + pass + " FAIL=" + fail);
		if (fail > 0) {
			System.exit(1);
		}
	}

	/**
	 * [0.34.3] 4.0 properties parts[] 数据面解析。
	 * <p>
	 * 实测 ERIDU 包的 doorZMultiplier：men2=+11.0 / men3=−11.0（左右符号相反）。
	 * 缺省语义、畸形部件隔离、names[] 提取全部回归。
	 */
	private static void testPropertiesParts() {
		System.out.println("[Properties4xLoader.parseParts]");

		// --- ERIDU 实测结构：两个门部件 + 一个显示器部件 ---
		final com.google.gson.JsonObject root = json("""
				{ "parts": [
				  { "names": ["men2"], "positionDefinitions": ["71e75d38"], "condition": "NORMAL",
				    "renderStage": "INTERIOR", "type": "NORMAL", "doorXMultiplier": 0.0, "doorZMultiplier": 11.0,
				    "doorAnimationType": "STANDARD_SLOW" },
				  { "names": ["men3"], "condition": "NORMAL", "renderStage": "EXTERIOR", "type": "NORMAL",
				    "doorXMultiplier": 0.0, "doorZMultiplier": -11.0, "doorAnimationType": "STANDARD_SLOW" },
				  { "names": ["lcdp_s","lcdp_z_1"], "condition": "DOORS_OPENED", "renderStage": "EXTERIOR",
				    "type": "DISPLAY", "doorXMultiplier": 0.0, "doorZMultiplier": 0.0 } ] }
				""");
		final var parts = mtrnext.client.render.Properties4xLoader.parseParts(root);
		check("解析出 3 个部件", parts.size() == 3);
		check("men2 names 落位", parts.get(0).names.size() == 1 && "men2".equals(parts.get(0).names.get(0)));
		check("men2 doorZMultiplier +11.0", parts.get(0).doorZMultiplier == 11.0F);
		check("men3 doorZMultiplier -11.0 (与 men2 反号)", parts.get(1).doorZMultiplier == -11.0F);
		check("STANDARD_SLOW 落位", "STANDARD_SLOW".equals(parts.get(0).doorAnimationType));
		check("DISPLAY 条件 DOORS_OPENED", "DISPLAY".equals(parts.get(2).type)
				&& "DOORS_OPENED".equals(parts.get(2).condition));
		check("多 names 提取", parts.get(2).names.size() == 2);
		check("缺省 doorXMultiplier=0", parts.get(2).doorXMultiplier == 0.0F);

		// --- 缺省值语义 ---
		final var min = mtrnext.client.render.Properties4xLoader.parseParts(json(
				"{\"parts\":[{\"names\":[\"x\"]}]}"));
		check("缺省动画类型 STANDARD", "STANDARD".equals(min.get(0).doorAnimationType));
		check("缺省 condition NORMAL", "NORMAL".equals(min.get(0).condition));
		check("缺省 renderStage EXTERIOR", "EXTERIOR".equals(min.get(0).renderStage));
		check("缺省 type NORMAL", "NORMAL".equals(min.get(0).type));

		// --- 畸形隔离：数组内非对象元素跳过，其余部件存活 ---
		final var mixed = mtrnext.client.render.Properties4xLoader.parseParts(json(
				"{\"parts\":[\"junk\",null,{\"names\":[\"ok\"]},42]}"));
		check("畸形部件跳过 -> 1 存活", mixed.size() == 1 && "ok".equals(mixed.get(0).names.get(0)));

		// --- 兜底：无 parts / 非数组 / null ---
		check("无 parts -> 空表", mtrnext.client.render.Properties4xLoader.parseParts(json("{}")).isEmpty());
		check("parts 非数组 -> 空表", mtrnext.client.render.Properties4xLoader.parseParts(
				json("{\"parts\":\"oops\"}")).isEmpty());
		check("names 非数组忽略 -> 部件仍创建", mtrnext.client.render.Properties4xLoader.parseParts(
				json("{\"parts\":[{\"names\":\"notarray\"}]}")).size() == 1);
	}

	/**
	 * [0.34.3] outliner → groupPath 解析 + isDoorByGroup 匹配。
	 * <p>
	 * 真实 4.0 包的门归属全在 outliner 组名（element.name 全是 cube），
	 * parts[].names 匹配组名。用模拟的混合 children 结构回归：
	 * 嵌套组、uuid 引用、无 outliner 的退化路径。
	 */
	private static void testOutlinerGroupPath() {
		System.out.println("[BbModelLoader.walkOutliner / isDoorByGroup]");

		// --- 模拟 bbmodel：嵌套 outliner + 混合 children ---
		final com.google.gson.JsonObject root = json("""
				{ "elements": [
				  { "uuid": "e1", "name": "cube", "from": [0,0,0], "to": [1,1,1] },
				  { "uuid": "e2", "name": "cube", "from": [0,0,0], "to": [1,1,1] },
				  { "uuid": "e3", "name": "cube", "from": [0,0,0], "to": [1,1,1] },
				  { "uuid": "e4", "name": "door_left_bottom", "from": [0,0,0], "to": [1,1,1] } ],
				  "outliner": [
				    { "name": "body", "children": [
				      { "name": "men2", "children": ["e1", "e2"] } ] },
				    { "name": "men3", "children": ["e3"] },
				    "e4" ] }
				""");
		final var boxes = mtrnext.client.render.BbModelLoader.parseForTest(root);
		check("4 个盒全部解析", boxes.size() == 4);
		check("e1 组名链 body→men2", boxes.get(0).groupPath.size() == 2
				&& "body".equals(boxes.get(0).groupPath.get(0))
				&& "men2".equals(boxes.get(0).groupPath.get(1)));
		check("e2 组名链同 e1", boxes.get(1).groupPath.size() == 2
				&& "men2".equals(boxes.get(1).groupPath.get(1)));
		check("e3 单层组 men3", boxes.get(2).groupPath.size() == 1
				&& "men3".equals(boxes.get(2).groupPath.get(0)));
		check("e4 顶层裸 uuid -> 空组链", boxes.get(3).groupPath.isEmpty());

		// --- isDoorByGroup：parts[].names 匹配祖先组名 ---
		final var parts = mtrnext.client.render.Properties4xLoader.parseParts(json("""
				{ "parts": [
				  { "names": ["men2"], "doorZMultiplier": 11.0 },
				  { "names": ["men3"], "doorZMultiplier": -11.0 } ] }
				"""));
		check("men2 组下盒判定为门", mtrnext.client.render.BbModelLoader.isDoorByGroup(boxes.get(0), parts));
		check("men3 组下盒判定为门", mtrnext.client.render.BbModelLoader.isDoorByGroup(boxes.get(2), parts));
		check("无组链的 door_left_bottom 走旧启发式", mtrnext.client.render.BbModelLoader.isDoorByGroup(boxes.get(3), parts));
		check("零倍率部件不参与门判定", !mtrnext.client.render.BbModelLoader.isDoorByGroup(boxes.get(0),
				mtrnext.client.render.Properties4xLoader.parseParts(json(
						"{\"parts\":[{\"names\":[\"men2\"],\"doorZMultiplier\":0.0}]}"))));
		check("parts=null 走旧启发式", mtrnext.client.render.BbModelLoader.isDoorByGroup(boxes.get(3), null));
		check("box=null 安全返回 false", !mtrnext.client.render.BbModelLoader.isDoorByGroup(null, parts));

		// --- 退化：无 outliner 的 bbmodel（3.x 导出）→ 全部空组链 ---
		final var noOutliner = mtrnext.client.render.BbModelLoader.parseForTest(json(
				"{\"elements\":[{\"uuid\":\"x1\",\"name\":\"door\",\"from\":[0,0,0],\"to\":[1,1,1]}]}"));
		check("无 outliner -> 空组链", noOutliner.get(0).groupPath.isEmpty());
	}

	/**
	 * [0.32] 4.0 车包车辆定义解析。
	 * <p>
	 * {@code VehicleResource.fromJson(o, null)} 在 manager==null 时跳过所有
	 * 资源引用（bbmodel/texture/positionDefinitions），只解析纯数据字段——
	 * 因此整条 4.x 车辆数据面可以脱离客户端离线回归。
	 */
	private static void testVehicleResource() {
		System.out.println("[VehicleResource.fromJson]");

		// --- ERIDU-DG1000A 风格的真实结构（含多模型层 + 连接面语义）---
		final com.google.gson.JsonObject full = json("""
				{ "id": "ERIDU_DG1000A", "name": "ERIDU DG1000A", "color": "D67C00",
				  "transportMode": "TRAIN", "length": 12.0, "width": 3.0,
				  "bogie1Position": -4.0, "bogie2Position": 4.0,
				  "couplingPadding1": 0.5, "couplingPadding2": -0.5,
				  "hasGangway1": false, "hasGangway2": true,
				  "hasBarrier1": false, "hasBarrier2": true,
				  "bveSoundBaseResource": "mtr:eridu",
				  "legacyRiderOffset": 0.25,
				  "models": [
				    { "modelResource": "mtr:body.bbmodel", "textureResource": "mtr:body.png",
				      "modelPropertiesResource": "mtr:properties_1.json", "flipTextureV": false },
				    { "modelResource": "mtr:inner.bbmodel", "textureResource": "mtr:inner.png",
				      "modelPropertiesResource": "mtr:properties_2.json" }
				  ],
				  "bogie1Models": [ { "modelResource": "mtr:b1.bbmodel" } ],
				  "bogie2Models": [ { "modelResource": "mtr:b2.bbmodel" } ] }
				""");
		final mtrnext.pack.VehicleResource v = mtrnext.pack.VehicleResource.fromJson(full, null);
		check("正常车定义解析成功", v != null);
		check("id 落位", v != null && "ERIDU_DG1000A".equals(v.id));
		check("name 落位", v != null && "ERIDU DG1000A".equals(v.name));
		check("color D67C00 -> 0xD67C00", v != null && v.color == 0xD67C00);
		check("transportMode TRAIN", v != null && "TRAIN".equals(v.transportMode));
		check("length/width", v != null && v.length == 12.0 && v.width == 3.0);
		check("bogie1/2Position 含负值", v != null && v.bogie1Position == -4.0 && v.bogie2Position == 4.0);
		check("couplingPadding 含负值", v != null && v.couplingPadding1 == 0.5 && v.couplingPadding2 == -0.5);
		check("连接面 gangway 1=false 2=true", v != null && !v.hasGangway1 && v.hasGangway2);
		check("连接面 barrier 1=false 2=true", v != null && !v.hasBarrier1 && v.hasBarrier2);
		check("bveSoundBaseResource", v != null && "mtr:eridu".equals(v.bveSoundBaseResource));
		check("legacyRiderOffset", v != null && v.legacyRiderOffset == 0.25);
		check("models 两层", v != null && v.models.size() == 2);
		check("模型层字段落位", v != null
				&& "mtr:body.bbmodel".equals(v.models.get(0).modelResource)
				&& "mtr:body.png".equals(v.models.get(0).textureResource)
				&& "mtr:properties_1.json".equals(v.models.get(0).propertiesResource));
		check("flipTextureV 显式 false", v != null && !v.models.get(0).flipTextureV);
		check("flipTextureV 缺省 true", v != null && v.models.get(1).flipTextureV);
		check("bogie1/bogie2Models 各自 1 层", v != null
				&& v.bogie1Models.size() == 1 && v.bogie2Models.size() == 1);
		check("manager=null 时 positionDefinitions 跳过(空表)", v != null && v.positionDefinitionNames.isEmpty());

		// --- [0.32] id 是注册表唯一键：缺失/空白必须拒绝而非塌成 "unknown" ---
		check("缺 id -> null", mtrnext.pack.VehicleResource.fromJson(json("{\"name\":\"x\"}"), null) == null);
		check("id 空白 -> null", mtrnext.pack.VehicleResource.fromJson(json("{\"id\":\"   \"}"), null) == null);
		check("id 空串 -> null", mtrnext.pack.VehicleResource.fromJson(json("{\"id\":\"\"}"), null) == null);
		check("null 输入 -> null", mtrnext.pack.VehicleResource.fromJson(null, null) == null);

		// --- 缺省值语义 ---
		final mtrnext.pack.VehicleResource min = mtrnext.pack.VehicleResource.fromJson(
				json("{\"id\":\"MIN\"}"), null);
		check("最小定义 name 回退为 id", min != null && "MIN".equals(min.name));
		check("最小定义 color 缺省 0xFFFFFF", min != null && min.color == 0xFFFFFF);
		check("最小定义 transportMode 缺省 TRAIN", min != null && "TRAIN".equals(min.transportMode));
		check("最小定义 length 缺省 12", min != null && min.length == 12.0);
		check("最小定义 bogie 缺省 ±4", min != null && min.bogie1Position == -4.0 && min.bogie2Position == 4.0);
		check("最小定义连接面缺省全 false", min != null
				&& !min.hasGangway1 && !min.hasGangway2 && !min.hasBarrier1 && !min.hasBarrier2);
		check("最小定义 models 为空表(非 null)", min != null && min.models.isEmpty());
		check("models 非数组 -> 空表", mtrnext.pack.VehicleResource.fromJson(
				json("{\"id\":\"A\",\"models\":\"oops\"}"), null).models.isEmpty());
		// [0.32] 数组内非对象元素按层跳过（旧写法会让整辆车报废）
		final mtrnext.pack.VehicleResource mixed = mtrnext.pack.VehicleResource.fromJson(json(
				"{\"id\":\"A\",\"models\":[\"junk\",{\"modelResource\":\"mtr:ok.bbmodel\"},42,null]}"), null);
		check("models 含 3 个畸形元素 -> 保留 1 个合法层", mixed != null && mixed.models.size() == 1);
		check("保留的是合法层", mixed != null
				&& "mtr:ok.bbmodel".equals(mixed.models.get(0).modelResource));
		check("models 全畸形 -> 空表且车仍可用", mtrnext.pack.VehicleResource.fromJson(
				json("{\"id\":\"A\",\"models\":[\"x\",null]}"), null).models.isEmpty());

		// --- [0.32] color 位宽/前缀/非法字符：此前 9 位会静默截断成诡异颜色 ---
		check("color '#FF8800' 前缀容错", colorOf("{\"id\":\"C\",\"color\":\"#FF8800\"}") == 0xFF8800);
		check("color ' ff8800 ' 空白容错", colorOf("{\"id\":\"C\",\"color\":\" ff8800 \"}") == 0xFF8800);
		check("color 9 位 'FFFFFFFFF' -> 回退白", colorOf("{\"id\":\"C\",\"color\":\"FFFFFFFFF\"}") == 0xFFFFFF);
		check("color 非十六进制 'ZZZ' -> 回退白", colorOf("{\"id\":\"C\",\"color\":\"ZZZ\"}") == 0xFFFFFF);
		check("color 空串 -> 回退白", colorOf("{\"id\":\"C\",\"color\":\"\"}") == 0xFFFFFF);
		check("color '00' -> 0(纯黑, 非回退)", colorOf("{\"id\":\"C\",\"color\":\"00\"}") == 0x0);

		// --- 数值字段畸形：文档化行为 = 抛 NumberFormatException（由调用方按车隔离）---
		boolean threw = false;
		try {
			mtrnext.pack.VehicleResource.fromJson(json("{\"id\":\"BAD\",\"length\":\"abc\"}"), null);
		} catch (NumberFormatException e) {
			threw = true;
		}
		check("length='abc' 抛 NumberFormatException(交调用方隔离)", threw);
	}

	/** 取 color 字段的解析结果（非法定义返回哨兵 -1） */
	private static int colorOf(String vehicleJson) {
		final mtrnext.pack.VehicleResource r = mtrnext.pack.VehicleResource.fromJson(json(vehicleJson), null);
		return r == null ? -1 : r.color;
	}

	private static com.google.gson.JsonObject json(String s) {
		return com.google.gson.JsonParser.parseString(s).getAsJsonObject();
	}

	/** sound.cfg 的 INI 子集解析：七段全部落位 + 空值语义 */
	private static void testSoundCfg() {
		System.out.println("[BveSoundConfig.parseCfg]");
		final List<String> lines = new ArrayList<>(List.of(
				"BveTs Vehicle Sound 3.01:utf-8",
				"bvets Vehicle Sound 3.01:utf-8",
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
