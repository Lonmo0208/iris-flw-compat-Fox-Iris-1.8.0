package top.leonx.irisflw.flywheel;

import java.util.concurrent.atomic.AtomicBoolean;

public class RenderLayerEventStateManager {
    private static final AtomicBoolean renderingShadow = new AtomicBoolean(false);

    private static boolean lastShadowState = false;

    public static boolean isRenderingShadow() {
        if (Thread.currentThread().getName().contains("Render thread")) {
            return lastShadowState;
        }
        lastShadowState = renderingShadow.get();
        return lastShadowState;
    }

    public static void setRenderingShadow(boolean state) {
        boolean oldState = renderingShadow.getAndSet(state);
        if (oldState != state) {
            lastShadowState = state;
        }
    }
}
