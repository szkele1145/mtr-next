package mtrnext.client.render;

import net.minecraft.server.packs.resources.ResourceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [0.38.0] 模型层的<b>渲染计划</b>预解析缓存。
 * <p>
 * 背景：{@code renderLayer} 每帧每模型层要做两件事——
 * <ol>
 *   <li>{@code Properties4xLoader.load} + {@code PositionDefinitions4xLoader.load}
 *       两次 {@code ConcurrentHashMap.get}；</li>
 *   <li>0.37.0 把位定义查表提到了部件循环外，但**每个 NORMAL 部件每帧仍要
 *       {@code new PositionGroup[defNames.size()]}** 一次（0.37.0 遗留的每帧分配）。</li>
 * </ol>
 * 一列车多层、每层每帧 ⇒ 每秒数千次数组分配。
 * <p>
 * 本类把「按层静态」的部分一次性解析成 {@link PlanPart}：
 * <ul>
 *   <li>只保留 {@code type=NORMAL} 且位定义名非空的部件（其余在原循环里本来就被
 *       {@code continue} 跳过，<b>渲染输出逐字不变</b>）；</li>
 *   <li>每个部件的位定义组数组<b>预解析</b>（含 null 过滤——原循环对 null group
 *       同样 {@code continue}，滤掉后渲染输出不变）；</li>
 *   <li>部件顺序、名字顺序、位定义名顺序全部保持原序（渲染顺序契约）。</li>
 * </ul>
 * <p>
 * <b>为什么是独立缓存而不是塞进 {@code VehicleResource.ModelLayer}</b>：
 * 车包对象（ModelLayer）可能跨资源重载存活，而 positionDefinitions 的内容跟随
 * 资源重载变化（{@code /reload} 后 group 的坐标可能变）——把快照塞进 ModelLayer
 * 会拿到过期引用。本类与 Properties4xLoader / PositionDefinitions4xLoader 一样
 * 在 {@code BbModelTreeCache.invalidate()}（资源重载钩子）里整体失效。
 * <p>
 * 缓存键 = {@code properties + "|" + positionDefinitions}：两者任一变化都会换键，
 * 不会出现「半新半旧」。
 */
public final class LayerRenderCache {

	private static final Map<String, LayerRenderCache> CACHE = new ConcurrentHashMap<>();

	/** 只含会产出几何的部件（type=NORMAL 且位定义名非空且至少解析到一个组），原序 */
	public final List<PlanPart> parts;

	private LayerRenderCache(List<PlanPart> parts) {
		this.parts = parts;
	}

	public static void invalidate() {
		CACHE.clear();
	}

	/**
	 * 取一层的渲染计划（带缓存）。
	 *
	 * @param propertiesResource properties_*.json 资源路径（可空 = 无部件层）
	 * @param positionDefinitionsResource definition_*.json 资源路径（可空）
	 */
	public static LayerRenderCache load(ResourceManager manager, String propertiesResource,
			String positionDefinitionsResource) {
		final String key = (propertiesResource == null ? "" : propertiesResource) + "|"
				+ (positionDefinitionsResource == null ? "" : positionDefinitionsResource);
		return CACHE.computeIfAbsent(key, k -> build(
				Properties4xLoader.load(manager, propertiesResource).parts,
				PositionDefinitions4xLoader.load(manager, positionDefinitionsResource)));
	}

	/**
	 * 纯数据构造（离线自测入口，不依赖 ResourceManager）。
	 * <p>
	 * 过滤规则与原 {@code renderLayer} 循环里的 {@code continue} **逐条对应**：
	 * <ol>
	 *   <li>{@code !"NORMAL".equals(info.type)} → 原循环 continue；</li>
	 *   <li>{@code info.positionDefinitionNames.isEmpty()} → 原循环 continue；</li>
	 *   <li>位定义名在 positions 里查不到（group == null）→ 原循环体内 continue。</li>
	 * </ol>
	 * 第 3 条在原实现里发生在 name 循环<b>之内</b>，但 name 是否存在只影响
	 * 「有 group 但没锚点」的部件，与「group 为 null」无关——null group 对任何
	 * name 都画不出东西 ⇒ 预过滤等价。
	 */
	public static LayerRenderCache build(List<Properties4xLoader.PartInfo> parts,
			Map<String, PositionDefinitions4xLoader.PositionGroup> positions) {
		final List<PlanPart> out = new ArrayList<>();
		if (parts == null || positions == null) {
			return new LayerRenderCache(Collections.emptyList());
		}
		for (final Properties4xLoader.PartInfo info : parts) {
			if (!"NORMAL".equals(info.type)) {
				continue; // 原循环：DISPLAY/FLOOR/DOORWAY 不参与常规几何渲染
			}
			if (info.positionDefinitionNames.isEmpty()) {
				continue; // 原循环：无位定义 = 不产出几何
			}
			final List<PositionDefinitions4xLoader.PositionGroup> groups = new ArrayList<>();
			for (final String defName : info.positionDefinitionNames) {
				final PositionDefinitions4xLoader.PositionGroup group = positions.get(defName);
				if (group != null) {
					groups.add(group); // 原循环体：null → continue
				}
			}
			if (groups.isEmpty()) {
				continue; // 所有位定义都查不到：原循环对每个 name 都空转，等价于不画
			}
			out.add(new PlanPart(info, groups.toArray(new PositionDefinitions4xLoader.PositionGroup[0])));
		}
		return new LayerRenderCache(Collections.unmodifiableList(out));
	}

	/** 一个会产出几何的部件 + 它预解析好的位定义组（已滤 null，原序） */
	public static final class PlanPart {
		public final Properties4xLoader.PartInfo info;
		public final PositionDefinitions4xLoader.PositionGroup[] groups;

		PlanPart(Properties4xLoader.PartInfo info, PositionDefinitions4xLoader.PositionGroup[] groups) {
			this.info = info;
			this.groups = groups;
		}
	}
}
