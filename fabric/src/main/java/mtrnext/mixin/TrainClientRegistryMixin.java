package mtrnext.mixin;

import mtr.client.TrainClientRegistry;
import mtr.client.TrainProperties;
import mtrnext.MtrNext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>[0.59.0] 车型解析的决定性探针。</b>
 *
 * <p>用户要求「证伪」：在 {@code TrainClientRegistry.getTrainProperties(...)} 的
 * 每次调用处打出「传入的 id」和「返回的 {@code TrainProperties.baseTrainType}」。
 *
 * <p>为什么必须探这里：{@code mtr.data.TrainClient} 在构造时用
 * <pre>
 *   TrainProperties p = TrainClientRegistry.getTrainProperties(this.trainId);
 *   this.trainRenderer = p.renderer.createTrainInstance(this);
 * </pre>
 * 解析<b>只看 {@code trainId}</b>，且<b>未命中时静默返回
 * {@link TrainProperties#getBlankProperties()}（baseTrainType=""，
 * 渲染器是原版 JonModelTrainRenderer）</b>。所以：
 * <ul>
 *   <li>id 命中 → 返回我们注册的 4.0 properties ⇒ 世界里是 4.0 车；</li>
 *   <li>id 不命中 → blank ⇒ <b>世界里渲染成原版车，且没有任何报错</b>。</li>
 * </ul>
 * 这一条「静默回退」正是「选了 4.0 车却看到原版车」最难查的路径，
 * 本探针把它变成一行显式日志。
 *
 * <h2>为什么按 id 去重</h2>
 * 渲染热路径每帧多次调用；同 id 同结果只打第一条，结果变化（例如
 * {@code pinFamilyVariant} 重新注册、或 reload 后不再命中）时再打一条。
 */
@Mixin(value = mtr.client.TrainClientRegistry.class, remap = false)
public abstract class TrainClientRegistryMixin {

	/** id → 上次看到的 baseTrainType（含 blank 标记） */
	private static final Map<String, String> LAST_SEEN = new ConcurrentHashMap<>();

	@Inject(method = "getTrainProperties(Ljava/lang/String;)Lmtr/client/TrainProperties;",
			at = @At("RETURN"), remap = false)
	private static void mtrnext$auditResolve(String key, CallbackInfoReturnable<TrainProperties> cir) {
		try {
			final TrainProperties props = cir.getReturnValue();
			final String id = key == null ? "<null>" : key.toLowerCase(Locale.ENGLISH);
			final String base = props == null ? "<no-props>" : props.baseTrainType;
			// getBlankProperties() 每次 new 一个新实例，不能比引用 ——
			// 它的定义特征就是 baseTrainType == ""。
			final boolean blank = props != null && (props.baseTrainType == null || props.baseTrainType.isEmpty());
			final String marker = blank ? "<blank>" : base;
			final String previous = LAST_SEEN.put(id, marker);
			if (marker.equals(previous)) {
				return; // 同 id 同结果：不刷屏
			}
			MtrNext.LOGGER.info("[compat-audit] getTrainProperties('{}') → baseTrainType='{}'{} renderer={}",
					id, base,
					blank ? " ⚠未注册（回退 blank ⇒ 世界里会渲染成原版车）" : "",
					props == null ? "<null>" : props.renderer.getClass().getName());
		} catch (final Throwable t) {
			// 探针绝不干扰主流程
		}
	}
}
