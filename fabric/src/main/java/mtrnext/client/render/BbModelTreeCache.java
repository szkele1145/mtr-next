package mtrnext.client.render;

import mtrnext.MtrNext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [0.35] bbmodel 模型树的资源加载与缓存。
 * <p>
 * 缓存键必须是 "bbmodel + properties" 的组合：同一个 bbmodel 可被不同 properties
 * 引用，而 {@code modelYOffset} 来自 properties（上游
 * {@code DynamicVehicleModel} 把 {@code modelProperties.getModelYOffset()} 传进
 * 每个元素的 {@code setModelPart}），单键缓存会让不同车包串偏移。
 */
public final class BbModelTreeCache {

	/** key = modelResource + "|" + propertiesResource */
	private static final Map<String, BbModelTree> TREE_CACHE = new ConcurrentHashMap<>();

	private BbModelTreeCache() {
	}

	public static void invalidate() {
		TREE_CACHE.clear();
		BbModelTree.invalidate();
		Properties4xLoader.invalidate();
		PositionDefinitions4xLoader.invalidate();
	}

	/**
	 * 取（或构建）一个模型层对应的原生模型树。
	 *
	 * @return 资源缺失时返回 null（调用方跳过该层）
	 */
	static BbModelTree tree(ResourceManager manager, String modelResource, String propertiesResource) {
		if (modelResource == null || modelResource.isEmpty()) {
			return null;
		}
		final String key = modelResource + "|" + (propertiesResource == null ? "" : propertiesResource);
		final BbModelTree cached = TREE_CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		try {
			final ResourceLocation id = resolve(modelResource);
			if (id == null || manager.getResource(id).isEmpty()) {
				MtrNext.LOGGER.debug("[bbmodel] 模型资源缺失: {}", modelResource);
				return null;
			}
			final Properties4xLoader.GangwayProperties props =
					Properties4xLoader.load(manager, propertiesResource);
			final float modelYOffset = (float) props.modelYOffset;
			try (InputStream stream = manager.getResource(id).get().open()) {
				final BbModelTree built = BbModelTree.of(key, stream, modelYOffset);
				TREE_CACHE.put(key, built);
				return built;
			}
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("[bbmodel] 载入失败 {}", modelResource, t);
			return null;
		}
	}

	/** 模型层贴图 → ResourceLocation（缺省回退白色混凝土，避免 entityCutoutNoCull 拿到空贴图） */
	static ResourceLocation texture(String resource) {
		if (resource == null || resource.isEmpty()) {
			return new ResourceLocation("minecraft", "textures/block/white_concrete.png");
		}
		final ResourceLocation id = resolve(resource);
		return id == null ? new ResourceLocation("minecraft", "textures/block/white_concrete.png") : id;
	}

	/** 车包资源串 → ResourceLocation；畸形串返回 null（不抛异常） */
	private static ResourceLocation resolve(String resource) {
		try {
			final String[] parts = resource.split(":", 2);
			return parts.length == 2
					? new ResourceLocation(parts[0], parts[1])
					: new ResourceLocation("minecraft", parts[0]);
		} catch (final Throwable t) {
			return null;
		}
	}
}
