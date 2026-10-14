package mtrnext.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtrnext.MtrNext;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [4.0 车包渲染桥 1/2] bbmodel（BlockBench JSON）解析为 MC 原生 ModelPart。
 * <p>
 * 实证（ERIDU-DG1000A 样本）：
 * <ul>
 *   <li>elements[]：标准 BlockBench 盒体——from/to（世界单位顶点）、
 *       rotation+origin（三轴旋转）、faces 六面 UV（resolution 24×24 基准）；</li>
 *   <li>faces 缺省面（无 uv 键）跳过该面绘制。</li>
 * </ul>
 * <p>
 * [0.10] 门动画桥：element name 含 "door" 的部件以 door_ 前缀命名，
 * {@link #animateDoors} 按门开度平移它们（Z 向 0.75 格行程）。
 */
public final class BbModelLoader {

	private static final Map<String, MeshDefinition> CACHE = new ConcurrentHashMap<>();

	/** [0.10] 门动画行程（格） */
	private static final float DOOR_TRAVEL = 0.75F;

	private BbModelLoader() {
	}

	public static void invalidate() {
		CACHE.clear();
	}

	public static ModelPart bake(ResourceManager manager, String modelResource) {
		final MeshDefinition mesh = CACHE.computeIfAbsent(modelResource, k -> parse(manager, k));
		if (mesh == null) {
			return null;
		}
		try {
			final LayerDefinition layerDefs = LayerDefinition.create(mesh, 24, 24);
			return layerDefs.bakeRoot();
		} catch (final Throwable t) {
			MtrNext.LOGGER.error("Failed to bake bbmodel {}", modelResource, t);
			return null;
		}
	}

	private static MeshDefinition parse(ResourceManager manager, String modelResource) {
		try {
			final ResourceLocation id = new ResourceLocation(modelResource);
			if (!manager.getResource(id).isPresent()) {
				MtrNext.LOGGER.warn("bbmodel not found: {}", modelResource);
				return null;
			}
			try (InputStreamReader r = new InputStreamReader(
					manager.getResource(id).get().open(), StandardCharsets.UTF_8)) {
				final JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
				return buildMesh(root);
			}
		} catch (final Throwable t) {
			MtrNext.LOGGER.error("Failed to parse bbmodel {}", modelResource, t);
			return null;
		}
	}

	private static MeshDefinition buildMesh(JsonObject root) {
		final MeshDefinition mesh = new MeshDefinition();
		final PartDefinition rootPart = mesh.getRoot();

		if (!root.has("elements") || !root.get("elements").isJsonArray()) {
			return mesh;
		}
		int index = 0;
		for (final JsonElement el : root.getAsJsonArray("elements")) {
			final JsonObject element = el.getAsJsonObject();
			final String name = str(element, "name", "el" + index);
			// [0.10] 门部件识别：name 含 "door"（不区分大小写）→ door_ 前缀
			final boolean isDoor = name.toLowerCase(java.util.Locale.ROOT).contains("door");
			final CubeListBuilder cube = buildCube(element);
			if (cube == null) {
				index++;
				continue;
			}
			final float[] rot = rotation(element);
			final float[] origin = origin(element);
			rootPart.addOrReplaceChild((isDoor ? "door_" : "el") + index, cube,
					PartPose.offsetAndRotation(
							-origin[0], origin[1], origin[2],
							(float) Math.toRadians(rot[0]),
							(float) Math.toRadians(rot[1]),
							(float) Math.toRadians(rot[2])));
			index++;
		}
		return mesh;
	}

	/**
	 * [0.10] 门开度动画：door_ 前缀的 part 沿 Z 平移（行程 0.75 格，向车内滑）。
	 * 1.20.1 ModelPart 的子部件经 getChild(name) 逐个取（children 字段私有）。
	 */
	public static void animateDoors(ModelPart baked, boolean doorLeftOpen, boolean doorRightOpen) {
		if (baked == null) {
			return;
		}
		final float target = (doorLeftOpen || doorRightOpen) ? 1.0F : 0.0F;
		// bbmodel 的 element 名是车包作者定的 — 门 part 以 door_ 前缀存在于我们的
		// bake 索引里; 逐 index 试探(数量少, 门部件通常 2-8 个)
		for (int i = 0; i < 256; i++) {
			final ModelPart part = baked.getChild("door_" + i);
			if (part == null) {
				continue;
			}
			part.z = -DOOR_TRAVEL * target;
		}
	}

	private static CubeListBuilder buildCube(JsonObject element) {
		if (!element.has("from") || !element.has("to")) {
			return null;
		}
		final JsonArray from = element.getAsJsonArray("from");
		final JsonArray to = element.getAsJsonArray("to");
		final float x1 = f(from, 0), y1 = f(from, 1), z1 = f(from, 2);
		final float x2 = f(to, 0), y2 = f(to, 1), z2 = f(to, 2);

		final CubeListBuilder cube = CubeListBuilder.create();
		boolean anyFace = false;
		if (element.has("faces") && element.get("faces").isJsonObject()) {
			final JsonObject faces = element.getAsJsonObject("faces");
			anyFace |= addFace(cube, faces, "down", x1, y2, z1, x2, y2, z2);
			anyFace |= addFace(cube, faces, "up", x1, y1, z1, x2, y1, z2);
			anyFace |= addFace(cube, faces, "north", x1, y1, z1, x2, y2, z1);
			anyFace |= addFace(cube, faces, "south", x1, y1, z2, x2, y2, z2);
			anyFace |= addFace(cube, faces, "east", x2, y1, z1, x2, y2, z2);
			anyFace |= addFace(cube, faces, "west", x1, y1, z1, x1, y2, z2);
		}
		if (!anyFace) {
			cube.addBox(x1, y1, z1, x2 - x1, y2 - y1, z2 - z1);
		}
		return cube;
	}

	private static boolean addFace(CubeListBuilder cube, JsonObject faces, String face,
			float x1, float y1, float z1, float x2, float y2, float z2) {
		if (!faces.has(face)) {
			return false;
		}
		final JsonObject f = faces.getAsJsonObject(face);
		if (!f.has("uv")) {
			return false;
		}
		final JsonArray uv = f.getAsJsonArray("uv");
		final float u1 = f(uv, 0), v1 = f(uv, 1);
		// [0.9.5] UV 精度：Math.round 保最大精度（残余亚像素偏差在 24×24 贴图 <2%）
		cube.texOffs(Math.round(u1), Math.round(v1)).addBox(x1, y1, z1, x2 - x1, y2 - y1, z2 - z1,
				net.minecraft.client.model.geom.builders.CubeDeformation.NONE);
		return true;
	}

	private static float[] rotation(JsonObject element) {
		if (!element.has("rotation")) {
			return new float[]{0, 0, 0};
		}
		final JsonArray r = element.getAsJsonArray("rotation");
		return new float[]{f(r, 0), f(r, 1), f(r, 2)};
	}

	private static float[] origin(JsonObject element) {
		if (!element.has("origin")) {
			return new float[]{0, 0, 0};
		}
		final JsonArray o = element.getAsJsonArray("origin");
		return new float[]{f(o, 0), f(o, 1), f(o, 2)};
	}

	private static float f(JsonArray a, int i) {
		return a.size() > i ? a.get(i).getAsFloat() : 0;
	}

	private static String str(JsonObject o, String key, String def) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
	}
}
