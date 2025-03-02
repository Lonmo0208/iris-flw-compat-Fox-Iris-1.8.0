package top.leonx.irisflw.forge;

import dev.engine_room.flywheel.event.RenderLayerEvent;
import net.minecraftforge.common.MinecraftForge;

public class FlywheelEventsInvokerImpl {
    public static void InvokeRenderLayer(RenderLayerEvent renderLayerEvent){
        MinecraftForge.EVENT_BUS.post(renderLayerEvent);
    }
}
