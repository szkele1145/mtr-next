package mtrnext;

import net.minecraft.client.model.geom.ModelPart;

import java.lang.reflect.Field;
import java.util.List;

/**
 * [0.36.0] 原生模型树（{@code BbModelTree}）自测。
 * <p>
 * 直接验证"复刻上游构造过程"是否正确：把 bbmodel 文本喂进
 * {@code BbModelTree.parseForTest}，再检查 bake 出来的 {@link ModelPart} 树。
 * <p>
 * 断言依据全部来自上游源码与实测：
 * <ul>
 *   <li>MC 1.20.1 {@code ModelPart$Cube} 的 box-UV 布局（实测，见
 *       {@code tools/probe_uv/ProbeUv.java}）：对 16³ 盒、u=100,v=200 得
 *       down=[116..132]×[200..216]、up=[132..148]×[200..216]、
 *       west=[100..116]×[216..232]、north=[116..132]×[216..232]、
 *       east=[132..148]×[216..232]、south=[148..164]×[216..232]</li>
 *   <li>上游 {@code BlockbenchElement.setModelPart} L23-41：
 *       originX=−origin[0]、originY=−origin[1]−modelYOffset*16、originZ=+origin[2]；
 *       x=−to[0]−originX、y=−to[1]−originY−modelYOffset*16、z=from[2]−originZ</li>
 *   <li>上游 {@code GroupTransformations.create} L28-44：pivot 逐级差分，
 *       modelYOffset 只落第一节</li>
 *   <li>上游 {@code BlockbenchOutline.add} L39-48：顶层组旋转强制 0，
 *       嵌套组 origin 取与父组的差</li>
 * </ul>
 * <p>
 * 节点结构：锚点 → 链节(带 pivot+rotation) → 元素节(带元素自身 origin/rotation)
 * → cube 节点(带几何)。<b>旋转在链节/元素节上，cube 节点只挂几何</b>。
 */
public final class BbModelTreeTest {

	private BbModelTreeTest() {
	}

	private static int pass = 0;
	private static int fail = 0;

	public static void main(String[] args) {
		testBasicCube();
		testGroupChainPivot();
		testElementRotation();
		testModelYOffset();
		testUvLayout();
		testMirrorFlag();
		testNestedGroups();
		testFlatModelFallback();
		testTopLevelRotationForcedZero();
		testNullResourceSafety();
		System.out.println();
		System.out.println("PASS=" + pass + " FAIL=" + fail);
		if (fail > 0) {
			System.exit(1);
		}
	}

	/** 单个盒：尺寸、位置（上游 X/Y 取负、Z 保持） */
	private static void testBasicCube() {
		System.out.println("[BbModelTree 单盒几何]");
		final var tree = mtrnext.client.render.BbModelTree.parseForTest("""
				{ "resolution": { "width": 64, "height": 64 },
				  "elements": [ { "uuid": "e1", "name": "cube", "from": [0, 0, 0], "to": [16, 16, 16],
				                  "uv_offset": [0, 0], "origin": [0, 0, 0] } ],
				  "outliner": [ { "name": "grp", "origin": [0, 0, 0], "children": ["e1"] } ] }
				""", 0.0F);
		check("解析出 1 个元素", tree.elements.size() == 1);
		if (tree.elements.isEmpty()) {
			return;
		}
		final var el = tree.elements.get(0);
		check("组名 = grp", "grp".equals(el.groupName));
		check("尺寸 16px = 1 格", near(el.sizeX, 1.0F, 0.001F) && near(el.sizeY, 1.0F, 0.001F)
				&& near(el.sizeZ, 1.0F, 0.001F));
		// 上游：x = -to[0] - originX = -16；中心 X = (x + sizeX/2)/16 = -0.5 格
		check("中心 X = -0.5 格（上游取负）", near(el.centerX, -0.5F, 0.001F));
		check("中心 Y = -0.5 格", near(el.centerY, -0.5F, 0.001F));
		check("中心 Z = +0.5 格（Z 保持）", near(el.centerZ, 0.5F, 0.001F));
		check("锚点存在", tree.groupParts.containsKey("grp") && tree.groupParts.get("grp") != null);
	}

	/**
	 * 组链 pivot 差分：上游 {@code GroupTransformations.create} L37-40
	 * 每节 pivot 减掉之前的 combined，所以嵌套组的 pivot 是相对量。
	 * <p>
	 * 顶层组 origin=(0,0,0) → 嵌套组 origin=(8,0,0) → 元素 origin=(8,0,0)。
	 * 元素 originX = −8 → x = −24 − (−8) = −16 → 中心 X = −0.5 格。
	 */
	private static void testGroupChainPivot() {
		System.out.println("[BbModelTree 组链 pivot 差分]");
		final var tree = mtrnext.client.render.BbModelTree.parseForTest("""
				{ "resolution": { "width": 64, "height": 64 },
				  "elements": [ { "uuid": "e1", "name": "cube", "from": [8, 0, 0], "to": [24, 16, 16],
				                  "uv_offset": [0, 0], "origin": [8, 0, 0] } ],
				  "outliner": [ { "name": "top", "origin": [0, 0, 0], "children": [
				                   { "name": "inner", "origin": [8, 0, 0], "children": ["e1"] } ] } ] }
				""", 0.0F);
		check("解析出 1 个元素", tree.elements.size() == 1);
		if (tree.elements.isEmpty()) {
			return;
		}
		final var el = tree.elements.get(0);
		check("嵌套组链后中心 X = -0.5 格", near(el.centerX, -0.5F, 0.001F));
		// 元素节 pivot = 元素 origin(8) - 父组 origin(8) = 0
		check("元素节 pivot 差分后为 0", near(el.holder.x, 0.0F, 0.001F));
		// 链长：组节 + 嵌套组节 + 元素节 = 3
		check("链长 = 3（组/嵌套组/元素）", el.chain.size() == 3);
	}

	/** 元素 rotation 必须进链（手算四边面路径完全忽略了它） */
	private static void testElementRotation() {
		System.out.println("[BbModelTree 元素旋转入链]");
		final var tree = mtrnext.client.render.BbModelTree.parseForTest("""
				{ "resolution": { "width": 64, "height": 64 },
				  "elements": [ { "uuid": "e1", "name": "cube", "from": [0, 0, 0], "to": [16, 16, 16],
				                  "uv_offset": [0, 0], "origin": [0, 0, 0], "rotation": [0, 0, 45] } ],
				  "outliner": [ { "name": "grp", "origin": [0, 0, 0], "children": ["e1"] } ] }
				""", 0.0F);
		check("解析出 1 个元素", tree.elements.size() == 1);
		if (tree.elements.isEmpty()) {
			return;
		}
		final var el = tree.elements.get(0);
		// 上游 rot = (-rx, -ry, +rz) → Z 旋转 +45° = +0.785 rad，落在元素节上
		check("元素节带 Z 旋转", near(el.holder.zRot, (float) Math.toRadians(45.0F), 0.01F));
		// cube 节点自身无旋转（旋转在链节上）
		check("cube 节点自身无旋转", near(el.part.zRot, 0.0F, 0.001F));
	}

	/**
	 * modelYOffset：上游在两处各减一次 modelYOffset*16
	 * （BlockbenchElement L24 的 originY 与 L35 的 y），
	 * 而链上的 offset 只落第一节（GroupTransformations.create L33-35、41）。
	 */
	private static void testModelYOffset() {
		System.out.println("[BbModelTree modelYOffset 位置]");
		final var tree = mtrnext.client.render.BbModelTree.parseForTest("""
				{ "resolution": { "width": 64, "height": 64 },
				  "elements": [ { "uuid": "e1", "name": "cube", "from": [0, 0, 0], "to": [16, 16, 16],
				                  "uv_offset": [0, 0], "origin": [0, 0, 0] } ],
				  "outliner": [ { "name": "grp", "origin": [0, 0, 0], "children": ["e1"] } ] }
				""", 1.0F);
		check("解析出 1 个元素", tree.elements.size() == 1);
		if (tree.elements.isEmpty()) {
			return;
		}
		final var el = tree.elements.get(0);
		// 链首节（组节）pivot.y = pivotY - combinedY - offset = 0 - 0 - 16 = -16
		check("链首节 pivot 承担 modelYOffset", !el.chain.isEmpty() && near(el.chain.get(0).y, -16.0F, 0.001F));
		// 末节（元素节）offset 已清零 → pivot.y = 0 - 0 - 0 = 0
		check("末节链节 offset 已清零", near(el.holder.y, 0.0F, 0.001F));
	}

	/**
	 * box-UV 布局：直接比对 MC 1.20.1 实测表。
	 * 元素 from=[0,0,0] to=[16,16,16]、uv_offset=[100,200]。
	 */
	private static void testUvLayout() {
		System.out.println("[BbModelTree box-UV 布局（MC 实测表）]");
		final var tree = mtrnext.client.render.BbModelTree.parseForTest("""
				{ "resolution": { "width": 1024, "height": 1024 },
				  "elements": [ { "uuid": "e1", "name": "cube", "from": [0, 0, 0], "to": [16, 16, 16],
				                  "uv_offset": [100, 200], "origin": [0, 0, 0] } ],
				  "outliner": [ { "name": "grp", "origin": [0, 0, 0], "children": ["e1"] } ] }
				""", 0.0F);
		check("解析出 1 个元素", tree.elements.size() == 1);
		if (tree.elements.isEmpty()) {
			return;
		}
		// MC 实测（16³ 盒，u=100，v=200）
		final float[][] expected = {
				{116, 132, 200, 216},  // down
				{132, 148, 200, 216},  // up
				{100, 116, 216, 232},  // west
				{116, 132, 216, 232},  // north
				{132, 148, 216, 232},  // east
				{148, 164, 216, 232}   // south
		};
		final String[] names = {"down", "up", "west", "north", "east", "south"};
		final Object[] polygons = polygonsOf(tree.elements.get(0).part);
		if (polygons == null || polygons.length != 6) {
			check("cube 有 6 个多边形", false);
			return;
		}
		for (int i = 0; i < 6; i++) {
			final float[] rect = uvRectOf(polygons[i]);
			final float[] exp = expected[i];
			check(names[i] + " UV = [" + (int) exp[0] + ".." + (int) exp[1] + "]×["
							+ (int) exp[2] + ".." + (int) exp[3] + "]",
					rect != null && near(rect[0], exp[0], 0.5F) && near(rect[1], exp[1], 0.5F)
							&& near(rect[2], exp[2], 0.5F) && near(rect[3], exp[3], 0.5F));
		}
	}

	/** mirror 标志：上游 addCuboid(..., !shade || mirror_uv) → CubeListBuilder.mirror */
	private static void testMirrorFlag() {
		System.out.println("[BbModelTree mirror 标志]");
		final var mirrored = mtrnext.client.render.BbModelTree.parseForTest("""
				{ "resolution": { "width": 1024, "height": 1024 },
				  "elements": [ { "uuid": "e1", "name": "cube", "from": [0, 0, 0], "to": [16, 16, 16],
				                  "uv_offset": [100, 200], "origin": [0, 0, 0], "mirror_uv": true } ],
				  "outliner": [ { "name": "grp", "origin": [0, 0, 0], "children": ["e1"] } ] }
				""", 0.0F);
		final var normal = mtrnext.client.render.BbModelTree.parseForTest("""
				{ "resolution": { "width": 1024, "height": 1024 },
				  "elements": [ { "uuid": "e1", "name": "cube", "from": [0, 0, 0], "to": [16, 16, 16],
				                  "uv_offset": [100, 200], "origin": [0, 0, 0] } ],
				  "outliner": [ { "name": "grp", "origin": [0, 0, 0], "children": ["e1"] } ] }
				""", 0.0F);
		if (mirrored.elements.isEmpty() || normal.elements.isEmpty()) {
			check("两种都解析出元素", false);
			return;
		}
		final Object[] mp = polygonsOf(mirrored.elements.get(0).part);
		final Object[] np = polygonsOf(normal.elements.get(0).part);
		if (mp == null || np == null) {
			check("两种都取到多边形", false);
			return;
		}
		/*
		 * MC 的 CubeListBuilder.mirror(true) 不改 UV 区域，只翻转<b>顶点顺序</b>
		 * （实测：west 面 (u=116,v=216)→(u=116,v=232) 的首末两点对调），
		 * 效果是贴图沿 V 方向镜像。
		 * 故断言"首顶点与末顶点的 V 互换"，而不是"UV 矩形变了"。
		 */
		final float[] mv = vertexVs(mp[2]);
		final float[] nv = vertexVs(np[2]);
		check("mirror_uv=true 翻转顶点 V 顺序", mv != null && nv != null && mv.length >= 4 && nv.length >= 4
				&& near(mv[0], nv[3], 0.5F) && near(mv[3], nv[0], 0.5F));
		check("mirror_uv=false 首末顶点 V 不同", nv != null && nv.length >= 4
				&& !near(nv[0], nv[3], 0.5F));
	}

	/** 嵌套组：子组元素仍归属顶层组名（上游 nameToPart 的键是顶层组） */
	private static void testNestedGroups() {
		System.out.println("[BbModelTree 嵌套组归属]");
		final var tree = mtrnext.client.render.BbModelTree.parseForTest("""
				{ "resolution": { "width": 64, "height": 64 },
				  "elements": [ { "uuid": "e1", "name": "cube", "from": [0, 0, 0], "to": [16, 16, 16],
				                  "uv_offset": [0, 0], "origin": [0, 0, 0] },
				                { "uuid": "e2", "name": "cube2", "from": [0, 0, 0], "to": [16, 16, 16],
				                  "uv_offset": [0, 0], "origin": [0, 0, 0] } ],
				  "outliner": [ { "name": "top", "origin": [0, 0, 0], "children": [
				                   { "name": "sub", "origin": [0, 0, 0], "children": ["e1", "e2"] } ] } ] }
				""", 0.0F);
		check("解析出 2 个元素", tree.elements.size() == 2);
		for (final var el : tree.elements) {
			check("子组元素归属顶层组 top", "top".equals(el.groupName));
		}
		check("顶层组名只有 top", tree.groupNames.size() == 1 && "top".equals(tree.groupNames.get(0)));
	}

	/** 扁平模型（无 outliner/groups）：退化路径每元素自成一组 */
	private static void testFlatModelFallback() {
		System.out.println("[BbModelTree 扁平退化]");
		final var tree = mtrnext.client.render.BbModelTree.parseForTest("""
				{ "resolution": { "width": 64, "height": 64 },
				  "elements": [ { "uuid": "e1", "name": "cube", "from": [0, 0, 0], "to": [16, 16, 16],
				                  "uv_offset": [0, 0], "origin": [0, 0, 0] } ] }
				""", 0.0F);
		check("退化出 1 个元素", tree.elements.size() == 1);
		check("退化出 1 个组", tree.groupNames.size() == 1);
	}

	/**
	 * 顶层组旋转被强制为 0（上游 BlockbenchOutline.add L40-47：
	 * previous==null 时 rotation 传 DoubleArrayList.of(0,0,0)）。
	 */
	private static void testTopLevelRotationForcedZero() {
		System.out.println("[BbModelTree 顶层组旋转强制 0]");
		final var tree = mtrnext.client.render.BbModelTree.parseForTest("""
				{ "resolution": { "width": 64, "height": 64 },
				  "elements": [ { "uuid": "e1", "name": "cube", "from": [0, 0, 0], "to": [16, 16, 16],
				                  "uv_offset": [0, 0], "origin": [0, 0, 0] } ],
				  "outliner": [ { "name": "grp", "origin": [0, 0, 0], "rotation": [0, 0, 90],
				                  "children": ["e1"] } ] }
				""", 0.0F);
		check("解析出 1 个元素", tree.elements.size() == 1);
		if (tree.elements.isEmpty()) {
			return;
		}
		final var el = tree.elements.get(0);
		// 顶层组旋转被丢弃 → 链上不应出现 90° 的 Z 旋转
		check("顶层组旋转未生效", near(el.holder.zRot, 0.0F, 0.001F));
	}

	/**
	 * null 资源安全：props-only 层（无 properties / 无位定义）会让
	 * {@code propertiesResource} / {@code positionDefinitionsResource} 为 null。
	 * {@code ConcurrentHashMap.computeIfAbsent(null, …)} 会 NPE —— 必须在
	 * 加载器入口就短路掉。
	 */
	private static void testNullResourceSafety() {
		System.out.println("[BbModelTree null 资源安全]");
		final var props = mtrnext.client.render.Properties4xLoader.load(null, null);
		check("properties=null 不抛异常", props != null);
		check("properties=null 时 parts 为空", props.parts != null && props.parts.isEmpty());
		final var props2 = mtrnext.client.render.Properties4xLoader.load(null, "");
		check("properties=\"\" 不抛异常", props2 != null);
		final var defs = mtrnext.client.render.PositionDefinitions4xLoader.load(null, null);
		check("位定义=null 不抛异常", defs != null && defs.isEmpty());
		final var defs2 = mtrnext.client.render.PositionDefinitions4xLoader.load(null, "");
		check("位定义=\"\" 不抛异常", defs2 != null);
	}

	// ------------------------------------------------------------------
	// 反射工具：读取 ModelPart.Cube 的多边形与 UV 矩形
	// ------------------------------------------------------------------

	private static final Field cubeField;
	private static final Field cubePolygonsField;
	private static final Field polygonVerticesField;
	private static final Field vertexUField;
	private static final Field vertexVField;

	static {
		try {
			cubeField = ModelPart.class.getDeclaredField("cubes");
			cubeField.setAccessible(true);
			final Class<?> cubeCls = Class.forName("net.minecraft.client.model.geom.ModelPart$Cube");
			cubePolygonsField = cubeCls.getDeclaredField("polygons");
			cubePolygonsField.setAccessible(true);
			final Class<?> polyCls = Class.forName("net.minecraft.client.model.geom.ModelPart$Polygon");
			polygonVerticesField = polyCls.getDeclaredField("vertices");
			polygonVerticesField.setAccessible(true);
			final Class<?> vtxCls = Class.forName("net.minecraft.client.model.geom.ModelPart$Vertex");
			vertexUField = vtxCls.getDeclaredField("u");
			vertexUField.setAccessible(true);
			vertexVField = vtxCls.getDeclaredField("v");
			vertexVField.setAccessible(true);
		} catch (final Throwable t) {
			throw new ExceptionInInitializerError(t);
		}
	}

	private static Object[] polygonsOf(ModelPart part) {
		try {
			final List<?> cubes = (List<?>) cubeField.get(part);
			if (cubes == null || cubes.isEmpty()) {
				return null;
			}
			return (Object[]) cubePolygonsField.get(cubes.get(0));
		} catch (final Throwable t) {
			return null;
		}
	}

	/**
	 * 取多边形的 UV 包围矩形 {minU, maxU, minV, maxV}。
	 * <p>
	 * MC bake 时把 UV 按纹理尺寸归一化到 0..1，这里乘回像素。
	 */
	private static float[] uvRectOf(Object polygon) {
		try {
			final Object[] vertices = (Object[]) polygonVerticesField.get(polygon);
			if (vertices == null || vertices.length == 0) {
				return null;
			}
			float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE;
			float minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
			for (final Object vertex : vertices) {
				final float u = vertexUField.getFloat(vertex) * 1024.0F;
				final float v = vertexVField.getFloat(vertex) * 1024.0F;
				minU = Math.min(minU, u);
				maxU = Math.max(maxU, u);
				minV = Math.min(minV, v);
				maxV = Math.max(maxV, v);
			}
			return new float[]{minU, maxU, minV, maxV};
		} catch (final Throwable t) {
			return null;
		}
	}

	/** 取多边形各顶点的 V 序列（像素），用于判定 mirror 的顶点顺序翻转 */
	private static float[] vertexVs(Object polygon) {
		try {
			final Object[] vertices = (Object[]) polygonVerticesField.get(polygon);
			if (vertices == null) {
				return null;
			}
			final float[] out = new float[vertices.length];
			for (int i = 0; i < vertices.length; i++) {
				out[i] = vertexVField.getFloat(vertices[i]) * 1024.0F;
			}
			return out;
		} catch (final Throwable t) {
			return null;
		}
	}

	private static void check(String label, boolean ok) {
		if (ok) {
			pass++;
			System.out.println("  ok   " + label);
		} else {
			fail++;
			System.out.println("  FAIL " + label);
		}
	}

	private static boolean near(float a, float b, float eps) {
		return Math.abs(a - b) <= eps;
	}
}
