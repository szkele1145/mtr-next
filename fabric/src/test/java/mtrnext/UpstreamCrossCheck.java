package mtrnext;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【方法 A】离线逐字重算对拍：4.0 原逻辑 vs mtr-next {@code BbModelTree}。
 *
 * <p>本类把 4.0 的四个类逐字复刻成内部实现（不依赖 MTR 的 mapping 层）：
 * <ul>
 *   <li>{@code org.mtr.mapping.mapper.ModelPartExtension}（nameTree / setChild / addChild）</li>
 *   <li>{@code org.mtr.mod.resource.GroupTransformations}（每节 pivot 差分）</li>
 *   <li>{@code org.mtr.mod.resource.BlockbenchOutline.add}（父子 origin 差分 + 顶层 rotation 强制 0）</li>
 *   <li>{@code org.mtr.mod.render.DynamicVehicleModel} 构造流程（含 iterateChildren 传参）</li>
 * </ul>
 * 然后用同一份 bbmodel 建出两棵树，逐元素比较 cube 中心在模型空间的位置。
 */
public final class UpstreamCrossCheck {

	private UpstreamCrossCheck() {
	}

	// ==================================================================
	// 4.0 逐字复刻 — ModelPartExtension
	// ==================================================================

	/** 4.0 {@code org.mtr.mapping.mapper.ModelPartExtension} 逐字复刻（去掉 mapping 层） */
	static final class Ext {

		ModelPart modelPart;
		float tempPivotX, tempPivotY, tempPivotZ;
		float tempRotationX, tempRotationY, tempRotationZ;
		int tempU, tempV;
		PartDefinition modelPartData;
		boolean childAdded = false;
		final List<String> nameTree = new ArrayList<>();

		private static int counter = 0;

		static String randomName() {
			return "part" + (counter++);
		}

		Ext(PartDefinition modelPartData) {
			this.nameTree.add(randomName());
			this.modelPartData = modelPartData;
		}

		Ext(String name, PartDefinition modelPartData) {
			this.nameTree.add(name);
			this.modelPartData = modelPartData;
		}

		void setPivot(float x, float y, float z) {
			tempPivotX = x;
			tempPivotY = y;
			tempPivotZ = z;
		}

		Ext setTextureUVOffset(int u, int v) {
			tempU = u;
			tempV = v;
			return this;
		}

		void setRotation(float x, float y, float z) {
			tempRotationX = x;
			tempRotationY = y;
			tempRotationZ = z;
		}

		Ext addChild() {
			setChild();
			final String name = randomName();
			final Ext e = new Ext(name, modelPartData.addOrReplaceChild(name, CubeListBuilder.create(), getModelTransform()));
			e.nameTree.addAll(0, nameTree);
			return e;
		}

		void addCuboid(float x, float y, float z, int sizeX, int sizeY, int sizeZ, float inflation, boolean mirrored) {
			setChild();
			final String name = randomName();
			lastCubeName = name;
			final CubeListBuilder builder = CubeListBuilder.create().mirror(mirrored)
					.addBox(name, x, y, z, sizeX, sizeY, sizeZ, new CubeDeformation(inflation), tempU, tempV);
			modelPartData.addOrReplaceChild(name, builder, getModelTransform());
		}

		String lastCubeName;

		void setModelPart(ModelPart main) {
			modelPart = main;
			for (final String n : nameTree) {
				modelPart = modelPart.getChild(n);
			}
		}

		private void setChild() {
			if (!childAdded) {
				modelPartData = modelPartData.addOrReplaceChild(lastName(), CubeListBuilder.create(), getModelTransform());
				childAdded = true;
			}
		}

		private String lastName() {
			return nameTree.isEmpty() ? "" : nameTree.get(nameTree.size() - 1);
		}

		private PartPose getModelTransform() {
			return PartPose.offsetAndRotation(tempPivotX, tempPivotY, tempPivotZ, tempRotationX, tempRotationY, tempRotationZ);
		}
	}

	// ==================================================================
	// 4.0 逐字复刻 — GroupTransformations
	// ==================================================================

	static final class GT {

		final List<G> list = new ArrayList<>();

		GT() {
		}

		GT(GT other, JsonArray origin, JsonArray rotation) {
			list.addAll(other.list);
			list.add(new G((float) -d(origin, 0), (float) -d(origin, 1), (float) d(origin, 2),
					(float) Math.toRadians(-d(rotation, 0)),
					(float) Math.toRadians(-d(rotation, 1)),
					(float) Math.toRadians(d(rotation, 2))));
		}

		Ext create(Ext root, float modelYOffset) {
			Ext modelPart = root;
			float cx = 0, cy = 0, cz = 0;
			float offset = modelYOffset * 16.0F;
			for (final G g : list) {
				modelPart.setPivot(g.pivotX - cx, g.pivotY - cy - offset, g.pivotZ - cz);
				modelPart.setRotation(g.rotateX, g.rotateY, g.rotateZ);
				cx += g.pivotX;
				cy += g.pivotY;
				cz += g.pivotZ;
				modelPart = modelPart.addChild();
				offset = 0.0F;
			}
			return modelPart;
		}

		String describe() {
			final StringBuilder sb = new StringBuilder();
			float cx = 0, cy = 0, cz = 0;
			for (final G g : list) {
				sb.append(String.format("[pivotRel=(%.3f,%.3f,%.3f) rot=(%.3f,%.3f,%.3f)] ",
						g.pivotX - cx, g.pivotY - cy, g.pivotZ - cz, g.rotateX, g.rotateY, g.rotateZ));
				cx += g.pivotX;
				cy += g.pivotY;
				cz += g.pivotZ;
			}
			sb.append(String.format("Σ=(%.3f,%.3f,%.3f)", cx, cy, cz));
			return sb.toString();
		}
	}

	static final class G {

		final float pivotX, pivotY, pivotZ;
		final float rotateX, rotateY, rotateZ;

		G(float px, float py, float pz, float rx, float ry, float rz) {
			pivotX = px;
			pivotY = py;
			pivotZ = pz;
			rotateX = rx;
			rotateY = ry;
			rotateZ = rz;
		}
	}

	// ==================================================================
	// 4.0 逐字复刻 — BlockbenchOutline
	// ==================================================================

	static final class Outline {

		String name = "";
		JsonArray origin = new JsonArray();
		JsonArray rotation = new JsonArray();
		final List<String> childrenUuid = new ArrayList<>();
		final List<Outline> children = new ArrayList<>();

		static Outline read(JsonObject o) {
			final Outline out = new Outline();
			out.name = o.has("name") ? o.get("name").getAsString() : "";
			if (o.has("origin") && o.get("origin").isJsonArray()) {
				out.origin = o.getAsJsonArray("origin");
			}
			if (o.has("rotation") && o.get("rotation").isJsonArray()) {
				out.rotation = o.getAsJsonArray("rotation");
			}
			if (o.has("children") && o.get("children").isJsonArray()) {
				for (final JsonElement c : o.getAsJsonArray("children")) {
					if (c.isJsonPrimitive()) {
						out.childrenUuid.add(c.getAsString());
					} else if (c.isJsonObject()) {
						out.children.add(read(c.getAsJsonObject()));
					}
				}
			}
			return out;
		}

		/** 4.0 {@code BlockbenchOutline.add(gt, previous, id)} 逐字复刻 */
		GT add(GT gt, Outline previous, String id) {
			final JsonArray useOrigin;
			final JsonArray useRotation;
			if (previous == null) {
				// 4.0：previous==null ⇒ origin 用绝对值，rotation 强制 (0,0,0)
				useOrigin = origin;
				useRotation = new JsonArray();
			} else {
				final JsonArray diff = new JsonArray();
				diff.add(d(origin, 0) - d(previous.origin, 0));
				diff.add(d(origin, 1) - d(previous.origin, 1));
				diff.add(d(origin, 2) - d(previous.origin, 2));
				useOrigin = diff;
				useRotation = rotation;
			}
			return new GT(gt, useOrigin, useRotation);
		}
	}

	// ==================================================================
	// 结果记录
	// ==================================================================

	static final class UpstreamElement {

		String uuid;
		String name;
		String topGroup;
		List<String> path;
		String cubeName;
		float cx, cy, cz;
		/** Blockbench 绝对语义下的正确位置（X/Y 取负、Y 偏移 modelYOffset 格） */
		float theoryX, theoryY, theoryZ;
		String gtChain;
	}

	static final class UpstreamResult {

		ModelPart bakedRoot;
		final Map<String, Ext> nameToPart = new LinkedHashMap<>();
		final List<UpstreamElement> elements = new ArrayList<>();
		final Map<String, GT> topGroupChain = new LinkedHashMap<>();
		int bakeWarnings = 0;
	}

	// ==================================================================
	// main
	// ==================================================================

	public static void main(String[] args) throws Exception {
		miniProbe();
		final Path path = Path.of(args.length > 0 ? args[0] : "C:/tmp/eridu/eridudanguei.bbmodel");
		final float modelYOffset = 1.0F;
		final String json = Files.readString(path, StandardCharsets.UTF_8);

		final UpstreamResult up = buildUpstream(json, modelYOffset);
		final mtrnext.client.render.BbModelTree mine =
				mtrnext.client.render.BbModelTree.parseForTest(json, modelYOffset);

		System.out.println("=== 模型 " + path.getFileName() + " ===");
		System.out.println("4.0 侧：顶层组 " + up.nameToPart.size() + " / 元素 " + up.elements.size());
		System.out.println("mtr-next 侧：顶层组 " + mine.groupNames.size() + " / 元素 " + mine.elements.size());

		// 顶层组的 pivot 对照
		System.out.println();
		System.out.println("=== 顶层组节点 pivot（4.0 vs mtr-next）===");
		int groupDiffs = 0;
		for (final String gn : mine.groupNames) {
			final Ext ext = up.nameToPart.get(gn);
			final ModelPart mp = mine.groupParts.get(gn);
			if (ext == null || mp == null) {
				System.out.println(String.format("  %-18s 4.0=%s  mtr-next=%s", gn,
						ext == null ? "缺失" : "有", mp == null ? "缺失" : "有"));
				continue;
			}
			final float dx = ext.modelPart.x - mp.x;
			final float dy = ext.modelPart.y - mp.y;
			final float dz = ext.modelPart.z - mp.z;
			System.out.println(String.format("  %-18s 4.0=(%.3f,%.3f,%.3f) mtr-next=(%.3f,%.3f,%.3f) Δ=(%.3f,%.3f,%.3f)",
					gn, ext.modelPart.x, ext.modelPart.y, ext.modelPart.z, mp.x, mp.y, mp.z, dx, dy, dz));
			if (Math.abs(dx) > 0.001F || Math.abs(dy) > 0.001F || Math.abs(dz) > 0.001F) {
				groupDiffs++;
			}
		}
		System.out.println("  顶层组 pivot 不一致数 = " + groupDiffs);

		// 逐元素 cube 中心对照
		final Map<String, mtrnext.client.render.BbModelTree.Element> mineByUuid = new HashMap<>();
		for (final mtrnext.client.render.BbModelTree.Element e : mine.elements) {
			mineByUuid.put(e.uuid, e);
		}

		System.out.println();
		System.out.println("=== 逐元素 cube 中心（模型空间，单位：格）===");
		int matched = 0;
		int bad = 0;
		float worst = 0;
		String worstLabel = "";
		float worstUp = 0, worstMine = 0;
		String worstUpLabel = "", worstMineLabel = "";
		for (final UpstreamElement ue : up.elements) {
			final mtrnext.client.render.BbModelTree.Element me = mineByUuid.get(ue.uuid);
			if (me == null) {
				continue;
			}
			matched++;
			final float[] uw = upstreamWorld(up.bakedRoot, ue.cubeName, ue.cx, ue.cy, ue.cz);
			final float[] mw = mineWorld(mine, me);
			final float dx = mw[0] - uw[0];
			final float dy = mw[1] - uw[1];
			final float dz = mw[2] - uw[2];
			final float mag = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
			if (mag > worst) {
				worst = mag;
				worstLabel = ue.topGroup + "/" + ue.name + "(" + ue.uuid + ")";
			}
			if (mag > 0.05F) {
				bad++;
			}
			final float upTheory = Math.max(Math.abs(uw[0] - ue.theoryX),
					Math.max(Math.abs(uw[1] - ue.theoryY), Math.abs(uw[2] - ue.theoryZ)));
			final float mineTheory = Math.max(Math.abs(mw[0] - ue.theoryX),
					Math.max(Math.abs(mw[1] - ue.theoryY), Math.abs(mw[2] - ue.theoryZ)));
			if (upTheory > worstUp) { worstUp = upTheory; worstUpLabel = ue.topGroup + "/" + ue.name + "(" + ue.uuid.substring(0, Math.min(8, ue.uuid.length())) + ")"; }
			if (mineTheory > worstMine) { worstMine = mineTheory; worstMineLabel = ue.topGroup + "/" + ue.name + "(" + ue.uuid.substring(0, Math.min(8, ue.uuid.length())) + ")"; }
		}
		System.out.println(String.format("  可配对数=%d  两棵树的差异>0.05 格 的元素数=%d  4.0-vs-mtrnext 最大差=%.3f 格（%s）",
				matched, bad, worst, worstLabel));
		System.out.println(String.format("  ★ 与 Blockbench 理论值比对：4.0 最大偏差=%.3f 格（%s）  mtr-next 最大偏差=%.3f 格（%s）",
				worstUp, worstUpLabel, worstMine, worstMineLabel));

		// 重点组：hand / hand_n 的细节
		for (final String focus : new String[]{"hand", "hand_n", "men2", "door"}) {
			printFocus(up, mine, focus);
		}
	}

	/**
	 * 最小模型探针：直接看 4.0 复刻建出的树长什么样（每节到底产生几个节点、pivot 落几层）。
	 */
	private static void miniProbe() {
		System.out.println("=== 最小探针：4.0 复刻的树结构 ===");
		final String json = """
				{ "resolution": { "width": 64, "height": 64 },
				  "elements": [ { "uuid": "e1", "name": "cube", "from": [0, 0, 0], "to": [16, 16, 16],
				                  "uv_offset": [0, 0], "origin": [16, 0, 0] } ],
				  "outliner": [ { "name": "grp", "origin": [0, 0, 0], "children": ["e1"] } ] }
				""";
		elementExts.clear();
		final UpstreamResult up = buildUpstream(json, 1.0F);
		System.out.println("4.0 树：");
		dumpTree(up.bakedRoot, 0, 1.0F);
		final mtrnext.client.render.BbModelTree mine = mtrnext.client.render.BbModelTree.parseForTest(json, 1.0F);
		System.out.println("mtr-next 树：");
		dumpTree(mine.root, 0, 1.0F);
		System.out.println();
	}

	private static void dumpTree(ModelPart part, int depth, float tex) {
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < depth; i++) {
			sb.append("  ");
		}
		// 名字：ModelPart 没存名字，只能靠 children 的键反推；这里打印 pivot/rot/cube 数
		sb.append(String.format("pivot=(%.2f,%.2f,%.2f) rot=(%.2f,%.2f,%.2f) children=%d",
				part.x, part.y, part.z, part.xRot, part.yRot, part.zRot, childrenOf(part).size()));
		System.out.println(sb);
		for (final ModelPart child : childrenOf(part).values()) {
			dumpTree(child, depth + 1, tex);
		}
	}

	private static void printFocus(UpstreamResult up, mtrnext.client.render.BbModelTree mine, String group) {		final GT gt = up.topGroupChain.get(group);
		final Ext ext = up.nameToPart.get(group);
		System.out.println();
		System.out.println("=== 焦点组 " + group + " ===");
		if (gt == null || ext == null) {
			System.out.println("  4.0 侧无此组");
			return;
		}
		System.out.println("  4.0 顶层组链: " + gt.describe());
		System.out.println(String.format("  4.0 顶层组节点 pivot=(%.3f,%.3f,%.3f) rot=(%.3f,%.3f,%.3f) 路径=%s",
				ext.modelPart.x, ext.modelPart.y, ext.modelPart.z, ext.modelPart.xRot, ext.modelPart.yRot, ext.modelPart.zRot, ext.nameTree));
		final ModelPart mp = mine.groupParts.get(group);
		if (mp != null) {
			System.out.println(String.format("  mtr-next 锚点 pivot=(%.3f,%.3f,%.3f) rot=(%.3f,%.3f,%.3f)",
					mp.x, mp.y, mp.z, mp.xRot, mp.yRot, mp.zRot));
		}

		// 该组前 6 个元素
		int n = 0;
		for (final UpstreamElement ue : up.elements) {
			if (!group.equals(ue.topGroup) || n >= 6) {
				continue;
			}
			n++;
			final mtrnext.client.render.BbModelTree.Element me = findByUuid(mine, ue.uuid);
			final float[] uw = upstreamWorld(up.bakedRoot, ue.cubeName, ue.cx, ue.cy, ue.cz);
			final float[] mw = me == null ? new float[]{Float.NaN, Float.NaN, Float.NaN} : mineWorld(mine, me);
			System.out.println(String.format("    %s/%s 4.0=(%.2f,%.2f,%.2f) mine=(%.2f,%.2f,%.2f)",
					ue.name, ue.uuid.substring(0, Math.min(8, ue.uuid.length())), uw[0], uw[1], uw[2], mw[0], mw[1], mw[2]));
			System.out.println("      4.0 链: " + ue.gtChain);
			System.out.println("      4.0 路径: " + ue.path);
			if (me != null) {
				final StringBuilder sb = new StringBuilder();
				for (final ModelPart p : me.chain) {
					sb.append(String.format("[rel=(%.3f,%.3f,%.3f) rot=(%.2f,%.2f,%.2f)]", p.x, p.y, p.z, p.xRot, p.yRot, p.zRot));
				}
				System.out.println("      mine 链: " + sb);
			}
		}
	}

	private static mtrnext.client.render.BbModelTree.Element findByUuid(
			mtrnext.client.render.BbModelTree tree, String uuid) {
		for (final mtrnext.client.render.BbModelTree.Element e : tree.elements) {
			if (uuid.equals(e.uuid)) {
				return e;
			}
		}
		return null;
	}

	private static float[] mineWorld(mtrnext.client.render.BbModelTree tree,
			mtrnext.client.render.BbModelTree.Element e) {
		final PoseStack ps = new PoseStack();
		final List<ModelPart> rootToAnchor = tree.groupPaths.get(e.groupName);
		if (rootToAnchor != null) {
			for (final ModelPart p : rootToAnchor) {
				p.translateAndRotate(ps);
			}
		}
		for (final ModelPart p : e.chain) {
			p.translateAndRotate(ps);
		}
		ps.translate(e.centerX, e.centerY, e.centerZ);
		final Vector3f v = ps.last().pose().getTranslation(new Vector3f());
		return new float[]{v.x, v.y, v.z};
	}

	private static float[] upstreamWorld(ModelPart root, String cubeName, float cx, float cy, float cz) {
		final PoseStack ps = new PoseStack();
		if (!locate(root, cubeName, ps)) {
			return new float[]{Float.NaN, Float.NaN, Float.NaN};
		}
		ps.translate(cx, cy, cz);
		final Vector3f v = ps.last().pose().getTranslation(new Vector3f());
		return new float[]{v.x, v.y, v.z};
	}

	@SuppressWarnings("unchecked")
	private static Map<String, ModelPart> childrenOf(ModelPart part) {
		try {
			return (Map<String, ModelPart>) CHILDREN.get(part);
		} catch (final Throwable t) {
			return Map.of();
		}
	}

	private static boolean locate(ModelPart part, String target, PoseStack ps) {
		for (final Map.Entry<String, ModelPart> e : childrenOf(part).entrySet()) {
			ps.pushPose();
			e.getValue().translateAndRotate(ps);
			if (target.equals(e.getKey())) {
				return true;
			}
			if (locate(e.getValue(), target, ps)) {
				return true;
			}
			ps.popPose();
		}
		return false;
	}

	private static final java.lang.reflect.Field CHILDREN;

	static {
		try {
			CHILDREN = ModelPart.class.getDeclaredField("children");
			CHILDREN.setAccessible(true);
		} catch (final Throwable t) {
			throw new ExceptionInInitializerError(t);
		}
	}

	// ==================================================================
	// 构建 4.0 侧
	// ==================================================================

	private static UpstreamResult buildUpstream(String json, float modelYOffset) {
		final JsonObject model = JsonParser.parseString(json).getAsJsonObject();
		final JsonObject res = model.getAsJsonObject("resolution");
		final int texW = res == null ? 64 : (int) Math.round(d(res, "width"));
		final int texH = res == null ? 64 : (int) Math.round(d(res, "height"));

		final Map<String, JsonObject> uuidToElement = new HashMap<>();
		for (final JsonElement e : model.getAsJsonArray("elements")) {
			final JsonObject o = e.getAsJsonObject();
			if (o.has("uuid")) {
				uuidToElement.put(o.get("uuid").getAsString(), o);
			}
		}

		final MeshDefinition mesh = new MeshDefinition();
		final UpstreamResult result = new UpstreamResult();

		for (final JsonElement ge : resolveTopGroups(model)) {
			final Outline top = Outline.read(ge.getAsJsonObject());
			final Ext[] holder = new Ext[1];
			// 4.0：ObjectHolder<ModelPartExtension> parentModelPart = new ObjectHolder<>(this::createModelPart)
			result.topGroupChain.put(top.name, top.add(new GT(), null, "check"));
			iterateChildren(top, null, new GT(), uuidToElement, holder, mesh.getRoot(), modelYOffset, result);
			if (holder[0] != null) {
				result.nameToPart.put(top.name, holder[0]);
			}
		}

		final ModelPart baked = LayerDefinition.create(mesh, texW, texH).bakeRoot();
		result.bakedRoot = baked;

		// 解析每个元素的 ModelPart（4.0 ModelPartExtension.setModelPart 的路径解析）
		for (final Ext ext : elementExts) {
			try {
				ext.setModelPart(baked);
			} catch (final Throwable t) {
				result.bakeWarnings++;
			}
		}
		// 解析顶层组节点
		for (final Ext ext : result.nameToPart.values()) {
			try {
				ext.setModelPart(baked);
			} catch (final Throwable t) {
				result.bakeWarnings++;
			}
		}
		elementExts.clear();
		return result;
	}

	/**
	 * Blockbench 5.0 格式：outliner 只有 uuid，组元数据在顶层 groups 数组。
	 * 与 BbModelTree.topLevelGroups 使用同一套迁移，保证对拍只比较 pivot 语义。
	 */
	private static List<JsonElement> resolveTopGroups(JsonObject model) {
		final Map<String, JsonObject> byUuid = new HashMap<>();
		if (model.has("groups") && model.get("groups").isJsonArray()) {
			for (final JsonElement el : model.getAsJsonArray("groups")) {
				if (el.isJsonObject() && el.getAsJsonObject().has("uuid")) {
					byUuid.put(el.getAsJsonObject().get("uuid").getAsString(), el.getAsJsonObject());
				}
			}
		}
		final List<JsonElement> out = new ArrayList<>();
		if (model.has("outliner") && model.get("outliner").isJsonArray()) {
			for (final JsonElement el : model.getAsJsonArray("outliner")) {
				out.add(el.isJsonObject() ? resolveNode(el.getAsJsonObject(), byUuid) : el);
			}
		}
		return out;
	}

	private static JsonObject resolveNode(JsonObject node, Map<String, JsonObject> byUuid) {
		final JsonObject def = node.has("uuid") ? byUuid.get(node.get("uuid").getAsString()) : null;
		final JsonObject merged = def == null ? node : def.deepCopy();
		if (node.has("children") && node.get("children").isJsonArray()) {
			final JsonArray kids = new JsonArray();
			for (final JsonElement c : node.getAsJsonArray("children")) {
				kids.add(c.isJsonObject() ? resolveNode(c.getAsJsonObject(), byUuid) : c);
			}
			merged.add("children", kids);
		}
		return merged;
	}

	private static final List<Ext> elementExts = new ArrayList<>();

	/**
	 * 4.0 {@code DynamicVehicleModel.iterateChildren} 逐字复刻。
	 * <p>注意 L151：递归子组时传的是 <b>groupTransformations（父的入参）</b>，
	 * 不是 newGroupTransformations。
	 */
	private static void iterateChildren(Outline outline, Outline previous, GT gt,
			Map<String, JsonObject> uuidToElement, Ext[] holder, PartDefinition meshRoot,
			float modelYOffset, UpstreamResult result) {
		final GT newGt = outline.add(gt, previous, "check");
		for (final String uuid : outline.childrenUuid) {
			final JsonObject element = uuidToElement.remove(uuid);
			if (element != null) {
				if (holder[0] == null) {
					holder[0] = new Ext(meshRoot);
				}
				final Ext parentForElement = holder[0].addChild();
				final UpstreamElement rec = setModelPart(element, parentForElement, newGt, modelYOffset, outline.name);
				if (rec != null) {
					result.elements.add(rec);
				}
			}
		}
		for (final Outline child : outline.children) {
			iterateChildren(child, outline, gt, uuidToElement, holder, meshRoot, modelYOffset, result);
		}
	}

	/** 4.0 {@code BlockbenchElement.setModelPart} 逐字复刻（ModelPart 部分） */
	private static UpstreamElement setModelPart(JsonObject element, Ext parentModelPart, GT gt,
			float modelYOffset, String topGroup) {
		final JsonArray origin = arr(element, "origin");
		final JsonArray from = arr(element, "from");
		final JsonArray to = arr(element, "to");
		final JsonArray rotation = arr(element, "rotation");

		final float originX = -(float) d(origin, 0);
		final float originY = -(float) d(origin, 1) - modelYOffset * 16.0F;
		final float originZ = (float) d(origin, 2);
		final float rotationX = (float) Math.toRadians(-d(rotation, 0));
		final float rotationY = (float) Math.toRadians(-d(rotation, 1));
		final float rotationZ = (float) Math.toRadians(d(rotation, 2));

		final GT newGt = new GT(gt, origin, rotation);
		final Ext modelPart = newGt.create(parentModelPart, modelYOffset);
		modelPart.setTextureUVOffset((int) d(arr(element, "uv_offset"), 0), (int) d(arr(element, "uv_offset"), 1));

		final float x = -(float) d(to, 0) - originX;
		final float y = -(float) d(to, 1) - originY - modelYOffset * 16.0F;
		final float z = (float) d(from, 2) - originZ;
		final int sizeX = (int) Math.round(d(to, 0) - d(from, 0));
		final int sizeY = (int) Math.round(d(to, 1) - d(from, 1));
		final int sizeZ = (int) Math.round(d(to, 2) - d(from, 2));
		final float inflate = (float) d(element, "inflate");
		final boolean shade = !element.has("shade") || element.get("shade").getAsBoolean();
		final boolean mirrorUv = element.has("mirror_uv") && element.get("mirror_uv").getAsBoolean();
		modelPart.addCuboid(x, y, z, sizeX, sizeY, sizeZ, inflate, !shade || mirrorUv);

		final UpstreamElement rec = new UpstreamElement();
		rec.uuid = element.has("uuid") ? element.get("uuid").getAsString() : "";
		rec.name = element.has("name") ? element.get("name").getAsString() : "";
		rec.topGroup = topGroup;
		rec.path = new ArrayList<>(modelPart.nameTree);
		rec.cx = (x + sizeX / 2.0F) / 16.0F;
		rec.cy = (y + sizeY / 2.0F) / 16.0F;
		rec.cz = (z + sizeZ / 2.0F) / 16.0F;
		rec.gtChain = newGt.describe();
		rec.theoryX = (float) (-((d(from, 0) + d(to, 0)) / 2.0) / 16.0);
		rec.theoryY = (float) (-((d(from, 1) + d(to, 1)) / 2.0) / 16.0 - modelYOffset);
		rec.theoryZ = (float) (((d(from, 2) + d(to, 2)) / 2.0) / 16.0);
		rec.cubeName = modelPart.lastCubeName;
		elementExts.add(modelPart);
		return rec;
	}

	// ==================================================================
	// helpers
	// ==================================================================

	private static JsonArray arr(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
	}

	private static double d(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : 0.0;
	}

	private static double d(JsonArray a, int i) {
		return a != null && a.size() > i && a.get(i).isJsonPrimitive() ? a.get(i).getAsDouble() : 0.0;
	}
}
