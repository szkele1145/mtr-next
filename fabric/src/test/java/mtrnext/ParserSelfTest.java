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
		testDoorAnimation4x();
		testBbModelScaleAndUv();
		testDoorAnnotation();
		testDoorOverlapDetection();
		testPositionDefinitionsMirror();
		testRealPackMirror();
		testDoorTimingWindows();
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

	/**
	 * [0.35] 上游 DoorAnimationType 曲线回归。
	 * <p>
	 * 基准：Minecraft-Transit-Railway/Minecraft-Transit-Railway（MIT，master）
	 * {@code fabric/src/main/java/org/mtr/mod/resource/DoorAnimationType.java}。
	 * 期望值由上游分支逐条手算得出，锁定移植一致性。
	 */
	private static void testDoorAnimation4x() {
		System.out.println("[DoorAnimation4x（上游 DoorAnimationType 移植）]");
		final mtrnext.client.render.DoorAnimation4x std = mtrnext.client.render.DoorAnimation4x.STANDARD;
		final mtrnext.client.render.DoorAnimation4x stdSlow = mtrnext.client.render.DoorAnimation4x.STANDARD_SLOW;
		final mtrnext.client.render.DoorAnimation4x constant = mtrnext.client.render.DoorAnimation4x.CONSTANT;
		final mtrnext.client.render.DoorAnimation4x plugFast = mtrnext.client.render.DoorAnimation4x.PLUG_FAST;
		final mtrnext.client.render.DoorAnimation4x plugSlow = mtrnext.client.render.DoorAnimation4x.PLUG_SLOW;
		final mtrnext.client.render.DoorAnimation4x plugSlow2 = mtrnext.client.render.DoorAnimation4x.PLUG_SLOW_2;
		final mtrnext.client.render.DoorAnimation4x plugSlow3 = mtrnext.client.render.DoorAnimation4x.PLUG_SLOW_3;

		// --- 零倍率 -> 零位移（上游 multiplier==0 直接返回 0） ---
		check("STANDARD 零倍率 X=0", std.getDoorAnimationX(0.0F, false, 0.5F) == 0.0F);
		check("STANDARD 零倍率 Z=0", std.getDoorAnimationZ(0.0F, false, 0.5F, true) == 0.0F);

		// --- STANDARD / STANDARD_SLOW：Z 位移 = smoothEnds(0, max, 0, 0.5, t)
		//     （余弦缓动，t=0.25 恰好中点 -> max/2；t>=0.5 饱和到 max） ---
		check("STANDARD Z(t=0.25)=5.5", near(std.getDoorAnimationZ(11.0F, false, 0.25F, true), 5.5F));
		check("STANDARD Z(t=0.5)=11", near(std.getDoorAnimationZ(11.0F, false, 0.5F, true), 11.0F));
		check("STANDARD Z(t=1)=11", near(std.getDoorAnimationZ(11.0F, false, 1.0F, true), 11.0F));
		check("STANDARD Z(t=0)=0", near(std.getDoorAnimationZ(11.0F, false, 0.0F, true), 0.0F));
		check("STANDARD_SLOW Z(t=0.25)=5.5", near(stdSlow.getDoorAnimationZ(11.0F, false, 0.25F, true), 5.5F));
		// 负倍率 -> 反向滑动（ERIDU men3 z=-11）
		check("STANDARD 负倍率 Z(t=1)=-11", near(std.getDoorAnimationZ(-11.0F, false, 1.0F, true), -11.0F));

		// --- CONSTANT：Z = 倍率 × clamp(t/0.5) ---
		check("CONSTANT Z(t=0.25)=5.5", near(constant.getDoorAnimationZ(11.0F, false, 0.25F, true), 5.5F));
		check("CONSTANT Z(t=0.5)=11", near(constant.getDoorAnimationZ(11.0F, false, 0.5F, true), 11.0F));
		check("CONSTANT Z(t=1) 饱和=11", near(constant.getDoorAnimationZ(11.0F, false, 1.0F, true), 11.0F));

		// --- PLUG 类：X 外摆 ---
		// PLUG_FAST/PLUG_SLOW_2/PLUG_SLOW_3: time<0.05 -> t*20*mult+0.05；否则 mult+0.05
		check("PLUG_FAST X(t=0)=0.05", near(plugFast.getDoorAnimationX(11.0F, false, 0.0F), 0.05F));
		check("PLUG_FAST X(t=0.05)=11.05", near(plugFast.getDoorAnimationX(11.0F, false, 0.05F), 11.05F));
		check("PLUG_FAST X(t=1)=11.05", near(plugFast.getDoorAnimationX(11.0F, false, 1.0F), 11.05F));
		check("PLUG_SLOW_3 X(t=1)=11.05", near(plugSlow3.getDoorAnimationX(11.0F, false, 1.0F), 11.05F));
		check("PLUG_SLOW_2 X(t=1)=11.05", near(plugSlow2.getDoorAnimationX(11.0F, false, 1.0F), 11.05F));
		// PLUG_SLOW: smoothEnds(0.05, mult+0.05, 0, 0.1, t) -> 起点≈0.05
		check("PLUG_SLOW X(t=0)≈0.05", near(plugSlow.getDoorAnimationX(11.0F, false, 0.0F), 0.05F, 0.02F));
		check("PLUG_SLOW X(t=1)=11.05", near(plugSlow.getDoorAnimationX(11.0F, false, 1.0F), 11.05F, 0.05F));
		// 非塞拉门 X 恒 0
		check("STANDARD X 恒 0", std.getDoorAnimationX(11.0F, false, 0.5F) == 0.0F);
		check("CONSTANT X 恒 0", constant.getDoorAnimationX(11.0F, false, 0.5F) == 0.0F);

		// --- flipped 语义：copySign(abs, (flipped?-1:1)*mult) ---
		// 正倍率 + flipped -> 整体反号
		check("flipped 反号(STANDARD Z)",
				near(std.getDoorAnimationZ(11.0F, false, 1.0F, true), -std.getDoorAnimationZ(11.0F, true, 1.0F, true)));
		check("flipped 反号(PLUG_FAST X)",
				near(plugFast.getDoorAnimationX(11.0F, false, 1.0F), -plugFast.getDoorAnimationX(11.0F, true, 1.0F)));
		// 负倍率 + flipped -> 变正
		check("负倍率+flipped 变正",
				near(std.getDoorAnimationZ(-11.0F, true, 1.0F, true), 11.0F));

		// --- PLUG_Z 分段：PLUG_SLOW_2/3 的 smoothEnds 区间不同 ---
		// PLUG_SLOW_2: smoothEnds(-max, max, -0.75, 0.75, t) -> t=0 落在区间内，
		// 余弦中点值 = (start+end)/2 = 0（start=-11, end=11 对称）
		check("PLUG_SLOW_2 Z(t=0)=0", near(plugSlow2.getDoorAnimationZ(11.0F, false, 0.0F, true), 0.0F, 0.05F));
		// t>=0.75 饱和到 endValue = +11
		check("PLUG_SLOW_2 Z(t=1)=11", near(plugSlow2.getDoorAnimationZ(11.0F, false, 1.0F, true), 11.0F));
		// PLUG_SLOW_3: smoothEnds(-max, max, -1, 1, t) -> t=1 仍在区间内
		check("PLUG_SLOW_3 Z(t=1)≈11", near(plugSlow3.getDoorAnimationZ(11.0F, false, 1.0F, true), 11.0F, 0.5F));

		// --- 解析：上游枚举名 -> 本地枚举 ---
		check("parse STANDARD_SLOW", mtrnext.client.render.DoorAnimation4x.parse("STANDARD_SLOW") == stdSlow);
		check("parse PLUG_SLOW_3", mtrnext.client.render.DoorAnimation4x.parse("PLUG_SLOW_3") == plugSlow3);
		check("parse 未知 -> STANDARD", mtrnext.client.render.DoorAnimation4x.parse("NO_SUCH") == std);
		check("parse null -> STANDARD", mtrnext.client.render.DoorAnimation4x.parse(null) == std);
		check("12 个枚举值齐全", mtrnext.client.render.DoorAnimation4x.values().length == 12);
	}

	/**
	 * [0.35] bbmodel 尺度换算 + UV 归一化回归。
	 * <p>
	 * 上游依据（MIT 上游 master）：
	 * {@code BlockbenchElement.setModelPart} —— 注释 "Normalize dimensions
	 * (16 Blockbench units = 1 Minecraft block)"，返回 Box 坐标全部 /16。
	 * UV：上游用 bbmodel 自身 resolution（BlockbenchModel.getTextureWidth/Height
	 * ← BlockbenchResolution），而非固定 24。
	 */
	private static void testBbModelScaleAndUv() {
		System.out.println("[BbModelLoader 尺度/UV]");

		// 16 BB 像素 = 1 格
		final var boxes = mtrnext.client.render.BbModelLoader.parseForTest(json("""
				{ "resolution": { "width": 512, "height": 512 },
				  "elements": [
				    { "uuid": "a", "name": "cube", "from": [16,0,32], "to": [32,16,48] } ] }
				"""));
		check("解析出 1 个盒", boxes.size() == 1);
		final var b = boxes.get(0);
		check("X 起点 16px -> 1 格", near(b.x1, 1.0F));
		check("X 终点 32px -> 2 格", near(b.x2, 2.0F));
		check("Y 起点 0px -> 0 格", near(b.y1, 0.0F));
		check("Y 终点 16px -> 1 格", near(b.y2, 1.0F));
		check("Z 起点 32px -> 2 格", near(b.z1, 2.0F));
		check("Z 终点 48px -> 3 格", near(b.z2, 3.0F));

		// UV 归一化除数 = resolution
		check("uvScaleU = 1/512", near(b.uvScaleU, 1.0F / 512.0F));
		check("uvScaleV = 1/512", near(b.uvScaleV, 1.0F / 512.0F));

		// 不同 resolution 的模型各自取自己的除数（ERIDU 实测 512/128/24/64）
		final var r128 = mtrnext.client.render.BbModelLoader.parseForTest(json(
				"{\"resolution\":{\"width\":128,\"height\":128},\"elements\":[{\"uuid\":\"a\",\"name\":\"cube\",\"from\":[0,0,0],\"to\":[16,16,16]}]}"));
		check("resolution 128 -> uvScaleU=1/128", near(r128.get(0).uvScaleU, 1.0F / 128.0F));

		// 缺 resolution -> 退化默认值（不崩）
		final var noRes = mtrnext.client.render.BbModelLoader.parseForTest(json(
				"{\"elements\":[{\"uuid\":\"a\",\"name\":\"cube\",\"from\":[0,0,0],\"to\":[16,16,16]}]}"));
		check("缺 resolution 不崩", noRes.size() == 1);
		check("缺 resolution -> uvScale=1/16", near(noRes.get(0).uvScaleU, 1.0F / 16.0F));
	}

	/**
	 * [0.35] 门参数标注回归（组名匹配 + 倍率/动画型写入）。
	 * <p>
	 * 上游依据：{@code ModelPropertiesPart.isDoor()} =
	 * {@code doorXMultiplier != 0 || doorZMultiplier != 0}；门归属由
	 * parts[].names 匹配模型部件名（4.0 pack 里即 outliner 组名）。
	 */
	private static void testDoorAnnotation() {
		System.out.println("[BbModelLoader 门标注]");

		final var boxes = mtrnext.client.render.BbModelLoader.parseForTest(json("""
				{ "resolution": { "width": 512, "height": 512 },
				  "elements": [
				    { "uuid": "e1", "name": "cube", "from": [304,0,0],  "to": [320,320,1024] },
				    { "uuid": "e2", "name": "cube", "from": [320,0,0],  "to": [336,320,1024] },
				    { "uuid": "e3", "name": "cube", "from": [0,0,0],    "to": [16,16,16] } ],
				  "outliner": [
				    { "name": "door", "children": [
				      { "name": "men2", "children": ["e1"] },
				      { "name": "men3", "children": ["e2"] } ] },
				    "e3" ] }
				"""));
		check("解析出 3 个盒", boxes.size() == 3);

		final var parts = mtrnext.client.render.Properties4xLoader.parseParts(json("""
				{ "parts": [
				  { "names": ["men2"], "doorZMultiplier": 11.0,  "doorAnimationType": "STANDARD_SLOW" },
				  { "names": ["men3"], "doorZMultiplier": -11.0, "doorAnimationType": "STANDARD_SLOW" } ] }
				"""));
		final var ann = mtrnext.client.render.BbModelLoader.annotateForTest(boxes, parts);
		check("标注后仍是 3 个盒", ann.size() == 3);

		// men2 -> z 倍率 +11，动画型 STANDARD_SLOW
		check("men2 盒 z 倍率=11", near(ann.get(0).doorZMult, 11.0F));
		check("men2 盒 x 倍率=0", ann.get(0).doorXMult == 0.0F);
		check("men2 盒动画型=STANDARD_SLOW",
				ann.get(0).doorAnim == mtrnext.client.render.DoorAnimation4x.STANDARD_SLOW);
		check("men2 盒是可动画门", ann.get(0).isAnimatedDoor());

		// men3 -> z 倍率 -11
		check("men3 盒 z 倍率=-11", near(ann.get(1).doorZMult, -11.0F));
		check("men3 盒动画型=STANDARD_SLOW",
				ann.get(1).doorAnim == mtrnext.client.render.DoorAnimation4x.STANDARD_SLOW);

		// 无组裸 uuid 的盒 -> 非门
		check("裸 uuid 盒非门", ann.get(2).doorAnim == null);
		check("裸 uuid 盒不是可动画门", !ann.get(2).isAnimatedDoor());
		check("裸 uuid 盒倍率清零", ann.get(2).doorZMult == 0.0F && ann.get(2).doorXMult == 0.0F);

		// 零倍率部件不参与门判定（上游 isDoor()）
		final var zeroParts = mtrnext.client.render.Properties4xLoader.parseParts(json(
				"{\"parts\":[{\"names\":[\"men2\"],\"doorZMultiplier\":0.0,\"doorXMultiplier\":0.0}]}"));
		final var ann2 = mtrnext.client.render.BbModelLoader.annotateForTest(
				mtrnext.client.render.BbModelLoader.parseForTest(json("""
						{ "elements": [ { "uuid": "e1", "name": "cube", "from": [0,0,0], "to": [16,16,16] } ],
						  "outliner": [ { "name": "men2", "children": ["e1"] } ] }
						""")), zeroParts);
		check("零倍率部件 -> 非门", ann2.get(0).doorAnim == null);

		// X-only 门（塞拉门）也要被标注（上游 isDoor() 判 X 或 Z）
		final var xParts = mtrnext.client.render.Properties4xLoader.parseParts(json(
				"{\"parts\":[{\"names\":[\"men2\"],\"doorXMultiplier\":11.0,\"doorZMultiplier\":0.0,\"doorAnimationType\":\"PLUG_FAST\"}]}"));
		final var ann3 = mtrnext.client.render.BbModelLoader.annotateForTest(
				mtrnext.client.render.BbModelLoader.parseForTest(json("""
						{ "elements": [ { "uuid": "e1", "name": "cube", "from": [0,0,0], "to": [16,16,16] } ],
						  "outliner": [ { "name": "men2", "children": ["e1"] } ] }
						""")), xParts);
		check("X-only 门被标注", ann3.get(0).doorAnim == mtrnext.client.render.DoorAnimation4x.PLUG_FAST);
		check("X-only 门 x 倍率=11", near(ann3.get(0).doorXMult, 11.0F));
	}

	/**
	 * [0.35.2] 门重叠离线检测（上游 DynamicVehicleModel.testDoors 移植）。
	 * <p>
	 * 上游算法：模拟门从 t=0 到 t=1 走 5 个时间片，若两扇门"初始不相交、
	 * 中途相交"，说明门 X/Z 倍率配错 → 上游打 WARN。
	 * <p>
	 * 关键推论（本次实测得出）：<b>同向同量滑动的两扇门永远不会相撞</b>
	 * ——相对距离恒定。故能触发的错误配置是<b>倍率不等</b>（快门追上慢门）。
	 * 另：ERIDU 那种 +11/−11 反向对滑配置，初始包围盒本就相交
	 * （实测 men2 z 跨 [-56.8, 63.5]、men3 z 跨 [-59.5, 40.0]），
	 * 故上游的"初始相交则跳过"分支会直接放过——不误报。
	 */
	private static void testDoorOverlapDetection() {
		System.out.println("[BbModelLoader.detectDoorOverlaps（上游 testDoors 移植）]");

		// --- 场景 A：同向同量滑动 → 相对距离恒定 → 不该报（防误报） ---
		final var sameSpeed = mtrnext.client.render.BbModelLoader.parseForTest(json("""
				{ "elements": [
				  { "uuid": "d1", "name": "cube", "from": [304,0,0],   "to": [320,64,124] },
				  { "uuid": "d2", "name": "cube", "from": [304,0,128], "to": [320,64,252] } ],
				  "outliner": [
				    { "name": "men2", "children": ["d1"] },
				    { "name": "men3", "children": ["d2"] } ] }
				"""));
		final var sameParts = mtrnext.client.render.Properties4xLoader.parseParts(json("""
				{ "parts": [
				  { "names": ["men2"], "doorZMultiplier": 11.0,  "doorAnimationType": "STANDARD" },
				  { "names": ["men3"], "doorZMultiplier": 11.0,  "doorAnimationType": "STANDARD" } ] }
				"""));
		final var sameBoxes = mtrnext.client.render.BbModelLoader.annotateForTest(sameSpeed, sameParts);
		check("同向同量滑动（相对距离恒定）不报",
				mtrnext.client.render.BbModelLoader.detectDoorOverlaps(sameBoxes).isEmpty());

		// --- 场景 B：倍率不等（快门追上慢门）→ 应报重叠 ---
		// d1 z=[0,10]格, d2 z=[12,22]格（初始不相交）
		// d1 滑 +40px=2.5格, d2 滑 +1px → t=1 时 d1=[2.5,12.5] 与 d2=[12.06,22.06] 相交
		final var unequal = mtrnext.client.render.BbModelLoader.parseForTest(json("""
				{ "elements": [
				  { "uuid": "d1", "name": "cube", "from": [304,0,0],   "to": [320,64,160] },
				  { "uuid": "d2", "name": "cube", "from": [304,0,192], "to": [320,64,352] } ],
				  "outliner": [
				    { "name": "men2", "children": ["d1"] },
				    { "name": "men3", "children": ["d2"] } ] }
				"""));
		final var unequalParts = mtrnext.client.render.Properties4xLoader.parseParts(json("""
				{ "parts": [
				  { "names": ["men2"], "doorZMultiplier": 40.0, "doorAnimationType": "STANDARD" },
				  { "names": ["men3"], "doorZMultiplier": 1.0,  "doorAnimationType": "STANDARD" } ] }
				"""));
		final var unequalBoxes = mtrnext.client.render.BbModelLoader.annotateForTest(unequal, unequalParts);
		check("异向初始不相交", !unequalBoxes.get(0).intersects(unequalBoxes.get(1), 0, 0, 0, 0));
		final var overlaps = mtrnext.client.render.BbModelLoader.detectDoorOverlaps(unequalBoxes);
		check("倍率不等追尾被检出", overlaps.size() == 1);
		check("报错信息含组名", overlaps.size() == 1
				&& overlaps.get(0).contains("men2") && overlaps.get(0).contains("men3"));

		// --- 场景 C：ERIDU 真实配置（+11/−11 反向对滑，初始包围盒相交）→ 不报 ---
		// 用真实实测的 ERIDU 组 Z 跨度：men2 [-56.8, 63.5]、men3 [-59.5, 40.0]（像素）
		final var eridu = mtrnext.client.render.BbModelLoader.parseForTest(json("""
				{ "elements": [
				  { "uuid": "d1", "name": "cube", "from": [304,0,-908], "to": [320,320,1016] },
				  { "uuid": "d2", "name": "cube", "from": [320,0,-952], "to": [336,320,640] } ],
				  "outliner": [
				    { "name": "men2", "children": ["d1"] },
				    { "name": "men3", "children": ["d2"] } ] }
				"""));
		final var eriduParts = mtrnext.client.render.Properties4xLoader.parseParts(json("""
				{ "parts": [
				  { "names": ["men2"], "doorZMultiplier": 11.0,  "doorAnimationType": "STANDARD_SLOW" },
				  { "names": ["men3"], "doorZMultiplier": -11.0, "doorAnimationType": "STANDARD_SLOW" } ] }
				"""));
		final var eriduBoxes = mtrnext.client.render.BbModelLoader.annotateForTest(eridu, eriduParts);
		check("ERIDU 真实几何初始包围盒相交",
				eriduBoxes.get(0).intersects(eriduBoxes.get(1), 0, 0, 0, 0));
		check("ERIDU 配置（+11/−11 对滑）不报重叠",
				mtrnext.client.render.BbModelLoader.detectDoorOverlaps(eriduBoxes).isEmpty());

		// --- 退化：单门 / null ---
		final var single = mtrnext.client.render.BbModelLoader.annotateForTest(
				mtrnext.client.render.BbModelLoader.parseForTest(json("""
						{ "elements": [ { "uuid": "d1", "name": "cube", "from": [0,0,0], "to": [16,16,16] } ],
						  "outliner": [ { "name": "men2", "children": ["d1"] } ] }
						""")), eriduParts);
		check("单门不报", mtrnext.client.render.BbModelLoader.detectDoorOverlaps(single).isEmpty());
		check("null 输入安全", mtrnext.client.render.BbModelLoader.detectDoorOverlaps(null).isEmpty());
	}

	/**
	 * [0.35.3] 位定义 → 镜像展开（上游 positionsFlipped 语义）。
	 * <p>
	 * 用真实 ERIDU 数据模式：men2 有两条 part——一条位定义只含 positions
	 * （正位），另一条只含 positionsFlipped（翻转位）。判定：
	 * 1) 顶层组 men2 的盒 mirrorNeeded=true（该组有 flipped 位）；
	 * 2) 只含 positionsFlipped 的 part 记录 doorFlipped=true →
	 *    门位移曲线反号（上游 copySign(abs,(flipped?-1:1)*mult)）。
	 */
	private static void testPositionDefinitionsMirror() {
		System.out.println("[位定义镜像展开（上游 positionsFlipped）]");

		// 位定义表：模拟 ERIDU definition_*.json 的一对定义
		final var posDefs = mtrnext.client.render.PositionDefinitions4xLoader.parseRoot(json("""
				{ "positionDefinitions": [
				  { "name": "defNormal",  "positions": [{"x":0.0,"y":0.0,"z":0.0}], "positionsFlipped": [] },
				  { "name": "defFlipped", "positions": [], "positionsFlipped": [{"x":0.0,"y":0.0,"z":0.0}] },
				  { "name": "defBoth",    "positions": [{"x":2.0,"y":0.0,"z":0.0}], "positionsFlipped": [{"x":2.0,"y":0.0,"z":0.0}] }
				] }
				"""));
		check("位定义解析数=3", posDefs.size() == 3);
		check("defNormal 有正位无翻转位", posDefs.get("defNormal").hasNormal()
				&& posDefs.get("defNormal").positionsFlipped.isEmpty());
		check("defFlipped 只含翻转位", posDefs.get("defFlipped").flippedOnly());
		check("defBoth 两位都有", posDefs.get("defBoth").hasNormal()
				&& !posDefs.get("defBoth").positionsFlipped.isEmpty());

		// bbmodel：men2 组一个盒（只建 +X 半边——真实 ERIDU 模式）
		final var boxes = mtrnext.client.render.BbModelLoader.parseForTest(json("""
				{ "elements": [
				  { "uuid": "m1", "name": "cube", "from": [304,0,0], "to": [320,64,128] } ],
				  "outliner": [ { "name": "men2", "children": ["m1"] } ] }
				"""));

		// properties：两条 men2 part（正位 + 翻转位），ERIDU 真实模式
		final var parts = mtrnext.client.render.Properties4xLoader.parseParts(json("""
				{ "parts": [
				  { "names": ["men2"], "doorZMultiplier": 11.0, "doorAnimationType": "STANDARD_SLOW",
				    "positionDefinitions": ["defNormal"] },
				  { "names": ["men2"], "doorZMultiplier": 11.0, "doorAnimationType": "STANDARD_SLOW",
				    "positionDefinitions": ["defFlipped"] } ] }
				"""));
		final var annotated = mtrnext.client.render.BbModelLoader.annotateForTest(boxes, parts, posDefs);
		check("镜像标注后盒数=1", annotated.size() == 1);
		check("men2 顶层组 mirrorNeeded=true", annotated.get(0).mirrorNeeded);
		check("men2 是动画门", annotated.get(0).isAnimatedDoor());
		check("men2 dz=+11", near(annotated.get(0).doorZMult, 11.0F));
		// 第一条匹配的 part 是 defNormal → doorFlipped=false（本体方向）
		check("本体 doorFlipped=false", !annotated.get(0).doorFlipped);

		// 只有翻转位 part 的场景：门位移应反号
		final var partsFlip = mtrnext.client.render.Properties4xLoader.parseParts(json("""
				{ "parts": [
				  { "names": ["men2"], "doorZMultiplier": 11.0, "doorAnimationType": "STANDARD_SLOW",
				    "positionDefinitions": ["defFlipped"] } ] }
				"""));
		final var annotatedFlip = mtrnext.client.render.BbModelLoader.annotateForTest(
				mtrnext.client.render.BbModelLoader.parseForTest(json("""
						{ "elements": [
						  { "uuid": "m1", "name": "cube", "from": [304,0,0], "to": [320,64,128] } ],
						  "outliner": [ { "name": "men2", "children": ["m1"] } ] }
						""")), partsFlip, posDefs);
		check("翻转位 part → doorFlipped=true", annotatedFlip.get(0).doorFlipped);
		check("翻转位 part → mirrorNeeded=true", annotatedFlip.get(0).mirrorNeeded);
		// 门曲线反号实证：STANDARD_SLOW 开门路径 fall through 到上游 default
		// （DoorAnimationType.java L152-153）→ smoothEnds(0, doorMax, 0, 0.5, t)。
		// doorMax=11，t=0.5：11*(1-cos(π))/2 = 11。
		// flipped=false → +11；flipped=true → copySign 反号 = -11。
		final var anim = annotatedFlip.get(0).doorAnim;
		check("flipped=false 曲线值 +11", near(anim.getDoorAnimationZ(11.0F, false, 0.5F, true), 11.0F, 0.001F));
		check("flipped=true 曲线值 −11", near(anim.getDoorAnimationZ(11.0F, true, 0.5F, true), -11.0F, 0.001F));
		// t=0.25（半程）：11*(1-cos(π/2))/2 = 5.5
		check("flipped=false t=0.25 曲线值 +5.5", near(anim.getDoorAnimationZ(11.0F, false, 0.25F, true), 5.5F, 0.001F));
		check("flipped=true t=0.25 曲线值 −5.5", near(anim.getDoorAnimationZ(11.0F, true, 0.25F, true), -5.5F, 0.001F));

		// 无位定义（defNormal-only part）→ 不镜像
		final var partsNormal = mtrnext.client.render.Properties4xLoader.parseParts(json("""
				{ "parts": [
				  { "names": ["men2"], "doorZMultiplier": 11.0, "doorAnimationType": "STANDARD_SLOW",
				    "positionDefinitions": ["defNormal"] } ] }
				"""));
		final var annotatedNormal = mtrnext.client.render.BbModelLoader.annotateForTest(
				mtrnext.client.render.BbModelLoader.parseForTest(json("""
						{ "elements": [
						  { "uuid": "m1", "name": "cube", "from": [304,0,0], "to": [320,64,128] } ],
						  "outliner": [ { "name": "men2", "children": ["m1"] } ] }
						""")), partsNormal, posDefs);
		check("正位 part（组内无翻转位）→ mirrorNeeded=false", !annotatedNormal.get(0).mirrorNeeded);
		check("正位 part → doorFlipped=false", !annotatedNormal.get(0).doorFlipped);

		// posDefs 为 null → 全部退化不镜像
		final var annotatedNull = mtrnext.client.render.BbModelLoader.annotateForTest(boxes, parts, null);
		check("null 位定义 → 不镜像", !annotatedNull.get(0).mirrorNeeded);
		check("null 位定义 → doorFlipped=false", !annotatedNull.get(0).doorFlipped);

		// [0.35.3] 对称组（两侧都有几何）→ 不画镜像副本。
		// 上游会给对称组也画 flipped 副本（与对面正本重叠）；mtr-next 跳过，
		// 避免 Z-fighting / 重复面。
		final var symBoxes = mtrnext.client.render.BbModelLoader.parseForTest(json("""
				{ "elements": [
				  { "uuid": "e1", "name": "cube", "from": [304,0,0], "to": [320,64,128] },
				  { "uuid": "e2", "name": "cube", "from": [-320,0,0], "to": [-304,64,128] } ],
				  "outliner": [ { "name": "end", "children": ["e1","e2"] } ] }
				"""));
		final var symParts = mtrnext.client.render.Properties4xLoader.parseParts(json("""
				{ "parts": [
				  { "names": ["end"], "positionDefinitions": ["defNormal"] },
				  { "names": ["end"], "positionDefinitions": ["defFlipped"] } ] }
				"""));
		final var symAnn = mtrnext.client.render.BbModelLoader.annotateForTest(symBoxes, symParts, posDefs);
		check("对称组 2 盒", symAnn.size() == 2);
		check("对称组 → 不镜像（避免与对面正本重叠）", !symAnn.get(0).mirrorNeeded && !symAnn.get(1).mirrorNeeded);

		// [0.35.3] 单侧 + 翻转位 → 镜像；单侧 + 仅正位 → 不镜像
		final var oneSidedNormalOnly = mtrnext.client.render.BbModelLoader.annotateForTest(
				mtrnext.client.render.BbModelLoader.parseForTest(json("""
						{ "elements": [
						  { "uuid": "m1", "name": "cube", "from": [304,0,0], "to": [320,64,128] } ],
						  "outliner": [ { "name": "men2", "children": ["m1"] } ] }
						""")), partsNormal, posDefs);
		check("单侧组但仅正位 → 不镜像", !oneSidedNormalOnly.get(0).mirrorNeeded);
	}

	/**
	 * [0.35.3] 真实 ERIDU 车包端到端验证（离线读文件，不依赖 Minecraft 运行时）。
	 * <p>
	 * 验证目标：
	 * ① eridudanguei 的 men2/men3/men2_n/men3_n/win/win_n/door/door_n 等
	 *    顶层组几何只建 +X 半边 → 应判为需镜像（补齐左半侧）；
	 * ② end/hand 等本身左右对称的组 → 不应镜像（避免与对面正本重叠）；
	 * ③ 门组 men2/men3 的 dz 倍率分别为 +11 / −11（ERIDU 实测）。
	 * <p>
	 * 车包路径不存在时整体跳过（不判失败）——保证裸机也能跑自测。
	 */
	private static void testRealPackMirror() {
		System.out.println("[真实 ERIDU 车包镜像判定]");
		final java.io.File packDir = new java.io.File(
				"F:\\vibe coding工程\\mtr-turbo\\build\\eridu-pack\\assets\\mtr");
		if (!packDir.isDirectory()) {
			System.out.println("  跳过：车包不存在 " + packDir.getAbsolutePath());
			return;
		}

		// 汇总所有 definition_*.json 的位定义
		final java.util.Map<String, mtrnext.client.render.PositionDefinitions4xLoader.PositionGroup> allDefs =
				new java.util.HashMap<>();
		final java.io.File[] defFiles = packDir.listFiles((d, n) -> n.startsWith("definition_") && n.endsWith(".json"));
		if (defFiles == null || defFiles.length == 0) {
			System.out.println("  跳过：无 definition_*.json");
			return;
		}
		for (final java.io.File f : defFiles) {
			final String txt = readUtf8(f);
			if (txt == null) {
				continue;
			}
			allDefs.putAll(mtrnext.client.render.PositionDefinitions4xLoader.parseRoot(json(txt)));
		}
		check("ERIDU 位定义数 ≥ 100", allDefs.size() >= 100);

		// 汇总所有 properties_*.json 的 parts
		final java.util.List<mtrnext.client.render.Properties4xLoader.PartInfo> allParts = new java.util.ArrayList<>();
		final java.io.File[] propFiles = packDir.listFiles((d, n) -> n.startsWith("properties_") && n.endsWith(".json"));
		for (final java.io.File f : propFiles) {
			final String txt = readUtf8(f);
			if (txt == null) {
				continue;
			}
			allParts.addAll(mtrnext.client.render.Properties4xLoader.parseParts(json(txt)));
		}
		check("ERIDU part 记录数 ≥ 100", allParts.size() >= 100);

		// 解析主车体 bbmodel（5 MB，970 元素）
		final java.io.File bb = new java.io.File(packDir, "eridudanguei.bbmodel");
		final String bbTxt = readUtf8(bb);
		if (bbTxt == null) {
			System.out.println("  跳过：eridudanguei.bbmodel 不可读");
			return;
		}
		final var boxes = mtrnext.client.render.BbModelLoader.parseForTest(json(bbTxt));
		check("ERIDU 主车体盒数 ≥ 900", boxes.size() >= 900);

		final var ann = mtrnext.client.render.BbModelLoader.annotateForTest(boxes, allParts, allDefs);

		// ① 门组：单侧 + 含翻转位 → 镜像
		final java.util.Map<String, java.util.List<mtrnext.client.render.BbModelLoader.BbBox>> byGroup =
				new java.util.HashMap<>();
		for (final var b : ann) {
			if (!b.topGroup.isEmpty()) {
				byGroup.computeIfAbsent(b.topGroup, k -> new java.util.ArrayList<>()).add(b);
			}
		}
		for (final String g : new String[]{"men2", "men3", "men2_n", "men3_n", "win", "win_n", "door", "door_n"}) {
			final var gb = byGroup.get(g);
			if (gb == null) {
				check("ERIDU 组 " + g + " 存在", false);
				continue;
			}
			check("ERIDU " + g + " 是单侧组", gb.get(0).singleSidedGroup);
			check("ERIDU " + g + " 需镜像", gb.get(0).mirrorNeeded);
		}

		// ② 对称组 end/hand → 不镜像
		for (final String g : new String[]{"end", "end_n", "hand", "hand_n"}) {
			final var gb = byGroup.get(g);
			if (gb == null) {
				continue;
			}
			check("ERIDU " + g + " 是双侧组", !gb.get(0).singleSidedGroup);
			check("ERIDU " + g + " 不镜像", !gb.get(0).mirrorNeeded);
		}

		// ③ 门倍率：men2 dz=+11，men3 dz=−11（ERIDU 实测）
		final var men2 = byGroup.get("men2");
		final var men3 = byGroup.get("men3");
		if (men2 != null) {
			check("ERIDU men2 dz=+11", near(men2.get(0).doorZMult, 11.0F, 0.001F));
			check("ERIDU men2 是动画门", men2.get(0).isAnimatedDoor());
		}
		if (men3 != null) {
			check("ERIDU men3 dz=−11", near(men3.get(0).doorZMult, -11.0F, 0.001F));
		}

		// ④ 镜像计数：需镜像的组数应在合理区间（实测 64 个含翻转位组，
		//    其中单侧组约 30 个）
		int mirrorGroups = 0;
		for (final var e : byGroup.entrySet()) {
			if (e.getValue().get(0).mirrorNeeded) {
				mirrorGroups++;
			}
		}
		System.out.println("  需镜像的顶层组数：" + mirrorGroups + " / 总组数 " + byGroup.size());
		check("需镜像组数在 10..60", mirrorGroups >= 10 && mirrorGroups <= 60);
	}

	/** 读 UTF-8 文本（失败返回 null） */
	private static String readUtf8(java.io.File f) {
		try {
			return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
		} catch (final Exception e) {
			return null;
		}
	}

	/**
	 * [0.35.4] 门行程时序窗口 + 闪烁（上游 modelPropertiesPart.json schema 权威字段）。
	 * <p>
	 * 上游 ModelPropertiesPart.java:342-345：
	 * {@code shouldRender = (from==0 && until==0) || isBetween(|doorValue|*DOOR_MOVE_TIME, from, until)}
	 * 上游 core/data/Vehicle.java:59：{@code DOOR_MOVE_TIME = 3200}
	 * 上游 core/tool/Utilities.java:61-63：{@code isBetween(v,a,b) = v>=min(a,b) && v<=max(a,b)}
	 * <p>
	 * 用 ERIDU 真实取值：doordengguan_n from=100；doordengshan_n from=50 +
	 * flash 500/500；lcdp_dooryincang from=1；lcdp_doorshanshuo flash 800/800。
	 */
	private static void testDoorTimingWindows() {
		System.out.println("[门行程时序窗口 + 闪烁（上游 schema 字段）]");

		// 无窗口 → 恒渲染
		final var noWindow = mtrnext.client.render.Properties4xLoader.parseParts(json("""
				{ "parts": [ { "names": ["men2"], "doorZMultiplier": 11.0 } ] }
				""")).get(0);
		check("无时序字段 → 开门时恒渲染", noWindow.shouldRenderAt(0.0, true));
		check("无时序字段 → 关门时恒渲染", noWindow.shouldRenderAt(1.0, false));

		// ERIDU doordengguan_n：from=100 双向，until=0 → 窗口 [0,100] ms
		final var guang = mtrnext.client.render.Properties4xLoader.parseParts(json("""
				{ "parts": [ { "names": ["doordengguan_n"],
				    "renderFromOpeningDoorTime": 100, "renderFromClosingDoorTime": 100 } ] }
				""")).get(0);
		check("from=100 被解析", guang.renderFromOpeningDoorTime == 100 && guang.renderFromClosingDoorTime == 100);
		// 门开度 0 → 行程 0 ms ∈ [0,100] → 渲染
		check("门全关(0ms) 在窗口内", guang.shouldRenderAt(0.0, true));
		// 行程 100 ms → doorValue = 100/3200 = 0.03125 → 恰在边界 → 渲染
		check("行程 100ms 边界内", guang.shouldRenderAt(100.0 / 3200.0, true));
		// 行程 200 ms → doorValue = 0.0625 → 超出 → 不渲染
		check("行程 200ms 超出窗口", !guang.shouldRenderAt(200.0 / 3200.0, true));
		// 门全开 → 3200 ms → 远超 → 不渲染
		check("门全开(3200ms) 超出窗口", !guang.shouldRenderAt(1.0, true));
		// 关门方向用 closing 字段（此处也是 100）
		check("关门方向同样受限", !guang.shouldRenderAt(1.0, false));

		// ERIDU doordengshan_n：仅开门 from=50（closing 全 0 → 关门时恒渲染）
		final var shan = mtrnext.client.render.Properties4xLoader.parseParts(json("""
				{ "parts": [ { "names": ["doordengshan_n"], "renderFromOpeningDoorTime": 50 } ] }
				""")).get(0);
		check("from=50 被解析", shan.renderFromOpeningDoorTime == 50);
		check("开门 行程 40ms 在窗口内", shan.shouldRenderAt(40.0 / 3200.0, true));
		check("开门 行程 60ms 超出", !shan.shouldRenderAt(60.0 / 3200.0, true));
		check("关门方向无窗口 → 恒渲染", shan.shouldRenderAt(1.0, false));

		// 显式区间 [from, until]：直到 500ms 都渲染
		final var range = mtrnext.client.render.Properties4xLoader.parseParts(json("""
				{ "parts": [ { "names": ["x"],
				    "renderFromOpeningDoorTime": 100, "renderUntilOpeningDoorTime": 500 } ] }
				""")).get(0);
		check("区间下界前不渲染", !range.shouldRenderAt(50.0 / 3200.0, true));
		check("区间内渲染", range.shouldRenderAt(300.0 / 3200.0, true));
		check("区间上界后不渲染", !range.shouldRenderAt(600.0 / 3200.0, true));

		// 倒序区间（from > until）：上游 isBetween 用 min/max，应仍可工作
		final var rev = mtrnext.client.render.Properties4xLoader.parseParts(json("""
				{ "parts": [ { "names": ["y"],
				    "renderFromOpeningDoorTime": 500, "renderUntilOpeningDoorTime": 100 } ] }
				""")).get(0);
		check("倒序区间 200ms 落在 [100,500]", rev.shouldRenderAt(200.0 / 3200.0, true));
		check("倒序区间 600ms 超出", !rev.shouldRenderAt(600.0 / 3200.0, true));

		// 负门值取绝对值（上游 |doorValue|）
		check("负门值取绝对值", guang.shouldRenderAt(-0.01, true) == guang.shouldRenderAt(0.01, true));

		// 闪烁：flashOnTime=500, flashOffTime=500 → 周期 1000ms
		final var flash = mtrnext.client.render.Properties4xLoader.parseParts(json("""
				{ "parts": [ { "names": ["doordengshan_n"],
				    "flashOnTime": 500, "flashOffTime": 500 } ] }
				""")).get(0);
		check("flashOnTime=500 被解析", flash.flashOnTime == 500 && flash.flashOffTime == 500);
		// 上游：currentTimeMillis % 1000 > 500 → 亮
		// （即 t%1000 落在 (500,1000] 为亮；t=1100 → 1100%1000=100 → 灭）
		check("t=600ms 亮", flash.flashOn(600L));
		check("t=400ms 灭", !flash.flashOn(400L));
		check("t=1100ms 灭（%1000=100）", !flash.flashOn(1100L));
		check("t=900ms 亮", flash.flashOn(900L));
		// 无闪烁配置 → 常亮
		check("无闪烁配置 → 常亮", noWindow.flashOn(1234L));
		// 单侧配置：flashOn=800, flashOff=0 → 周期 800，%800 > 0 → 除整除点外都亮
		final var flash2 = mtrnext.client.render.Properties4xLoader.parseParts(json("""
				{ "parts": [ { "names": ["z"], "flashOnTime": 800, "flashOffTime": 0 } ] }
				""")).get(0);
		check("flashOff=0 → t=1ms 亮", flash2.flashOn(1L));
		check("flashOff=0 → t=800ms 灭（整除点）", !flash2.flashOn(800L));

		// DOOR_MOVE_TIME 常量与上游一致
		check("DOOR_MOVE_TIME=3200",
				mtrnext.client.render.Properties4xLoader.PartInfo.DOOR_MOVE_TIME == 3200.0);
	}

	/** 浮点近似比较（容差 1e-4） */
	private static boolean near(float actual, float expected) {
		return near(actual, expected, 1e-4F);
	}

	/** 浮点近似比较（自定义容差） */
	private static boolean near(float actual, float expected, float tol) {
		return Math.abs(actual - expected) <= tol;
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
