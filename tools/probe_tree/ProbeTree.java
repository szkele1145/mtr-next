package probe;
import net.minecraft.client.model.geom.*;
import net.minecraft.client.model.geom.builders.*;
import java.lang.reflect.*;
import java.util.*;
public class ProbeTree {
  static Field CF, CUBEPOLY, PF, UF, VF, CH;
  public static void main(String[] a) throws Exception {
    CF = ModelPart.class.getDeclaredField("cubes"); CF.setAccessible(true);
    Class<?> cubeCls = Class.forName("net.minecraft.client.model.geom.ModelPart$Cube");
    CUBEPOLY = cubeCls.getDeclaredField("polygons"); CUBEPOLY.setAccessible(true);
    Class<?> poly = Class.forName("net.minecraft.client.model.geom.ModelPart$Polygon");
    PF = poly.getDeclaredField("vertices"); PF.setAccessible(true);
    Class<?> vtx = Class.forName("net.minecraft.client.model.geom.ModelPart$Vertex");
    UF = vtx.getDeclaredField("u"); UF.setAccessible(true);
    VF = vtx.getDeclaredField("v"); VF.setAccessible(true);
    CH = ModelPart.class.getDeclaredField("children"); CH.setAccessible(true);
    MeshDefinition mesh = new MeshDefinition();
    PartDefinition root = mesh.getRoot();
    PartDefinition anchor = root.addOrReplaceChild("anchor", CubeListBuilder.create(), PartPose.ZERO);
    PartDefinition holder = anchor.addOrReplaceChild("holder", CubeListBuilder.create(), PartPose.offsetAndRotation(0, -16, 0, 0, 0, 0.7853982f));
    PartDefinition cubeNode = holder.addOrReplaceChild("cube", CubeListBuilder.create().addBox("cube", 0,0,0, 16,16,16, new CubeDeformation(0), 100, 200), PartPose.ZERO);
    ModelPart baked = LayerDefinition.create(mesh, 1024, 1024).bakeRoot();
    dump(baked, 0);
  }
  static void dump(ModelPart p, int d) throws Exception {
    StringBuilder sb = new StringBuilder();
    for (int i=0;i<d;i++) sb.append("  ");
    sb.append("x=").append(p.x).append(" y=").append(p.y).append(" z=").append(p.z)
      .append(" xr=").append(p.xRot).append(" yr=").append(p.yRot).append(" zr=").append(p.zRot);
    List<?> cubes = (List<?>) CF.get(p);
    sb.append(" cubes=").append(cubes.size());
    System.out.println(sb);
    for (Object c : cubes) {
      Object[] polys = (Object[]) CUBEPOLY.get(c);
      System.out.println("    cube poly=" + polys.length);
      for (Object po : polys) {
        Object[] vertices = (Object[]) PF.get(po);
        float minU=999,maxU=-999,minV=999,maxV=-999;
        for (Object vertex : vertices) { float u=UF.getFloat(vertex), v=VF.getFloat(vertex);
          minU=Math.min(minU,u); maxU=Math.max(maxU,u); minV=Math.min(minV,v); maxV=Math.max(maxV,v); }
        System.out.println("      u=[" + (int)(minU*1024) + ".." + (int)(maxU*1024) + "] v=[" + (int)(minV*1024) + ".." + (int)(maxV*1024) + "]");
      }
    }
    Map<?,?> children = (Map<?,?>) CH.get(p);
    for (var e : children.entrySet()) { System.out.println("  child key=" + e.getKey()); dump((ModelPart)e.getValue(), d+1); }
  }
}