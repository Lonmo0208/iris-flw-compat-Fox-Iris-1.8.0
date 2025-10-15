package top.leonx.irisflw.iris;

import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniform;
import org.joml.Matrix3f;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

public class GlUniformMcMatrix3f extends GlUniform<Matrix3f> {
    private float[] cachedMatrix = null;
    
    public GlUniformMcMatrix3f(int location) {
        super(location);
    }

    @Override
    public void set(Matrix3f value) {
        if (index < 0) {
            return;
        }
        
        try (MemoryStack stack = MemoryStack.stackPush()) {
            float[] matrixData = new float[9];
            value.get(matrixData);

            if (cachedMatrix == null || !matrixEquals(cachedMatrix, matrixData)) {
                if (cachedMatrix == null) {
                    cachedMatrix = new float[9];
                }
                System.arraycopy(matrixData, 0, cachedMatrix, 0, 9);

                GL30C.glUniformMatrix3fv(index, false, matrixData);
            }
        }
    }

    private boolean matrixEquals(float[] m1, float[] m2) {
        for (int i = 0; i < 9; i++) {
            if (Math.abs(m1[i] - m2[i]) > 1e-6f) {
                return false;
            }
        }
        return true;
    }

    public void clearCache() {
        cachedMatrix = null;
    }
}
