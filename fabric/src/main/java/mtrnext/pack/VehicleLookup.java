package mtrnext.pack;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>[0.59.0] 4.0 车型 id 的一等公民解析表。</b>
 *
 * <h2>为什么需要它</h2>
 * YMTR 的 {@code mtr.data.TrainType} 只认识两种字符串：
 * <ol>
 *   <li>{@code <mode>_<len>_<width>}（如 {@code train_12_3}）—— 直接解析出长度/宽度；</li>
 *   <li>{@code TrainType} 枚举常量的名字（如 {@code SP1900_MINI}）。</li>
 * </ol>
 * 4.0 车包的车型 id（{@code eridu-dg1000a}）<b>两者都不是</b>，于是
 * {@code TrainType.getSpacing} 的兜底值 {@code 1 + 1 = 2} 会把「12 格长的车」
 * 算成 2 格 ⇒ 车厢数爆表、渲染间距错乱。
 *
 * <p>历史上 mtr-next 的对策是「把 4.0 车型<b>翻译</b>成 {@code train_<len>_<width>}
 * 再写进 {@code Siding.baseTrainType}」——这正是用户反复要求删除的
 * <b>「强制兼容」</b>：4.0 车型在数据层被冒充成 3.x 车型。
 *
 * <p>本类给 {@code TrainType} 的解析方法提供「4.0 车型 → 真实几何」的答案，
 * 使 {@code Siding.baseTrainType} 可以直接写 4.0 车型 id 而长度/宽度依然正确，
 * 彻底不需要那层翻译。
 *
 * <h2>缓存策略</h2>
 * 只缓存<b>命中</b>（正向缓存），不缓存未命中 —— 4.0 车包是资源重载时异步填充的，
 * 缓存一次「当时还没有」的空结果会永久毒化解析。未命中的代价是一次几十项的
 * 线性扫描，而这些方法只在 Siding/Train 构造和界面 hover 时调用，可忽略。
 */
public final class VehicleLookup {

	/** 小写 id（含 family 形式）→ 车辆资源；仅在命中时写入 */
	private static final Map<String, VehicleResource> HIT = new ConcurrentHashMap<>();

	private VehicleLookup() {
	}

	/**
	 * 按车型 id 找 4.0 车包里的车。
	 *
	 * <p>接受三种写法（都会做小写归一）：
	 * <ul>
	 *   <li>完整 id：{@code ERIDU-DG1000A(M_B)}；</li>
	 *   <li>family 形式：{@code ERIDU-DG1000A}；</li>
	 *   <li>任意大小写。</li>
	 * </ul>
	 * 3.x 的字符串（{@code train_12_3} / {@code SP1900}）不会命中任何车 ⇒ 返回 {@code null}，
	 * 调用方必须原样退回 YMTR 的解析逻辑。
	 */
	public static VehicleResource find(String trainType) {
		if (trainType == null) {
			return null;
		}
		final String raw = trainType.trim();
		if (raw.isEmpty()) {
			return null;
		}
		final String key = raw.toLowerCase(Locale.ENGLISH);
		final VehicleResource cached = HIT.get(key);
		if (cached != null) {
			return cached;
		}
		final VehicleResource found = resolve(key);
		if (found != null) {
			HIT.put(key, found);
			// family 键也缓存一份，避免同一家族的三个变体各扫一遍
			HIT.put(familyOf(key), found);
		}
		return found;
	}

	/** 4.0 车包是否已装载（纯 3.x 用户为 false） */
	public static boolean hasAny() {
		return !VehiclePackLoader.getVehicles().isEmpty();
	}

	/**
	 * 该车型应当写进 {@code Siding.baseTrainType} 的「4.0 正式名」。
	 *
	 * <p>与 {@code TrainTypeBridge} 的注册键严格一致（family 形式、小写），
	 * 这样 {@code TrainClientRegistry.getTrainProperties(baseTrainType)} 必定命中
	 * 4.0 的 {@code TrainProperties}（其 {@code baseTrainType} 也是 4.0 名）。
	 *
	 * @return 命中时返回 family 小写名；不是 4.0 车型返回 {@code null}
	 */
	public static String canonicalBase(String trainType) {
		final VehicleResource res = find(trainType);
		return res == null ? null : familyOf(res.id.toLowerCase(Locale.ENGLISH));
	}

	/** 资源重载时清空命中缓存（车包增删后 id 集合会变） */
	public static void clearCache() {
		HIT.clear();
	}

	/** {@code ERIDU-DG1000A(M_B)} → {@code eridu-dg1000a}（入参须已小写） */
	private static String familyOf(String lowerId) {
		final int i = lowerId.indexOf('(');
		return i < 0 ? lowerId : lowerId.substring(0, i);
	}

	private static VehicleResource resolve(String lowerKey) {
		final Map<String, VehicleResource> all = VehiclePackLoader.getVehicles();
		if (all.isEmpty()) {
			return null;
		}
		// 1) 直接命中（车包 id 恰好是小写时）
		final VehicleResource direct = all.get(lowerKey);
		if (direct != null) {
			return direct;
		}
		// 2) 大小写不定 + family 兜底
		final String wantFamily = familyOf(lowerKey);
		for (final Map.Entry<String, VehicleResource> e : all.entrySet()) {
			final String k = e.getKey().toLowerCase(Locale.ENGLISH);
			if (k.equals(lowerKey)) {
				return e.getValue();
			}
			if (k.contains("(") && familyOf(k).equals(wantFamily)) {
				return e.getValue();
			}
		}
		return null;
	}
}
