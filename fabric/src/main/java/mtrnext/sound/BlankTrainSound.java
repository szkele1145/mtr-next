package mtrnext.sound;

import mtr.data.TrainClient;
import mtr.sound.TrainSoundBase;

/**
 * [0.6] 4.0 车辆的空声音实现（BVE 声音桥 0.7 接）。
 * <p>
 * javap 实证：TrainSoundBase 是抽象类（无工厂方法，无 blank）——
 * createTrainInstance / playNearestCar / playAllCars / playAllCarsDoorOpening。
 * 本类给出全空实现：注册进 TrainProperties 时合法，实际播放为静音。
 * 0.7 计划：解析 BVE sound.cfg（motor 档位 + csv 调速曲线）做真播放。
 */
public final class BlankTrainSound extends TrainSoundBase {

	@Override
	public TrainSoundBase createTrainInstance(TrainClient train) {
		return new BlankTrainSound();
	}

	@Override
	public void playNearestCar(net.minecraft.world.level.Level world, net.minecraft.core.BlockPos pos, int carNumber) {
		// 静音（0.7 BVE 桥）
	}

	@Override
	public void playAllCars(net.minecraft.world.level.Level world, net.minecraft.core.BlockPos pos, int unused) {
		// 静音
	}

	@Override
	public void playAllCarsDoorOpening(net.minecraft.world.level.Level world, net.minecraft.core.BlockPos pos, int unused) {
		// 静音
	}
}
