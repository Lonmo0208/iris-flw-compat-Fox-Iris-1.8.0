package top.leonx.irisflw.fabric;

import dev.engine_room.flywheel.event.RenderLayerEvent;
import dev.engine_room.flywheel.fabric.event.FlywheelEvents;

public class FlywheelEventsInvokerImpl {
    public static void InvokeRenderLayer(RenderLayerEvent renderLayerEvent){
        FlywheelEvents.RENDER_LAYER.invoker().handleEvent(renderLayerEvent);
    }
}
