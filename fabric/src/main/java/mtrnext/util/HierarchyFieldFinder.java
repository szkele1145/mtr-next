package mtrnext.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [0.22] 类层级反射字段查找器——speed/doorValue 两个字段的同一模式提取。
 * <p>
 * BveTrainSound 的 findSpeedField / findDoorValueField 是完全同构的
 * （沿父类链找 getDeclaredField + setAccessible + 按 Class 缓存）——
 * 深夜代码审查发现后提取为本工具，消除重复且让后续字段（如
 * brakeValue 之类的扩展）零成本接入。
 */
public final class HierarchyFieldFinder {

	private static final Map<Class<?>, Map<String, java.lang.reflect.Field>> CACHE =
			new ConcurrentHashMap<>();

	private HierarchyFieldFinder() {
	}

	/**
	 * 沿父类链查找字段（含自身），按 (Class, name) 缓存 Field。
	 *
	 * @param instance 实例（起点 Class 取自它）
	 * @param name     字段名
	 * @return 可访问的 Field；不可达时抛 NoSuchFieldException（调用方 catch 后走降级）
	 */
	public static java.lang.reflect.Field find(Object instance, String name) throws NoSuchFieldException {
		final Class<?> cls = instance.getClass();
		final Map<String, java.lang.reflect.Field> byName =
				CACHE.computeIfAbsent(cls, k -> new ConcurrentHashMap<>());
		final java.lang.reflect.Field cached = byName.get(name);
		if (cached != null) {
			return cached;
		}
		for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
			try {
				final java.lang.reflect.Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				byName.put(name, f);
				return f;
			} catch (NoSuchFieldException ignore) {
				// 沿父类链继续
			}
		}
		throw new NoSuchFieldException(name);
	}
}
