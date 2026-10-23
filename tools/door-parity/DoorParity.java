import org.mtr.mod.resource.DoorAnimationType;
import mtrnext.client.render.DoorAnimation4x;

/**
 * Numeric parity harness: upstream MTR 4.0.5 DoorAnimationType vs mtr-next DoorAnimation4x.
 * Loads the real upstream enum from the 4.0.5 jar, calls both implementations on a dense
 * time grid with identical (float-cast) inputs, and compares outputs.
 *
 * Tolerance: max(1e-4 px absolute, 1e-6 relative) - far below any visual significance;
 * the point is to catch semantic (branch/constant) divergence, not float ulp noise.
 */
public class DoorParity {

    static long checked = 0, exact = 0, over = 0;
    static double maxAbs = 0;
    static final StringBuilder report = new StringBuilder();

    public static void main(String[] args) throws Exception {
        // ---- probe parameter order ----
        DoorAnimationType upC = DoorAnimationType.valueOf("CONSTANT");
        double p1 = upC.getDoorAnimationZ(1.0, false, 0.5, false);
        System.out.println("[probe] CONSTANT getDoorAnimationZ(1, false, 0.5, false) = " + p1
                + "   (expect 1.0 if order = (multiplier, flipped, time, opening))");
        DoorAnimationType upP = DoorAnimationType.valueOf("PLUG_FAST");
        double p2 = upP.getDoorAnimationX(2.0, false, 0.5);
        System.out.println("[probe] PLUG_FAST getDoorAnimationX(2, false, 0.5) = " + p2
                + "   (expect 2.05 if order = (multiplier, flipped, time))");
        if (Math.abs(p1 - 1.0) > 1e-9 || Math.abs(p2 - 2.05) > 1e-9) {
            System.out.println("!! PARAMETER ORDER ASSUMPTION FAILED - abort");
            return;
        }
        System.out.println("[probe] order confirmed: (multiplier, flipped, time[, opening])");
        System.out.println("[probe] upstream enum constants: " + DoorAnimationType.values().length);

        // ---- grid ----
        float[] mults = {0.0f, 0.5f, 1.0f, 2.0f, 3.0f, -0.5f, -1.0f, -2.0f};
        boolean[] flips = {false, true};
        boolean[] opens = {false, true};
        java.util.List<Float> times = new java.util.ArrayList<>();
        for (double t = 0.0; t <= 1.0001; t += 0.001) times.add((float) t);
        float[] extra = {-0.5f, -0.1f, -0.05f, 1.05f, 1.1f, 1.5f, 2.0f};
        for (float e : extra) times.add(e);

        for (DoorAnimationType up : DoorAnimationType.values()) {
            DoorAnimation4x nx = DoorAnimation4x.valueOf(up.name());
            for (float m : mults) {
                for (boolean f : flips) {
                    for (float t : times) {
                        double uv = up.getDoorAnimationX((double) m, f, (double) t);
                        float nv = nx.getDoorAnimationX(m, f, t);
                        cmp(up.name(), "X", m, f, t, false, uv, nv);
                    }
                    for (boolean o : opens) {
                        for (float t : times) {
                            double uv = up.getDoorAnimationZ((double) m, f, (double) t, o);
                            float nv = nx.getDoorAnimationZ(m, f, t, o);
                            cmp(up.name(), "Z", m, f, t, o, uv, nv);
                        }
                    }
                }
            }
        }
        System.out.println("checked=" + checked + "  bitwise-exact=" + exact + "  over-tolerance=" + over);
        System.out.println("maxAbsDiff=" + maxAbs + " px");
        if (report.length() > 0) {
            System.out.println("--- violations (first 20) ---");
            System.out.print(report);
            System.exit(1);
        } else {
            System.out.println("ALL PASS (tolerance max(1e-4 px, 1e-6 rel))");
        }
    }

    static void cmp(String type, String axis, float m, boolean f, float t, boolean o, double uv, float nv) {
        checked++;
        double nd = (double) nv;
        double ad = Math.abs(uv - nd);
        if (ad == 0.0) exact++;
        if (ad > maxAbs) maxAbs = ad;
        double tol = Math.max(1e-4, Math.abs(uv) * 1e-6);
        if (ad > tol) {
            over++;
            if (over <= 20) {
                report.append(String.format(
                        "%s.%s m=%s f=%b t=%s o=%b up=%s next=%s diff=%s%n",
                        type, axis, m, f, t, o, uv, nd, ad));
            }
        }
    }
}
