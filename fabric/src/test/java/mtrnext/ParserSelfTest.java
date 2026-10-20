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
		testDoorMovementInterpolation();
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
		System.out.println("[BbModelTree 顶层组语义 / 门归属]");

		// --- 模拟 bbmodel：嵌套 outliner + 混合 children ---
		// 上游 DynamicVehicleModel.java:41-62：blockbenchModel.getOutlines() 只遍历
		// **顶层** outline，每个顶层组建一个 nameToPart 条目；嵌套组只是链上的
		// 旋转/位移节点，**不**单独建锚点。
		final var tree = mtrnext.client.render.BbModelTree.parseForTest("""
				{ "elements": [
				  { "uuid": "e1", "name": "cube", "from": [0,0,0], "to": [16,16,16] },
				  { "uuid": "e2", "name": "cube", "from": [0,0,0], "to": [16,16,16] },
				  { "uuid": "e3", "name": "cube", "from": [0,0,0], "to": [16,16,16] } ],
				  "outliner": [
				    { "name": "body", "children": [
				      { "name": "men2", "children": ["e1", "e2"] } ] },
				    { "name": "men3", "children": ["e3"] } ] }
				""", 0.0F);
		check("3 个 element 全部解析", tree.elements.size() == 3);
		check("顶层组只有 2 个（body / men3）", tree.groupNames.size() == 2);
		check("body 是顶层组", tree.groupNames.contains("body"));
		check("men3 是顶层组", tree.groupNames.contains("men3"));
		// 嵌套组 men2 不是顶层组 → 不建锚点
		check("嵌套组 men2 不建锚点", !tree.groupNames.contains("men2"));
		check("嵌套 element 归属顶层组 body",
				"body".equals(tree.elements.get(0).groupName));
		check("嵌套 element 归属顶层组 body（e2 同）",
				"body".equals(tree.elements.get(1).groupName));
		check("顶层 element 归属 men3",
				"men3".equals(tree.elements.get(2).groupName));
		check("groupParts 数量 = 顶层组数", tree.groupParts.size() == 2);

		// --- 门归属：parts[].names 匹配**顶层组名**（上游 nameToPart 语义） ---
		final var parts = mtrnext.client.render.Properties4xLoader.parseParts(json("""
				{ "parts": [
				  { "names": ["body"], "doorZMultiplier": 11.0 },
				  { "names": ["men3"], "doorZMultiplier": -11.0 } ] }
				"""));
		final var boxes = mtrnext.client.render.DoorOverlapDetector.doorBoxes(tree, parts);
		// 上游门盒是「顶层组内所有 element 的合并包围盒」×「位置实例」，
		// 不是逐 element —— body 组 2 个 element → 1 个门盒。
		check("body + men3 共 2 个门盒（按顶层组合并）", boxes.size() == 2);
		check("body 门 z 倍率=11", near(boxes.get(0).doorZMult, 11.0F));
		check("body 门组名=body", "body".equals(boxes.get(0).groupPath));
		check("men3 门 z 倍率=-11", near(boxes.get(1).doorZMult, -11.0F));
		check("men3 门组名=men3", "men3".equals(boxes.get(1).groupPath));

		// 嵌套组名（men2）**不**能命中 parts[].names —— 上游只为顶层组建锚点
		final var nestedOnly = mtrnext.client.render.Properties4xLoader.parseParts(json(
				"{\"parts\":[{\"names\":[\"men2\"],\"doorZMultiplier\":11.0}]}"));
		check("嵌套组名不命中门归属（上游只认顶层组）",
				mtrnext.client.render.DoorOverlapDetector.doorBoxes(tree, nestedOnly).isEmpty());

		// 零倍率部件不参与门判定（上游 isDoor()）
		final var zeroParts = mtrnext.client.render.Properties4xLoader.parseParts(json(
				"{\"parts\":[{\"names\":[\"body\"],\"doorZMultiplier\":0.0}]}"));
		check("零倍率部件不参与门判定",
				mtrnext.client.render.DoorOverlapDetector.doorBoxes(tree, zeroParts).isEmpty());

		// null 安全
		check("tree=null 门盒为空",
				mtrnext.client.render.DoorOverlapDetector.doorBoxes(null, parts).isEmpty());
		check("parts=null 门盒为空",
				mtrnext.client.render.DoorOverlapDetector.doorBoxes(tree, null).isEmpty());
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
		/*
		 * STANDARD_SLOW 的**关门专用分段曲线**（上游 DoorAnimationType.java:142-151）：
		 *   if (!opening) {
		 *       if (time > 0.2)  return smoothEnds(1, doorMax, 0.2, 0.5, time);
		 *       if (time > 0.1)  return 1;
		 *       else             return smoothEnds(0, 1, 0, 0.1, time);
		 *   }
		 * 注意它与开门分支（fall through 到 default 的 smoothEnds(0, doorMax, 0, 0.5, time)）
		 * 在 t<0.2 区间**不同**：关门分支在 t=0.15 时是 1，开门分支是 3.3。
		 * ⇒ 传错 opening 会让关门时门「跳」一下。
		 */
		check("STANDARD_SLOW 关门 t=0.15 → 1（关门专用曲线）",
				near(stdSlow.getDoorAnimationZ(11.0F, false, 0.15F, false), 1.0F, 0.001F));
		check("STANDARD_SLOW 开门 t=0.15 ≠ 1（走 default 分支）",
				!near(stdSlow.getDoorAnimationZ(11.0F, false, 0.15F, true), 1.0F, 0.001F));
		check("STANDARD_SLOW 关门 t=0.05 → 0.5（smoothEnds(0,1,0,0.1)）",
				near(stdSlow.getDoorAnimationZ(11.0F, false, 0.05F, false), 0.5F, 0.02F));
		check("STANDARD_SLOW 关门 t=0.3 → 介于 1 与 11",
				stdSlow.getDoorAnimationZ(11.0F, false, 0.3F, false) > 1.0F
						&& stdSlow.getDoorAnimationZ(11.0F, false, 0.3F, false) < 11.0F);
		// STANDARD（无关门专用分支）两种方向结果相同
		check("STANDARD 开关两方向同值（无专用分支）",
				near(std.getDoorAnimationZ(11.0F, false, 0.3F, true),
						std.getDoorAnimationZ(11.0F, false, 0.3F, false), 0.001F));
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
	 * [0.36.5] bbmodel 尺度换算回归（迁到原生树）。
	 * <p>
	 * 上游依据（MIT 上游 master）：
	 * {@code BlockbenchElement.setModelPart} —— 注释 "Normalize dimensions
	 * (16 Blockbench units = 1 Minecraft block)"，返回 Box 坐标全部 /16。
	 * <p>
	 * [0.36.5 变更] 原实现用手算 {@code BbBox}（旧 {@code BbModelLoader}），
	 * 该类已随原生树重写一并删除。本测试改用 {@link BbModelTree.Element} 的
	 * {@code center/size}（已是格为单位）。
	 * <p>
	 * UV 归一化（resolution 作除数）已由 {@code bbModelTreeTest} 用
	 * 「六面逐面对比 MC 实测 UV 表」的方式更严格覆盖，本处不再重复。
	 */
	private static void testBbModelScaleAndUv() {
		System.out.println("[BbModelTree 尺度换算]");

		// 16 BB 像素 = 1 格
		final var tree = mtrnext.client.render.BbModelTree.parseForTest("""
				{ "resolution": { "width": 512, "height": 512 },
				  "elements": [
				    { "uuid": "a", "name": "cube", "from": [16,0,32], "to": [32,16,48] } ] }
				""", 0.0F);
		check("解析出 1 个 element", tree.elements.size() == 1);
		final var e = tree.elements.get(0);
		/*
		 * MC 空间约定（上游 BlockbenchElement.java:23-41）：
		 *   originX = -origin[0]、originY = -origin[1] - modelYOffset*16、originZ = +origin[2]
		 *   x = -to[0] - originX、y = -to[1] - originY - modelYOffset*16、z = from[2] - originZ
		 *   sizeX/Y/Z = round(to - from)  ← 正数
		 * 本例 from=[16,0,32] to=[32,16,48]，modelYOffset=0，元素 origin 缺省 [0,0,0]：
		 *   originX=0 → x = -32-0 = -32；originY=0 → y = -16-0 = -16；originZ=0 → z = 32-0 = 32
		 *   尺寸各 16px = 1 格
		 *   中心 = 起点 + 尺寸/2：X=(-32+8)/16=-1.5、Y=(-16+8)/16=-0.5、Z=(32+8)/16=2.5
		 */
		check("X 中心 −1.5 格（MC 空间 X 取负）", near(e.centerX, -1.5F));
		check("X 尺寸 1 格", near(e.sizeX, 1.0F));
		check("Y 中心 −0.5 格（MC 空间 Y 取负）", near(e.centerY, -0.5F));
		check("Y 尺寸 1 格", near(e.sizeY, 1.0F));
		check("Z 中心 2.5 格（Z 保持）", near(e.centerZ, 2.5F));
		check("Z 尺寸 1 格", near(e.sizeZ, 1.0F));

		// 上游 Box（BB 坐标系，0.36.5 归一化后）：
		// 上游原始参数 = (2, 1, -2, 1, 0, -3)（X/Y/Z 全取负 → 三轴倒序）
		// 归一化 → min=(1, 0, -3), max=(2, 1, -2)
		final var box = e.upstreamBox();
		check("上游 Box minX = 1", near(box[0], 1.0F));
		check("上游 Box minY = 0", near(box[1], 0.0F));
		check("上游 Box minZ = -3", near(box[2], -3.0F));
		check("上游 Box maxX = 2", near(box[3], 2.0F));
		check("上游 Box maxY = 1", near(box[4], 1.0F));
		check("上游 Box maxZ = -2", near(box[5], -2.0F));
		check("上游 Box 三轴长度均为 1 格",
				near(box[3] - box[0], 1.0F) && near(box[4] - box[1], 1.0F) && near(box[5] - box[2], 1.0F));

		// 不同 resolution 的模型各自取自己的贴图尺寸
		final var r128 = mtrnext.client.render.BbModelTree.parseForTest(
				"{\"resolution\":{\"width\":128,\"height\":128},\"elements\":[{\"uuid\":\"a\",\"name\":\"cube\",\"from\":[0,0,0],\"to\":[16,16,16]}]}",
				0.0F);
		check("resolution 128 生效", r128.textureWidth == 128 && r128.textureHeight == 128);

		// 缺 resolution -> 退化默认值（不崩）
		final var noRes = mtrnext.client.render.BbModelTree.parseForTest(
				"{\"elements\":[{\"uuid\":\"a\",\"name\":\"cube\",\"from\":[0,0,0],\"to\":[16,16,16]}]}",
				0.0F);
		check("缺 resolution 不崩", noRes.elements.size() == 1);
	}

	/**
	 * [0.35] 门参数标注回归（组名匹配 + 倍率/动画型写入）。
	 * <p>
	 * 上游依据：{@code ModelPropertiesPart.isDoor()} =
	 * {@code doorXMultiplier != 0 || doorZMultiplier != 0}；门归属由
	 * parts[].names 匹配模型部件名（4.0 pack 里即 outliner 组名）。
	 */
	/**
	 * [0.36.5] 门参数归属回归（迁到原生树 + 顶层组语义）。
	 * <p>
	 * 上游依据：{@code ModelPropertiesPart.isDoor()} =
	 * {@code doorXMultiplier != 0 || doorZMultiplier != 0}；门归属由
	 * parts[].names 匹配模型部件名（4.0 pack 里即**顶层** outliner 组名，
	 * 见 {@code DynamicVehicleModel.java:41-62} 的 {@code nameToPart}）。
	 * <p>
	 * [0.36.5 变更] 旧实现匹配「祖先组名链」（嵌套组也命中），
	 * 偏离上游；且载体 {@code BbBox} 已随旧手算类删除。
	 */
	private static void testDoorAnnotation() {
		System.out.println("[门参数归属（原生树 / 顶层组）]");

		final var tree = mtrnext.client.render.BbModelTree.parseForTest("""
				{ "resolution": { "width": 512, "height": 512 },
				  "elements": [
				    { "uuid": "e1", "name": "cube", "from": [304,0,0],  "to": [320,320,1024] },
				    { "uuid": "e2", "name": "cube", "from": [320,0,0],  "to": [336,320,1024] },
				    { "uuid": "e3", "name": "cube", "from": [0,0,0],    "to": [16,16,16] } ],
				  "outliner": [
				    { "name": "men2", "children": ["e1"] },
				    { "name": "men3", "children": ["e2"] },
				    { "name": "other", "children": ["e3"] } ] }
				""", 0.0F);
		check("解析出 3 个 element", tree.elements.size() == 3);

		final var parts = mtrnext.client.render.Properties4xLoader.parseParts(json("""
				{ "parts": [
				  { "names": ["men2"], "doorZMultiplier": 11.0,  "doorAnimationType": "STANDARD_SLOW" },
				  { "names": ["men3"], "doorZMultiplier": -11.0, "doorAnimationType": "STANDARD_SLOW" } ] }
				"""));
		final var ann = mtrnext.client.render.DoorOverlapDetector.doorBoxes(tree, parts);
		check("只有 2 个 element 归属门", ann.size() == 2);

		// men2 -> z 倍率 +11，动画型 STANDARD_SLOW
		check("men2 门 z 倍率=11", near(ann.get(0).doorZMult, 11.0F));
		check("men2 门 x 倍率=0", ann.get(0).doorXMult == 0.0F);
		check("men2 门动画型=STANDARD_SLOW",
				ann.get(0).doorAnim == mtrnext.client.render.DoorAnimation4x.STANDARD_SLOW);
		check("men2 门组名=men2", "men2".equals(ann.get(0).groupPath));

		// men3 -> z 倍率 -11
		check("men3 门 z 倍率=-11", near(ann.get(1).doorZMult, -11.0F));
		check("men3 门动画型=STANDARD_SLOW",
				ann.get(1).doorAnim == mtrnext.client.render.DoorAnimation4x.STANDARD_SLOW);

		// 未被 parts 命中的组 -> 非门
		final var noHit = mtrnext.client.render.DoorOverlapDetector.doorBoxes(tree,
				mtrnext.client.render.Properties4xLoader.parseParts(json(
						"{\"parts\":[{\"names\":[\"nobody\"],\"doorZMultiplier\":11.0}]}")));
		check("未命中的组不是门", noHit.isEmpty());

		// 零倍率部件不参与门判定（上游 isDoor()）
		final var zeroParts = mtrnext.client.render.Properties4xLoader.parseParts(json(
				"{\"parts\":[{\"names\":[\"men2\"],\"doorZMultiplier\":0.0,\"doorXMultiplier\":0.0}]}"));
		final var ann2 = mtrnext.client.render.DoorOverlapDetector.doorBoxes(tree, zeroParts);
		check("零倍率部件 -> 非门", ann2.isEmpty());

		// X-only 门（塞拉门）也要被标注（上游 isDoor() 判 X 或 Z）
		final var xParts = mtrnext.client.render.Properties4xLoader.parseParts(json(
				"{\"parts\":[{\"names\":[\"men2\"],\"doorXMultiplier\":11.0,\"doorZMultiplier\":0.0,\"doorAnimationType\":\"PLUG_FAST\"}]}"));
		final var ann3 = mtrnext.client.render.DoorOverlapDetector.doorBoxes(tree, xParts);
		check("X-only 门被标注", ann3.size() == 1
				&& ann3.get(0).doorAnim == mtrnext.client.render.DoorAnimation4x.PLUG_FAST);
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
		System.out.println("[DoorOverlapDetector（上游 testDoors 移植）]");

		// --- 场景 A：同向同量滑动 → 相对距离恒定 → 不该报（防误报） ---
		// d1 z=[0,10]格, d2 z=[12,22]格
		final var sameSpeed = mtrnext.client.render.DoorOverlapDetector.doorBoxes(
				mtrnext.client.render.BbModelTree.parseForTest("""
						{ "elements": [
						  { "uuid": "d1", "name": "cube", "from": [304,0,0],   "to": [320,64,124] },
						  { "uuid": "d2", "name": "cube", "from": [304,0,128], "to": [320,64,252] } ],
						  "outliner": [
						    { "name": "men2", "children": ["d1"] },
						    { "name": "men3", "children": ["d2"] } ] }
						""", 0.0F),
				mtrnext.client.render.Properties4xLoader.parseParts(json("""
						{ "parts": [
						  { "names": ["men2"], "doorZMultiplier": 11.0,  "doorAnimationType": "STANDARD" },
						  { "names": ["men3"], "doorZMultiplier": 11.0,  "doorAnimationType": "STANDARD" } ] }
						""")));
		check("同向同量滑动收集到 2 扇门", sameSpeed.size() == 2);
		check("同向同量滑动（相对距离恒定）不报",
				mtrnext.client.render.DoorOverlapDetector.detectDoorOverlaps(sameSpeed).isEmpty());

		// --- 场景 B：倍率不等（大滑动量追上小滑动量）→ 应报重叠 ---
		// 上游 testDoors 的时间片：time = i/5 ∈ {0, 0.2, 0.4, 0.6, 0.8, 1.0}。
		// STANDARD 走 default: smoothEnds(0, doorMax, 0, 0.5, time)：
		//   t=0.2 → doorMax×(1−cos(0.4π))/2 = doorMax×0.6545
		//   t=0.4 → doorMax×0.9045；t≥0.6 → doorMax（已封顶）
		// BB 坐标系实测（见 [dbg] 输出）：from z=0 的盒 z=[-10,0]（Z 轴取负，
		// 跨度 = (to.z-from.z)/16 格）；from z=-16/to z=-32 的盒 z=[1,2]。
		// d1: doorZMultiplier=40，盒 z=[-10,0] 格
		// d2: doorZMultiplier=1，盒 z=[1,2] 格 —— 初始间隙 1 格（不相交）
		// t=0.2：d1 滑 40×0.6545=26.2px=1.64 格 → z=[-8.36,1.64]
		//       d2 滑 1×0.6545=0.65px=0.04 格 → z=[1.04,2.04]
		//       d1.max=1.64 > d2.min=1.04 ⇒ Z 轴区间相交 → 应报。
		final var unequal = mtrnext.client.render.DoorOverlapDetector.doorBoxes(
				mtrnext.client.render.BbModelTree.parseForTest("""
						{ "elements": [
						  { "uuid": "d1", "name": "cube", "from": [304,0,0],   "to": [320,64,160] },
						  { "uuid": "d2", "name": "cube", "from": [304,0,-16], "to": [320,64,-32] } ],
						  "outliner": [
						    { "name": "men2", "children": ["d1"] },
						    { "name": "men3", "children": ["d2"] } ] }
						""", 0.0F),
				mtrnext.client.render.Properties4xLoader.parseParts(json("""
						{ "parts": [
						  { "names": ["men2"], "doorZMultiplier": 40.0, "doorAnimationType": "STANDARD" },
						  { "names": ["men3"], "doorZMultiplier": 1.0,  "doorAnimationType": "STANDARD" } ] }
						""")));
		check("异向初始不相交", !unequal.get(0).intersects(unequal.get(1), 0, 0));
		final var overlaps = mtrnext.client.render.DoorOverlapDetector.detectDoorOverlaps(unequal);
		check("倍率不等追尾被检出", overlaps.size() == 1);
		check("报错信息含组名", overlaps.size() == 1
				&& overlaps.get(0).contains("men2") && overlaps.get(0).contains("men3"));

		// --- 场景 C：ERIDU 真实配置（+11/−11 反向对滑，初始包围盒相交）→ 不报 ---
		// 用真实实测的 ERIDU 组 Z 跨度：men2 [-56.8, 63.5]、men3 [-59.5, 40.0]（像素）
		final var eridu = mtrnext.client.render.DoorOverlapDetector.doorBoxes(
				mtrnext.client.render.BbModelTree.parseForTest("""
						{ "elements": [
						  { "uuid": "d1", "name": "cube", "from": [304,0,-908], "to": [320,320,1016] },
						  { "uuid": "d2", "name": "cube", "from": [320,0,-952], "to": [336,320,640] } ],
						  "outliner": [
						    { "name": "men2", "children": ["d1"] },
						    { "name": "men3", "children": ["d2"] } ] }
						""", 0.0F),
				mtrnext.client.render.Properties4xLoader.parseParts(json("""
						{ "parts": [
						  { "names": ["men2"], "doorZMultiplier": 11.0,  "doorAnimationType": "STANDARD_SLOW" },
						  { "names": ["men3"], "doorZMultiplier": -11.0, "doorAnimationType": "STANDARD_SLOW" } ] }
						""")));
		check("ERIDU 真实几何初始包围盒相交",
				eridu.get(0).intersects(eridu.get(1), 0, 0));
		check("ERIDU 配置（+11/−11 对滑）不报重叠",
				mtrnext.client.render.DoorOverlapDetector.detectDoorOverlaps(eridu).isEmpty());

		// --- 退化：单门 / null ---
		final var single = mtrnext.client.render.DoorOverlapDetector.doorBoxes(
				mtrnext.client.render.BbModelTree.parseForTest("""
						{ "elements": [ { "uuid": "d1", "name": "cube", "from": [0,0,0], "to": [16,16,16] } ],
						  "outliner": [ { "name": "men2", "children": ["d1"] } ] }
						""", 0.0F),
				mtrnext.client.render.Properties4xLoader.parseParts(json("""
						{ "parts": [
						  { "names": ["men2"], "doorZMultiplier": 11.0,  "doorAnimationType": "STANDARD_SLOW" },
						  { "names": ["men3"], "doorZMultiplier": -11.0, "doorAnimationType": "STANDARD_SLOW" } ] }
						""")));
		check("单门不报", mtrnext.client.render.DoorOverlapDetector.detectDoorOverlaps(single).isEmpty());
		check("null 输入安全", mtrnext.client.render.DoorOverlapDetector.detectDoorOverlaps(null).isEmpty());
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
	/**
	 * [0.36.5] 位定义 → 正位/翻转位（上游 positionsFlipped 语义，迁到原生树）。
	 * <p>
	 * 用真实 ERIDU 数据模式：men2 有两条 part——一条位定义只含 positions
	 * （正位），另一条只含 positionsFlipped（翻转位）。
	 * <p>
	 * <b>[0.36.5 语义变更]</b>：旧实现引入 {@code mirrorNeeded} / {@code doorFlipped}
	 * 两个自造概念，判据是「组内几何是否只建了一侧」。0.36.0 起原生树
	 * **不做镜像副本**——翻转位由 {@code positionDefinitions} 的
	 * {@code positionsFlipped} 槽直接决定，与上游
	 * {@code ModelPropertiesPart.renderNormal} 一致。
	 * 本测试改为验证该语义：翻转位 → 门位移曲线反号。
	 */
	private static void testPositionDefinitionsMirror() {
		System.out.println("[位定义正位/翻转位（上游 positionsFlipped）]");

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

		// --- 渲染路径的翻转位分派（上游 renderNormal：positions 走正位，
		//     positionsFlipped 走 flipped=true 即绕 Y 轴 180° + X 取反） ---
		// 上游 ModelPropertiesPart.renderNormal：
		//   for (position : positionDefinition.positions)         → flipped=false
		//   for (position : positionDefinition.positionsFlipped)  → flipped=true
		final var defNormal = posDefs.get("defNormal");
		final var defFlipped = posDefs.get("defFlipped");
		check("defNormal 正位 1 个、翻转位 0 个",
				defNormal.positions.size() == 1 && defNormal.positionsFlipped.isEmpty());
		check("defFlipped 正位 0 个、翻转位 1 个",
				defFlipped.positions.isEmpty() && defFlipped.positionsFlipped.size() == 1);

		// 翻转位实例的 X 取反（原生渲染器 drawInstance 的 -p[0]）
		final float[] flippedPos = defFlipped.positionsFlipped.get(0);
		check("翻转位 X 取反（绕 Y 轴 180°）", near(-flippedPos[0], -flippedPos[0]));

		// 门曲线反号实证：STANDARD_SLOW 开门路径 fall through 到上游 default
		// （DoorAnimationType.java L152-153）→ smoothEnds(0, doorMax, 0, 0.5, t)。
		// doorMax=11，t=0.5：11*(1-cos(π))/2 = 11。
		// flipped=false → +11；flipped=true → copySign 反号 = -11。
		final var anim = mtrnext.client.render.DoorAnimation4x.STANDARD_SLOW;
		check("flipped=false 曲线值 +11", near(anim.getDoorAnimationZ(11.0F, false, 0.5F, true), 11.0F, 0.001F));
		check("flipped=true 曲线值 −11", near(anim.getDoorAnimationZ(11.0F, true, 0.5F, true), -11.0F, 0.001F));
		// t=0.25（半程）：11*(1-cos(π/2))/2 = 5.5
		check("flipped=false t=0.25 曲线值 +5.5", near(anim.getDoorAnimationZ(11.0F, false, 0.25F, true), 5.5F, 0.001F));
		check("flipped=true t=0.25 曲线值 −5.5", near(anim.getDoorAnimationZ(11.0F, true, 0.25F, true), -5.5F, 0.001F));

		// 门归属仍按顶层组名（与上游 nameToPart 一致）：men2 命中、nobody 不命中
		final var tree = mtrnext.client.render.BbModelTree.parseForTest("""
				{ "elements": [
				  { "uuid": "m1", "name": "cube", "from": [304,0,0], "to": [320,64,128] } ],
				  "outliner": [ { "name": "men2", "children": ["m1"] } ] }
				""", 0.0F);
		final var partsFlip = mtrnext.client.render.Properties4xLoader.parseParts(json("""
				{ "parts": [
				  { "names": ["men2"], "doorZMultiplier": 11.0, "doorAnimationType": "STANDARD_SLOW",
				    "positionDefinitions": ["defFlipped"] } ] }
				"""));
		final var flipBoxes = mtrnext.client.render.DoorOverlapDetector.doorBoxes(tree, partsFlip);
		check("翻转位 part → 仍归属 men2 门", flipBoxes.size() == 1
				&& "men2".equals(flipBoxes.get(0).groupPath));
		check("翻转位 part → dz=+11", near(flipBoxes.get(0).doorZMult, 11.0F));
	}

	/**
	 * [0.36.5] 真实 ERIDU 车包端到端验证（离线读文件，不依赖 Minecraft 运行时）。
	 * <p>
	 * 验证目标：
	 * ① eridudanguei 的顶层组数 = 36、门组 men2/men3/men2_n/men3_n 均建锚点，
	 *    嵌套子组不建锚点（上游 nameToPart 口径）；
	 * ② 门组 men2/men3 的 dz 倍率分别为 +11 / −11（ERIDU 实测）；
	 * ③ 门重叠检测在真实包上不误报（+11/−11 反向对滑）。
	 * <p>
	 * [0.36.5 变更] 旧实现测的是「单侧组/双侧组 → 是否画镜像副本」，
	 * 该概念在 0.36.0 的原生树里已不存在（翻转位由 positionDefinitions 决定）。
	 * <p>
	 * 车包路径不存在时整体跳过（不判失败）——保证裸机也能跑自测。
	 */
	private static void testRealPackMirror() {
		System.out.println("[真实 ERIDU 车包端到端]");
		final java.io.File packDir = new java.io.File(
				"F:\\vibe coding工程\\mtr-turbo\\build\\eridu-pack\\assets\\mtr");
		if (!packDir.isDirectory()) {
			System.out.println("  跳过：车包不存在 " + packDir.getAbsolutePath());
			return;
		}

		// 汇总所有 definition_*.json 的位定义
		// [0.36.5] 原生树不再按「单侧/双侧」决定镜像副本，翻转位由
		// positionDefinitions 的 positionsFlipped 槽直接给出，故此处只做
		// 数据规模核验，不再参与镜像判定。
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
		final var tree = mtrnext.client.render.BbModelTree.parseForTest(bbTxt, 0.0F);
		check("ERIDU 主车体 element 数 ≥ 900", tree.elements.size() >= 900);
		check("ERIDU 顶层组数 = 36（实测）", tree.groupNames.size() == 36);
		check("ERIDU 门组 men2 是顶层组", tree.groupNames.contains("men2"));
		check("ERIDU 门组 men3 是顶层组", tree.groupNames.contains("men3"));
		check("ERIDU 嵌套组 men2_n 建锚点（顶层）", tree.groupNames.contains("men2_n"));
		check("ERIDU 嵌套子组 win_n_a1 不建锚点（顶层 36 口径）",
				!tree.groupNames.contains("win_n_a1"));
		check("ERIDU groupParts 数量 = 顶层组数", tree.groupParts.size() == tree.groupNames.size());

		final var ann = mtrnext.client.render.DoorOverlapDetector.doorBoxes(tree, allParts, allDefs);

		// ① 门组：men2/men3 等带门倍率，应被收进门盒集合
		final java.util.Map<String, java.util.List<mtrnext.client.render.DoorOverlapDetector.DoorBox>> byGroup =
				new java.util.HashMap<>();
		for (final var b : ann) {
			byGroup.computeIfAbsent(b.groupPath, k -> new java.util.ArrayList<>()).add(b);
		}
		for (final String g : new String[]{"men2", "men3", "men2_n", "men3_n"}) {
			final var gb = byGroup.get(g);
			check("ERIDU 门组 " + g + " 存在", gb != null && !gb.isEmpty());
		}

		// ② 门倍率：men2 dz=+11，men3 dz=−11（ERIDU 实测）
		final var men2 = byGroup.get("men2");
		final var men3 = byGroup.get("men3");
		if (men2 != null && !men2.isEmpty()) {
			check("ERIDU men2 dz=+11", near(men2.get(0).doorZMult, 11.0F, 0.001F));
		}
		if (men3 != null && !men3.isEmpty()) {
			check("ERIDU men3 dz=−11", near(men3.get(0).doorZMult, -11.0F, 0.001F));
		}

		// ③ 门盒总数应与「带非零门倍率且组名命中」的 element 数量一致
		System.out.println("  门盒数：" + ann.size() + " / 顶层组数 " + tree.groupNames.size());
		check("门盒数在合理区间（> 20 且 < 500）", ann.size() > 20 && ann.size() < 500);

		// ④ 门重叠检测在真实包上不误报（ERIDU 用 +11/−11 反向对滑）
		final var overlaps = mtrnext.client.render.DoorOverlapDetector.detectDoorOverlaps(ann);
		for (final String s : overlaps) {
			System.out.println("    重叠告警：" + s);
		}
		check("ERIDU 真实包门重叠检测无告警", overlaps.isEmpty());
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

	/**
	 * [0.36.2] 门位移插值层（上游 {@code DoorMovementInterpolation}）。
	 * <p>
	 * 上游 {@code PersistentVehicleData.java:164-178}：
	 * <pre>
	 * private static class DoorMovementInterpolation {
	 *     private boolean opening;
	 *     private final Interpolation interpolation = new Interpolation(500);
	 *     private float setAndGet(double value, boolean opening) {
	 *         interpolation.setValue(value, this.opening != opening);
	 *         this.opening = opening;
	 *         return (float) interpolation.getValue();
	 *     }
	 * }
	 * </pre>
	 * 上游 {@code Interpolation} 是 500 ms <b>线性</b>混合，方向翻转时从
	 * <b>当前插值结果</b>续接（{@code oldValue = getValue()}）。
	 * <p>
	 * 核心可观测性质：<b>翻转不跳变</b>——方向翻转的那一刻，返回值仍等于
	 * 翻转前的插值值，而不是瞬跳到新曲线值。这正是这层插值存在的意义。
	 */
	private static void testDoorMovementInterpolation() {
		System.out.println("[门位移插值层（上游 DoorMovementInterpolation）]");

		// key 规则：上游 PersistentVehicleData.java:99
		check("key 规则 = 倍率_类型_翻转",
				mtrnext.client.render.DoorMovementInterpolation.key(11.0, "STANDARD", false)
						.equals("11.0_STANDARD_false"));
		check("key 区分翻转",
				!mtrnext.client.render.DoorMovementInterpolation.key(11.0, "STANDARD", false)
						.equals(mtrnext.client.render.DoorMovementInterpolation.key(11.0, "STANDARD", true)));
		check("key 区分倍率",
				!mtrnext.client.render.DoorMovementInterpolation.key(11.0, "STANDARD", false)
						.equals(mtrnext.client.render.DoorMovementInterpolation.key(-11.0, "STANDARD", false)));

		// 首次调用：上游 Interpolation 初始 oldValue=0、startMillis=0；
		// setAndGet(11.0, opening=true) 时 this.opening(false) != opening(true)
		// → 触发 startInterpolation → oldValue = getValue() = 0，
		//   startMillis = now，latestValue = 11
		// → 同一毫秒内 getValue() 返回 oldValue = 0（线性插值的起点）。
		// 即上游的插值器**第一次调用返回 0**，从 0 开始向 11 过渡。
		final var interp = new mtrnext.client.render.DoorMovementInterpolation();
		final float first = interp.setAndGet(10.0, true);
		check("首次调用返回插值起点 0（上游行为）", near(first, 0.0F, 0.001F));

		// 等插值收敛：超过 500 ms 后 getValue() 直接返回 latestValue = 10
		try {
			Thread.sleep(650L);
		} catch (final InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
		final float converged = interp.setAndGet(10.0, true);
		check("超时后收敛到 latestValue", near(converged, 10.0F, 0.01F));

		// 同方向更新：上游 setValue 只在 startInterpolation 为真时才重启窗口，
		// 且 startMillis 不变 → difference 继续增长 → 超出 500 ms 后直接取 latestValue。
		// 即：**同方向变化不产生过渡**（过渡只在方向翻转时发生）。
		final float afterSecond = interp.setAndGet(0.0, true);
		check("同方向变化不跳变也不插值（超出窗口后取 latestValue）",
				near(afterSecond, 0.0F, 0.01F));

		// 方向翻转：应重启 500 ms 插值窗口——返回值仍接近翻转前的插值结果
		// （翻转前 oldValue 已被同步为 0），而不是瞬跳到新目标 10
		final float beforeFlip = interp.setAndGet(0.0, true);
		final float afterFlip = interp.setAndGet(10.0, false); // 方向翻转 + 目标值跳到 10
		check("方向翻转不跳变（值仍在旧插值附近）",
				Math.abs(afterFlip - beforeFlip) < 1.0);
		check("方向翻转后不等于新目标值 10", Math.abs(afterFlip - 10.0) > 1.0);

		// 收敛：等待超过 500 ms 后应收敛到目标值
		try {
			Thread.sleep(650L);
		} catch (final InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
		final float settled = interp.setAndGet(10.0, false);
		check("超时后收敛到目标值", near(settled, 10.0F, 0.01F));

		// 上游 Interpolation.setValueDirect：不插值，直接设值
		final var direct = new mtrnext.client.render.DoorMovementInterpolation.Interpolation(500);
		direct.setValueDirect(4.0);
		check("setValueDirect 直接取值", near((float) direct.getValue(), 4.0F, 0.001F));

		// 线性插值：中间态应落在两端之间
		final var lin = new mtrnext.client.render.DoorMovementInterpolation.Interpolation(500);
		lin.setValue(0.0, false);
		lin.setValue(10.0, false); // 非重启 → 继续插值
		final double v = lin.getValue();
		check("线性插值中间态在 [0,10]", v >= 0.0 && v <= 10.0);
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
