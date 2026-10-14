package mtrnext.util;

/**
 * [0.4] 宿主 Siding 实例的传递器。
 * <p>
 * SidingScreenMixin 打开编组屏时把宿主 Siding 实例暂存于此
 * （Screen 构造参数不便传宿主——编组屏的构造签名保持 UUID 简洁），
 * 编组屏 onClose 时 consume 取回做桥接。单客户端线程，无并发问题。
 */
public final class HostSidingHolder {

	private static Object host;

	private HostSidingHolder() {
	}

	public static void set(Object sidingInstance) {
		host = sidingInstance;
	}

	/** 取回并清空（一次性语义——屏关闭后不留引用） */
	public static Object consume() {
		final Object h = host;
		host = null;
		return h;
	}
}
