package mtrnext.client.render;

import mtrnext.MtrNext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;

/**
 * [0.35] bbmodel 模型树的资源加载与缓存。
 * <p>
 * 缓存键必须是 "bbmodel + properties" 的组合：同一个 bbmodel 可被不同 properties
 * 引用，而 {@code modelYOffset} 来自 properties（上游
 * {@code DynamicVehicleModel} 把 {@code modelProperties.getModelYOffset()} 传进
 * 每个元素的 {@code setModelPart}），单键缓存会让不同车包串偏移。
 */
public final class BbModelTreeCache {

	private BbModelTreeCache() {
	}

	public static void invalidate() {
		BbModelTree.invalidate();
		Properties4xLoader.invalidate();
		PositionDefinitions4xLoader.invalidate();
		// [0.38.0] 渲染计划快照同样跟随资源重载失效（其内容 = 上面两个数据层的产品）
		LayerRenderCache.invalidate();
	}

	/**
	 * 取（或构建）一个模型层对应的原生模型树。
	 * <p>
	 * 缓存由 {@link BbModelTree} 自己持有（键 = modelResource + "|" + propertiesResource），
	 * 此处不再另建一份表，避免同一棵树被缓存两次。
	 * <p>
	 * ★ [0.36.9] 新增<b>预计算键</b>重载：{@code ModelLayer} 构造时已算好键
	 * （见 {@code VehicleResource.ModelLayer.treeCacheKey}），
	 * 避免渲染热路径每帧做一次字符串拼接。
	 *
	 * @return 资源缺失时返回 null（调用方跳过该层）
	 */
	static BbModelTree tree(ResourceManager manager, String modelResource, String propertiesResource) {
		return tree(manager, modelResource, propertiesResource,
				mtrnext.pack.VehicleResource.ModelLayer.treeCacheKey(modelResource, propertiesResource));
	}

	/**
	 * [0.36.9] 用<b>预计算键</b>取模型树。
	 *
	 * @param cacheKey {@code ModelLayer.treeCacheKey} 的结果（与本方法内部算法一致）
	 */
	static BbModelTree tree(ResourceManager manager, String modelResource, String propertiesResource,
			String cacheKey) {
		if (modelResource == null || modelResource.isEmpty()) {
			return null;
		}
		final BbModelTree cached = BbModelTree.cached(cacheKey);
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
				return BbModelTree.of(cacheKey, stream, modelYOffset);
			}
		} catch (final Throwable t) {
			MtrNext.LOGGER.debug("[bbmodel] 载入失败 {}", modelResource, t);
			return null;
		}
	}

	/**
	 * 模型层贴图 → {@code ResourceLocation}（缺省回退白色混凝土，
	 * 避免 {@code entityCutoutNoCull} 拿到空贴图）。
	 * <p>
	 * ★ [0.36.9] 新增<b>预解析</b>重载：{@code ModelLayer} 构造时已解析好
	 * {@code ResourceLocation}（见 {@code ModelLayer.textureLocation}），
	 * 避免渲染热路径每帧新建对象。{@code location} 为 null（未给贴图或串畸形）
	 * 时回退白色混凝土。
	 */
	static ResourceLocation texture(net.minecraft.resources.ResourceLocation location) {
		return location == null ? WHITE_CONCRETE : location;
	}

	/** 模型层贴图 → ResourceLocation（缺省回退白色混凝土，避免 entityCutoutNoCull 拿到空贴图） */
	static ResourceLocation texture(String resource) {
		if (resource == null || resource.isEmpty()) {
			return WHITE_CONCRETE;
		}
		final ResourceLocation id = resolve(resource);
		return id == null ? WHITE_CONCRETE : id;
	}

	/** [0.36.9] 缺省贴图（常量，避免每次 new） */
	private static final ResourceLocation WHITE_CONCRETE =
			new ResourceLocation("minecraft", "textures/block/white_concrete.png");

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
