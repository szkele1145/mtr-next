package mtrnext.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtrnext.MtrNext;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [0.35] bbmodel → MC 原生模型树：复刻上游 MTR 4 的<b>构造过程</b>，而不是它的结果。
 * <p>
 * 历史教训：0.29 起本项目用"手算四边面"渲染 bbmodel，连续踩坑——元素旋转被忽略、
 * 组链层级丢失、box-UV 与 per-face UV 对不上（见 docs/bbox-uv-flaw.md、docs/bbmodel-native-construction.md）。
 * 根因是试图反推上游的<b>结果</b>。
 * <p>
 * 上游其实不做任何几何或 UV 计算：它把 bbmodel 转成 MC 自己的 {@link ModelPart} 树
 * （{@link MeshDefinition} → {@link PartDefinition} → {@link CubeListBuilder}），
 * 再交给 {@link ModelPart#render} 绘制。本类照抄这条链，于是以下语义一次性自动正确：
 * <ul>
 *   <li>box-UV 布局 —— MC 的 {@code addBox(..., u, v)} 自己算六个面的 UV</li>
 *   <li>元素旋转 —— 每个元素挂一个带 origin/rotation 的姿态节点</li>
 *   <li>组链层级 —— 逐级 {@code addOrReplaceChild}，pivot 做逐级差分</li>
 *   <li>{@code modelYOffset} —— 只作用在链上第一节点的 pivotY</li>
 *   <li>{@code inflate} / {@code mirror_uv} / {@code shade} —— 直接进 {@link CubeDeformation} 与 {@code mirror}</li>
 * </ul>
 * 上游对应源码：{@code BlockbenchModel}、{@code BlockbenchOutline}、{@code BlockbenchElement}、
 * {@code GroupTransformations}、{@code ModelPartExtension}。
 */
public final class BbModelTree {

	private static final Map<String, BbModelTree> CACHE = new ConcurrentHashMap<>();

	public final String modelId;
	/** bbmodel resolution，MC bake 用 */
	public final int textureWidth;
	public final int textureHeight;
	/** bake 后的根节点 */
	public final ModelPart root;
	/** 顶层 outliner 组名 → 该组的锚点节点（上游 nameToPart 同义） */
	public final Map<String, ModelPart> groupParts;
	/** 顶层 outliner 组名，保持 bbmodel 顺序 */
	public final List<String> groupNames;
	/** 全部元素，保持 bbmodel elements 顺序 */
	public final List<Element> elements;

	private BbModelTree(String modelId, int textureWidth, int textureHeight, ModelPart root,
			Map<String, ModelPart> groupParts, List<String> groupNames, List<Element> elements) {
		this.modelId = modelId;
		this.textureWidth = textureWidth;
		this.textureHeight = textureHeight;
		this.root = root;
		this.groupParts = groupParts;
		this.groupNames = groupNames;
		this.elements = elements;
	}

	/** 单个 bbmodel 元素（一个 cuboid）在 MC 树中的落点 */
	public static final class Element {

		public final String uuid;
		/** bbmodel 里的 name（可能为空串） */
		public final String name;
		/** 所属顶层 outliner 组名 */
		public final String groupName;
		/** 元素 holder 节点；门位移就 setPos 在这个节点上 */
		public final ModelPart part;
		/**
		 * 元素链的<b>末节节点</b>（承载元素自身的 origin/rotation）。
		 * <p>
		 * 上游语义：链是「锚点 → 组节… → 元素节」，cube 挂在元素节之下，
		 * 而<b>旋转在元素节上</b>。故断言旋转要读本节点，不是 {@link #part}。
		 */
		public final ModelPart holder;
		/**
		 * 从锚点到元素节的完整节点链（自上而下）。
		 * <p>
		 * modelYOffset 只落在<b>第一节</b>（上游 GroupTransformations.create
		 * L33-35、41），故要断言 offset 需看 chain.get(0)。
		 */
		public final List<ModelPart> chain;
		/** MC 坐标下的中心 X（格），用于调试/包围盒 */
		public final float centerX;
		public final float centerY;
		public final float centerZ;
		public final float sizeX;
		public final float sizeY;
		public final float sizeZ;
		/**
		 * [0.36.5] 上游 {@code BlockbenchElement.setModelPart} 的
		 * {@code origin}（元素自身原点，bbmodel 像素，已含 modelYOffset）
		 * 与 {@code rotation}（角度制）。
		 * <p>
		 * 保留它们才能复刻上游返回的 {@code Box}
		 * （{@code docs/_upstream/BlockbenchElement.java:48-59}），
		 * 该 Box 是上游门重叠检测（{@code getOpenDoorBounds}）的输入。
		 */
		public final float originX;
		public final float originY;
		public final float originZ;
		public final float rotationX;
		public final float rotationY;
		public final float rotationZ;
		/**
		 * [0.36.5] 盒体在 MC 空间的位置（像素），与上游 L34-36 一致：
		 * {@code x = -to[0] - originX}、{@code y = -to[1] - originY - modelYOffset*16}、
		 * {@code z = from[2] - originZ}。
		 */
		public final float cubeX;
		public final float cubeY;
		public final float cubeZ;
		/** [0.36.5] 盒体尺寸（像素），上游 L37-39 的 round(to - from) */
		public final float pxSizeX;
		public final float pxSizeY;
		public final float pxSizeZ;

		Element(String uuid, String name, String groupName, ModelPart part, ModelPart holder,
				List<ModelPart> chain,
				float centerX, float centerY, float centerZ,
				float sizeX, float sizeY, float sizeZ,
				float originX, float originY, float originZ,
				float rotationX, float rotationY, float rotationZ,
				float cubeX, float cubeY, float cubeZ,
				float pxSizeX, float pxSizeY, float pxSizeZ) {
			this.uuid = uuid;
			this.name = name;
			this.groupName = groupName;
			this.part = part;
			this.holder = holder;
			this.chain = chain;
			this.centerX = centerX;
			this.centerY = centerY;
			this.centerZ = centerZ;
			this.sizeX = sizeX;
			this.sizeY = sizeY;
			this.sizeZ = sizeZ;
			this.originX = originX;
			this.originY = originY;
			this.originZ = originZ;
			this.rotationX = rotationX;
			this.rotationY = rotationY;
			this.rotationZ = rotationZ;
			this.cubeX = cubeX;
			this.cubeY = cubeY;
			this.cubeZ = cubeZ;
			this.pxSizeX = pxSizeX;
			this.pxSizeY = pxSizeY;
			this.pxSizeZ = pxSizeZ;
		}

		/**
		 * [0.36.5] 复刻上游 {@code BlockbenchElement.setModelPart} 返回的
		 * {@code Box}（BB 坐标系，单位：格）。
		 * <p>
		 * 上游（{@code docs/_upstream/BlockbenchElement.java:48-59}）：
		 * <pre>
		 * vector1 = (x, y, z).rotateX(rx).rotateY(ry).rotateZ(rz)
		 * vector2 = (x+sizeX, y+sizeY, z+sizeZ).rotateX(rx).rotateY(ry).rotateZ(rz)
		 * Box(-(v1.x+originX)/16, -(v1.y+originY)/16, -(v1.z+originZ)/16,
		 *     -(v2.x+originX)/16, -(v2.y+originY)/16, -(v2.z+originZ)/16)
		 * </pre>
		 * 旋转角为弧度制（上游 L26-28：rx=rad(-r0), ry=rad(-r1), rz=rad(+r2)）。
		 * <p>
		 * <b>[0.36.6 更正]</b>：本方法返回<b>归一化后</b>的
		 * [minX, minY, minZ, maxX, maxY, maxZ]，与上游<b>行为等效</b>——
		 * 但理由与 0.36.5 的记载<b>不同</b>，此处更正如下。
		 * <p>
		 * 0.36.5 曾断言「上游 Box 三轴倒序 ⇒ MC {@code Box.intersects} 恒 false
		 * ⇒ 上游 {@code testDoors} 永不报重叠」——<b>该断言是错的</b>。
		 * 2026-10-21 取到上游二进制（{@code Minecraft-Mappings-fabric-1.20.1-0.0.1.jar}
		 * 的 {@code org.mtr.mapping.holder.Box}）并反编译核实：
		 * <ul>
		 *   <li>上游 {@code Box(double×6)} 构造器直接
		 *       {@code new class_238(x1,y1,z1,x2,y2,z2)}；</li>
		 *   <li>MC 1.20.1 的 {@code AABB} 六参构造器内部用
		 *       {@code Math.min}/{@code Math.max} <b>自行归一化</b>：<br>
		 *       {@code minX=Math.min(x1,x2)}, {@code maxX=Math.max(x1,x2)}（X/Y/Z 同）；</li>
		 *   <li>⇒ 上游拿到的 Box <b>始终是有序的</b>（min ≤ max），
		 *       {@code testDoors} <b>能正常检测重叠</b>。</li>
		 * </ul>
		 * <p>
		 * 同批核实（MC {@code Vec3} 字节码）：{@code xRot/yRot/zRot} 均为
		 * 绕原点的标准右手旋转，{@code xRot} 保 X、{@code yRot} 保 Y、{@code zRot} 保 Z，
		 * 组合顺序 X→Y→Z——与本类 {@link #rotate(double, double, double, double, double, double)}
		 * 的实现逐项一致。
		 *
		 * @return 6 元数组 [minX, minY, minZ, maxX, maxY, maxZ]（格，已归一化）
		 */
		public float[] upstreamBox() {
			final double rx = Math.toRadians(-rotationX);
			final double ry = Math.toRadians(-rotationY);
			final double rz = Math.toRadians(rotationZ);

			final double[] v1 = rotate(cubeX, cubeY, cubeZ, rx, ry, rz);
			final double[] v2 = rotate(cubeX + pxSizeX, cubeY + pxSizeY, cubeZ + pxSizeZ, rx, ry, rz);
			final float x1 = (float) (-(v1[0] + originX) / 16.0);
			final float y1 = (float) (-(v1[1] + originY) / 16.0);
			final float z1 = (float) (-(v1[2] + originZ) / 16.0);
			final float x2 = (float) (-(v2[0] + originX) / 16.0);
			final float y2 = (float) (-(v2[1] + originY) / 16.0);
			final float z2 = (float) (-(v2[2] + originZ) / 16.0);
			return new float[]{
					Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
					Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)};
		}

		/** 上游 {@code Vector3d.rotateX().rotateY().rotateZ()}（先 X 后 Y 后 Z） */
		private static double[] rotate(double x, double y, double z, double rx, double ry, double rz) {
			// rotateX
			double cos = Math.cos(rx);
			double sin = Math.sin(rx);
			double ny = y * cos - z * sin;
			double nz = y * sin + z * cos;
			y = ny;
			z = nz;
			// rotateY
			cos = Math.cos(ry);
			sin = Math.sin(ry);
			double nx = x * cos + z * sin;
			nz = -x * sin + z * cos;
			x = nx;
			z = nz;
			// rotateZ
			cos = Math.cos(rz);
			sin = Math.sin(rz);
			nx = x * cos - y * sin;
			ny = x * sin + y * cos;
			return new double[]{nx, ny, z};
		}
	}

	public static void invalidate() {
		CACHE.clear();
	}

	/** 查缓存（不构建） */
	public static BbModelTree cached(String cacheKey) {
		return CACHE.get(cacheKey);
	}

	/** 按 cacheKey 缓存；调用方负责给出稳定的、能唯一标识这份 bbmodel + properties 的键 */
	public static BbModelTree of(String cacheKey, InputStream stream, float modelYOffset) {
		final BbModelTree existing = CACHE.get(cacheKey);
		if (existing != null) {
			return existing;
		}
		final BbModelTree parsed = parse(stream, cacheKey, modelYOffset);
		CACHE.put(cacheKey, parsed);
		return parsed;
	}

	/**
	 * 离线自测入口：直接喂 bbmodel 文本。
	 * <p>
	 * 与 {@link #of} 的区别：不进缓存、不需要 ResourceManager。
	 */
	public static BbModelTree parseForTest(String json, float modelYOffset) {
		final java.io.InputStream stream = new java.io.ByteArrayInputStream(
				json.getBytes(StandardCharsets.UTF_8));
		return parse(stream, "test", modelYOffset);
	}

	// ------------------------------------------------------------------
	// 上游 GroupTransformations：一层组链变换（pivot + 欧拉角，弧度）
	// ------------------------------------------------------------------

	private static final class GroupTransformation {

		final float pivotX;
		final float pivotY;
		final float pivotZ;
		final float rotateX;
		final float rotateY;
		final float rotateZ;

		GroupTransformation(float pivotX, float pivotY, float pivotZ,
				float rotateX, float rotateY, float rotateZ) {
			this.pivotX = pivotX;
			this.pivotY = pivotY;
			this.pivotZ = pivotZ;
			this.rotateX = rotateX;
			this.rotateY = rotateY;
			this.rotateZ = rotateZ;
		}
	}

	/** 不可变链：追加即拷贝，因此兄弟节点复用父链不会互相污染（同上游） */
	private static final class GroupChain {

		static final GroupChain EMPTY = new GroupChain(List.of());

		final List<GroupTransformation> links;

		GroupChain(List<GroupTransformation> links) {
			this.links = links;
		}

		/**
		 * 上游 {@code new GroupTransformations(gt, origin, rotation)}：
		 * pivot = (-originX, -originY, +originZ)，rot = (-rx, -ry, +rz) 转弧度。
		 */
		GroupChain append(JsonArray origin, JsonArray rotation) {
			final List<GroupTransformation> next = new ArrayList<>(links.size() + 1);
			next.addAll(links);
			next.add(new GroupTransformation(
					-f(origin, 0), -f(origin, 1), f(origin, 2),
					(float) Math.toRadians(-f(rotation, 0)),
					(float) Math.toRadians(-f(rotation, 1)),
					(float) Math.toRadians(f(rotation, 2))));
			return new GroupChain(next);
		}

		/**
		 * 上游 {@code GroupTransformations.create(ModelPartExtension, modelYOffset)}：
		 * 逐节 {@code setPivot(pivot - combined[, offset])} + {@code setRotation} + {@code addChild()}。
		 * <b>offset 只作用在第一节</b>，之后清零。
		 * <p>
		 * 名字路径继承自父节点（{@code parent.path}），这样 bake 后能按
		 * "从 mesh root 起的完整路径"取回节点。
		 */
		Created create(Created parent, float modelYOffset, Names names) {
			PartDefinition current = parent.part;
			final List<String> path = new ArrayList<>(parent.path);
			final List<String> linkNames = new ArrayList<>(links.size());
			float combinedX = 0;
			float combinedY = 0;
			float combinedZ = 0;
			float offset = modelYOffset * 16.0F;
			for (final GroupTransformation gt : links) {
				final String name = names.next();
				current = current.addOrReplaceChild(name, CubeListBuilder.create(),
						PartPose.offsetAndRotation(
								gt.pivotX - combinedX,
								gt.pivotY - combinedY - offset,
								gt.pivotZ - combinedZ,
								gt.rotateX, gt.rotateY, gt.rotateZ));
				path.add(name);
				linkNames.add(name);
				combinedX += gt.pivotX;
				combinedY += gt.pivotY;
				combinedZ += gt.pivotZ;
				offset = 0;
			}
			return new Created(current, path, linkNames);
		}
	}

	/** 链创建结果：末端 PartDefinition + 从 mesh root 起的名字路径 */
	private static final class Created {

		final PartDefinition part;
		final List<String> path;
		/** 本次 create() 新建的链节名字（自上而下），供 offset 断言用 */
		final List<String> linkNames;

		Created(PartDefinition part, List<String> path) {
			this(part, path, List.of());
		}

		Created(PartDefinition part, List<String> path, List<String> linkNames) {
			this.part = part;
			this.path = path;
			this.linkNames = linkNames;
		}

		Created child(String childName, CubeListBuilder builder, PartPose pose) {
			final List<String> next = new ArrayList<>(path.size() + 1);
			next.addAll(path);
			next.add(childName);
			return new Created(part.addOrReplaceChild(childName, builder, pose), next);
		}
	}

	/** 名字生成器：同一次 parse 内唯一（避免 addOrReplaceChild 互相覆盖） */
	private static final class Names {

		private int counter = 0;

		String next() {
			return "n" + (counter++);
		}
	}

	// ------------------------------------------------------------------
	// 解析
	// ------------------------------------------------------------------

	private static BbModelTree parse(InputStream stream, String cacheKey, float modelYOffset) {
		JsonObject rootObj;
		try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
			final JsonElement parsed = JsonParser.parseReader(reader);
			if (!parsed.isJsonObject()) {
				MtrNext.LOGGER.error("[bbmodel] 根节点不是对象: {}", cacheKey);
				return empty(cacheKey);
			}
			rootObj = parsed.getAsJsonObject();
		} catch (Exception ex) {
			MtrNext.LOGGER.error("[bbmodel] 解析失败 {}", cacheKey, ex);
			return empty(cacheKey);
		}

		final JsonObject resolution = obj(rootObj, "resolution");
		int texW = resolution == null ? 0 : Math.round(f(resolution, "width"));
		int texH = resolution == null ? 0 : Math.round(f(resolution, "height"));
		if (texW <= 0 || texH <= 0) {
			texW = 16;
			texH = 16;
		}

		final Map<String, JsonObject> uuidToElement = new HashMap<>();
		if (rootObj.has("elements") && rootObj.get("elements").isJsonArray()) {
			for (final JsonElement el : rootObj.getAsJsonArray("elements")) {
				if (el.isJsonObject()) {
					final JsonObject e = el.getAsJsonObject();
					final String uuid = str(e, "uuid", "");
					if (!uuid.isEmpty()) {
						uuidToElement.put(uuid, e);
					}
				}
			}
		}

		final Names names = new Names();
		final MeshDefinition mesh = new MeshDefinition();
		final PartDefinition meshRoot = mesh.getRoot();
		final Map<String, ModelPart> groupParts = new LinkedHashMap<>();
		final List<String> groupNames = new ArrayList<>();
		final List<Created> anchors = new ArrayList<>();
		final List<ElementDraft> drafts = new ArrayList<>();

		for (final JsonObject group : topLevelGroups(rootObj)) {
			final String groupName = str(group, "name", "");
			// 上游 parentModelPart = createModelPart()：每个顶层组各挂一个零姿态锚点，
			// 之后该组所有元素从这条锚点各自建链（组本身不再建节点）
			final Created anchor = new Created(meshRoot, List.of())
					.child(names.next(), CubeListBuilder.create(), PartPose.ZERO);
			addGroup(group, null, GroupChain.EMPTY, anchor, groupName,
					uuidToElement, names, drafts, modelYOffset, 0);
			groupNames.add(groupName);
			anchors.add(anchor);
		}

		final ModelPart bakedRoot = LayerDefinition.create(mesh, texW, texH).bakeRoot();

		// bake 后按名字路径取回节点
		for (int i = 0; i < groupNames.size() && i < anchors.size(); i++) {
			final ModelPart part = resolve(bakedRoot, anchors.get(i).path);
			if (part != null) {
				groupParts.put(groupNames.get(i), part);
			}
		}

		final List<Element> elements = new ArrayList<>(drafts.size());
		for (final ElementDraft draft : drafts) {
			final ModelPart part = resolve(bakedRoot, draft.path);
			final ModelPart holder = resolve(bakedRoot, draft.holderPath);
			if (part == null) {
				continue;
			}
			// 链节路径 = holderPath 的末 N 段（N = 链节数）
			final int linkCount = draft.linkNames.size();
			final List<ModelPart> chain = new ArrayList<>(linkCount);
			for (int i = 0; i < linkCount; i++) {
				final int cut = draft.holderPath.size() - linkCount + i;
				final ModelPart link = resolve(bakedRoot, draft.holderPath.subList(0, cut + 1));
				if (link != null) {
					chain.add(link);
				}
			}
			elements.add(new Element(draft.uuid, draft.name, draft.groupName, part, holder, chain,
					draft.centerX, draft.centerY, draft.centerZ,
					draft.sizeX, draft.sizeY, draft.sizeZ,
					draft.originX, draft.originY, draft.originZ,
					draft.rotationX, draft.rotationY, draft.rotationZ,
					draft.cubeX, draft.cubeY, draft.cubeZ,
					draft.pxSizeX, draft.pxSizeY, draft.pxSizeZ));
		}

		MtrNext.LOGGER.debug("[bbmodel] {} 构建完成：{} 组 / {} 元素 / 纹理 {}x{}",
				cacheKey, groupNames.size(), elements.size(), texW, texH);
		return new BbModelTree(cacheKey, texW, texH, bakedRoot, groupParts, groupNames, elements);
	}

	/** 元素在 bake 前的暂存信息（bake 后才能拿到 ModelPart） */
	private static final class ElementDraft {

		final String uuid;
		final String name;
		final String groupName;
		final List<String> path;
		/** 元素链末节节点的路径（承载元素自身 origin/rotation） */
		final List<String> holderPath;
		/** 链节名字（自上而下），modelYOffset 落在第 0 节 */
		final List<String> linkNames;
		final float centerX;
		final float centerY;
		final float centerZ;
		final float sizeX;
		final float sizeY;
		final float sizeZ;
		// [0.36.5] 上游 Box 复刻所需
		final float originX;
		final float originY;
		final float originZ;
		final float rotationX;
		final float rotationY;
		final float rotationZ;
		final float cubeX;
		final float cubeY;
		final float cubeZ;
		final float pxSizeX;
		final float pxSizeY;
		final float pxSizeZ;

		ElementDraft(String uuid, String name, String groupName, List<String> path, List<String> holderPath,
				List<String> linkNames,
				float centerX, float centerY, float centerZ,
				float sizeX, float sizeY, float sizeZ,
				float originX, float originY, float originZ,
				float rotationX, float rotationY, float rotationZ,
				float cubeX, float cubeY, float cubeZ,
				float pxSizeX, float pxSizeY, float pxSizeZ) {
			this.uuid = uuid;
			this.name = name;
			this.groupName = groupName;
			this.path = path;
			this.holderPath = holderPath;
			this.linkNames = linkNames;
			this.centerX = centerX;
			this.centerY = centerY;
			this.centerZ = centerZ;
			this.sizeX = sizeX;
			this.sizeY = sizeY;
			this.sizeZ = sizeZ;
			this.originX = originX;
			this.originY = originY;
			this.originZ = originZ;
			this.rotationX = rotationX;
			this.rotationY = rotationY;
			this.rotationZ = rotationZ;
			this.cubeX = cubeX;
			this.cubeY = cubeY;
			this.cubeZ = cubeZ;
			this.pxSizeX = pxSizeX;
			this.pxSizeY = pxSizeY;
			this.pxSizeZ = pxSizeZ;
		}
	}

	/**
	 * 递归处理一个 outliner 组：先算本组在链上的变换，再展开 children
	 * （children 里既有元素 uuid 字符串，也有嵌套组对象——两种风格都要支持）。
	 */
	private static void addGroup(JsonObject group, JsonObject parent, GroupChain parentChain,
			Created anchor, String topGroupName, Map<String, JsonObject> uuidToElement,
			Names names, List<ElementDraft> drafts, float modelYOffset, int depth) {
		if (depth > 64) {
			MtrNext.LOGGER.warn("[bbmodel] outliner 嵌套过深，跳过组 {}", str(group, "name", "?"));
			return;
		}

		final GroupChain chain = outlineAdd(parentChain, parent, group);

		// 注意：这里<b>不为组本身建节点</b>。上游 iterateChildren 只累积
		// GroupTransformations 链，真正的 ModelPart 由每个元素调用
		// BlockbenchElement.setModelPart 时从锚点一次性建出来
		// （groupTransformations + 元素自身 origin/rotation → create()）。
		// 若在此处建节点再把 anchor 换成它，嵌套组会把父链重复应用一次。
		if (group.has("children") && group.get("children").isJsonArray()) {
			for (final JsonElement child : group.getAsJsonArray("children")) {
				if (child.isJsonPrimitive()) {
					// 上游用 uuidToElement.remove(uuid)：同一元素被多处引用时只处理一次
					final JsonObject element = uuidToElement.remove(child.getAsString());
					if (element != null) {
						addElement(element, chain, anchor, topGroupName, names, drafts, modelYOffset);
					}
				} else if (child.isJsonObject()) {
					addGroup(child.getAsJsonObject(), group, chain, anchor, topGroupName,
							uuidToElement, names, drafts, modelYOffset, depth + 1);
				}
			}
		}
	}

	/**
	 * 上游 {@code BlockbenchOutline.add}：顶层组的 rotation 被强制为 0（并 WARN），
	 * 嵌套组用相对父组的 origin 差分 + 自己的 rotation。
	 */
	private static GroupChain outlineAdd(GroupChain parentChain, JsonObject parent, JsonObject group) {
		final JsonArray origin = arr(group, "origin");
		final JsonArray rotation = arr(group, "rotation");
		if (parent == null) {
			if (Math.abs(f(rotation, 0)) > 1 || Math.abs(f(rotation, 1)) > 1 || Math.abs(f(rotation, 2)) > 1) {
				MtrNext.LOGGER.warn("[bbmodel] 跳过顶层组 rotation（上游同行为）: {}", str(group, "name", "?"));
			}
			return parentChain.append(origin, EMPTY_ARRAY);
		}
		final JsonArray parentOrigin = arr(parent, "origin");
		final JsonArray diff = new JsonArray();
		diff.add(f(origin, 0) - f(parentOrigin, 0));
		diff.add(f(origin, 1) - f(parentOrigin, 1));
		diff.add(f(origin, 2) - f(parentOrigin, 2));
		return parentChain.append(diff, rotation);
	}

	/** 上游 {@code BlockbenchElement.setModelPart} 的坐标/尺寸/UV 推导（单位为 bbmodel 像素） */
	private static void addElement(JsonObject element, GroupChain chain, Created anchor,
			String topGroupName, Names names, List<ElementDraft> drafts, float modelYOffset) {
		final JsonArray origin = arr(element, "origin");
		final JsonArray from = arr(element, "from");
		final JsonArray to = arr(element, "to");
		final JsonArray rotation = arr(element, "rotation");

		// 上游 BlockbenchElement.setModelPart L23-25：
		//   originX = -origin[0]
		//   originY = -origin[1] - modelYOffset * 16
		//   originZ = +origin[2]
		final float originX = -f(origin, 0);
		final float originY = -f(origin, 1) - modelYOffset * 16.0F;
		final float originZ = f(origin, 2);
		// 上游 L34-36：x = -to[0] - originX；y = -to[1] - originY - modelYOffset*16；z = from[2] - originZ
		final float x = -f(to, 0) - originX;
		final float y = -f(to, 1) - originY - modelYOffset * 16.0F;
		final float z = f(from, 2) - originZ;

		final int sizeX = Math.round(f(to, 0) - f(from, 0));
		final int sizeY = Math.round(f(to, 1) - f(from, 1));
		final int sizeZ = Math.round(f(to, 2) - f(from, 2));

		final JsonArray uvOffset = arr(element, "uv_offset");
		final int u = Math.round(f(uvOffset, 0));
		final int v = Math.round(f(uvOffset, 1));

		final float inflate = f(element, "inflate");
		// shade 缺省为 true；上游镜像条件 = !shade || mirror_uv
		final boolean shade = !element.has("shade") || element.get("shade").getAsBoolean();
		final boolean mirrorUv = element.has("mirror_uv") && element.get("mirror_uv").getAsBoolean();

		// 上游：元素自身的 origin/rotation 追加为链的最后一节，然后从锚点
		// 一次性建出整条链（modelYOffset 只落在第一节，即组链首节）。
		// 元素 y 坐标额外再减一次 modelYOffset*16（BlockbenchElement L35）。
		final Created holder = chain.append(origin, rotation).create(anchor, modelYOffset, names);
		final String cubeName = names.next();
		final Created built = holder.child(cubeName,
				CubeListBuilder.create()
						.mirror(!shade || mirrorUv)
						.addBox(cubeName, x, y, z, sizeX, sizeY, sizeZ,
								new CubeDeformation(inflate), u, v),
				PartPose.ZERO);

		// MC 坐标：X/Y 取负（上游同约定），Z 保持。
		// 盒中心 = 盒原点 + 尺寸/2（单位像素），再换算成格。
		final float centerX = (x + sizeX / 2.0F) / 16.0F;
		final float centerY = (y + sizeY / 2.0F) / 16.0F;
		final float centerZ = (z + sizeZ / 2.0F) / 16.0F;

		// [0.36.5] 上游 Box 复刻所需的原始量：
		//   origin（元素自身原点，BB 像素）/ rotation（角度制）
		//   cube(x,y,z) 与尺寸（像素）——即上游 L34-39 的中间量
		drafts.add(new ElementDraft(str(element, "uuid", ""), str(element, "name", ""),
				topGroupName, built.path, holder.path, holder.linkNames, centerX, centerY, centerZ,
				sizeX / 16.0F, sizeY / 16.0F, sizeZ / 16.0F,
				originX, originY, originZ,
				f(rotation, 0), f(rotation, 1), f(rotation, 2),
				x, y, z,
				sizeX, sizeY, sizeZ));
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private static ModelPart resolve(ModelPart root, List<String> path) {
		ModelPart current = root;
		for (final String name : path) {
			if (!current.hasChild(name)) {
				return null;
			}
			current = current.getChild(name);
		}
		return current;
	}

	private static List<JsonObject> topLevelGroups(JsonObject rootObj) {
		final List<JsonObject> out = new ArrayList<>();
		collectGroups(rootObj, "outliner", out);
		if (out.isEmpty()) {
			collectGroups(rootObj, "groups", out);
		}
		if (out.isEmpty()) {
			// 完全扁平：没有 outliner 也没有 groups。上游此处会拿不到任何 part；
			// 这里退化为"每个元素自成一组"，至少保证几何可见。
			if (rootObj.has("elements") && rootObj.get("elements").isJsonArray()) {
				int index = 0;
				for (final JsonElement el : rootObj.getAsJsonArray("elements")) {
					if (!el.isJsonObject()) {
						continue;
					}
					final JsonObject flat = new JsonObject();
					flat.addProperty("name", "element_" + index++);
					final JsonArray children = new JsonArray();
					children.add(str(el.getAsJsonObject(), "uuid", ""));
					flat.add("children", children);
					flat.add("origin", arr(el.getAsJsonObject(), "origin"));
					out.add(flat);
				}
			}
		}
		return out;
	}

	private static void collectGroups(JsonObject rootObj, String key, List<JsonObject> out) {
		if (!rootObj.has(key) || !rootObj.get(key).isJsonArray()) {
			return;
		}
		for (final JsonElement el : rootObj.getAsJsonArray(key)) {
			if (el.isJsonObject()) {
				out.add(el.getAsJsonObject());
			}
		}
	}

	private static BbModelTree empty(String cacheKey) {
		final ModelPart root = LayerDefinition.create(new MeshDefinition(), 16, 16).bakeRoot();
		return new BbModelTree(cacheKey, 16, 16, root, Map.of(), List.of(), List.of());
	}

	private static final JsonArray EMPTY_ARRAY = new JsonArray();

	private static JsonObject obj(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
	}

	private static JsonArray arr(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : EMPTY_ARRAY;
	}

	private static float f(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsFloat() : 0;
	}

	private static float f(JsonArray a, int i) {
		return a != null && a.size() > i && a.get(i).isJsonPrimitive() ? a.get(i).getAsFloat() : 0;
	}

	private static String str(JsonObject o, String key, String def) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
	}
}
