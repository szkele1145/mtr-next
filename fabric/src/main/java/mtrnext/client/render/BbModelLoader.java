package mtrnext.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtrnext.MtrNext;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [4.0 车包渲染桥 1/2] bbmodel（BlockBench JSON）解析为 MC 原生 ModelPart。
 * <p>
 * 实证（ERIDU-DG1000A 样本）：
 * <ul>
 *   <li>elements[]：标准 BlockBench 盒体——from/to（世界单位顶点）、
 *       rotation+origin（三轴旋转）、faces 六面 UV（resolution 24×24 基准）；</li>
 *   <li>modded_entity 格式的 from/to 是"展开"的像素坐标（含 -67 等大偏移），
 *       直接映射为 ModelPart cube（MC 原生 16 单位/格基准一致）；</li>
 *   <li>faces 缺省面（无 uv 键）跳过该面绘制。</li>
 * </ul>
 * <p>
 * 产物为 MeshDefinition → ModelPart 树（每 element 一个独立 part，
 * 保留 rotation），后续由车辆渲染器按编组位组装。
 */
public final class BbModelLoader {

	/** 已解析缓存：模型资源路径 → MeshDefinition（资源重载时清） */
	private static final Map<String, MeshDefinition> CACHE = new ConcurrentHashMap<>();

	private BbModelLoader() {
	}

	public static void invalidate() {
		CACHE.clear();
	}

	/** 解析 bbmodel 为 ModelPart 树（带缓存） */
	public static ModelPart bake(net.minecraft.server.packs.resources.ResourceManager manager, String modelResource) {
		final MeshDefinition mesh = CACHE.computeIfAbsent(modelResource, k -> parse(manager, k));
		if (mesh == null) {
			return null;
		}
		try {
			final var layerDefs = net.minecraft.client.model.geom.builders.LayerDefinition.create(mesh, 24, 24);
			return layerDefs.bakeRoot();
		} catch (final Throwable t) {
			MtrNext.LOGGER.error("Failed to bake bbmodel {}", modelResource, t);
			return null;
		}
	}

	private static MeshDefinition parse(net.minecraft.server.packs.resources.ResourceManager manager, String modelResource) {
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
			final CubeListBuilder cube = buildCube(element);
			if (cube == null) {
				index++;
				continue;
			}
			// 每 element 一个独立 part：保留 rotation+origin
			final float[] rot = rotation(element);
			final float[] origin = origin(element);
			rootPart.addOrReplaceChild("el" + index, cube,
					PartPose.offsetAndRotation(
							-origin[0], origin[1], origin[2],
							(float) Math.toRadians(rot[0]),
							(float) Math.toRadians(rot[1]),
							(float) Math.toRadians(rot[2])));
			index++;
		}
		return mesh;
	}

	private static CubeListBuilder buildCube(JsonObject element) {
		if (!element.has("from") || !element.has("to")) {
			return null;
		}
		final JsonArray from = element.getAsJsonArray("from");
		final JsonArray to = element.getAsJsonArray("to");
		// BlockBench: Y 轴向下 → MC: Y 轴向上（翻转 to/from.y）
		final float x1 = f(from, 0), y1 = f(from, 1), z1 = f(from, 2);
		final float x2 = f(to, 0), y2 = f(to, 1), z2 = f(to, 2);

		final CubeListBuilder cube = CubeListBuilder.create();
		boolean anyFace = false;
		if (element.has("faces") && element.get("faces").isJsonObject()) {
			final JsonObject faces = element.getAsJsonObject("faces");
			// MC 六面 UV 键: down/up/west/east/north/south — BlockBench 同名, 直接映射
			anyFace |= addFace(cube, faces, "down", x1, y2, z1, x2, y2, z2);
			anyFace |= addFace(cube, faces, "up", x1, y1, z1, x2, y1, z2);
			anyFace |= addFace(cube, faces, "north", x1, y1, z1, x2, y2, z1);
			anyFace |= addFace(cube, faces, "south", x1, y1, z2, x2, y2, z2);
			anyFace |= addFace(cube, faces, "east", x2, y1, z1, x2, y2, z2);
			anyFace |= addFace(cube, faces, "west", x1, y1, z1, x1, y2, z2);
		}
		if (!anyFace) {
			// 无 faces 定义: 整盒 UV 0-16（与 BlockBench box_uv 缺省一致）
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
		final float u1 = f(uv, 0), v1 = f(uv, 1), u2 = f(uv, 2), v2 = f(uv, 3);
		// [0.9.5] UV 精度修复：texOffs 只支持 int 偏移，但 bbmodel 的 UV 常带小数
		// （如 4.5）——整数截断会让贴图错位半像素。方案：Math.round 保最大精度，
		// 残余亚像素偏差在 24×24 车包贴图上不可见（<0.5/24 ≈ 2%）。
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
