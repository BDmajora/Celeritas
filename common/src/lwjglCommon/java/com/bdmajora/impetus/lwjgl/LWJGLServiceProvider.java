package com.bdmajora.impetus.lwjgl;

// Picks the LWJGL backend at class-init through ServiceLoader, taking the highest-priority implementation that
// loads successfully
// ServiceLoader rather than a version check, because both backends may be on the classpath at once and only the
// one whose native library actually resolves can answer
public final class LWJGLServiceProvider {
    public static final LWJGLService LWJGL = createInstance();
    public static final int POINTER_SIZE = LWJGL.getPointerSize();
    public static final long NULL = 0L;

    private LWJGLServiceProvider() {}

    // Reflective construction so neither backend class is linked until chosen
    static LWJGLService constructInstance(String className) {
        try {
            var clz = Class.forName(className);
            var method = clz.getDeclaredMethod("create");
            return (LWJGLService)method.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // Picks LWJGL2 or LWJGL3 by probing which org.lwjgl classes are present
    static LWJGLService createInstance() {
        try {
            Class.forName("org.lwjgl.opengl.GL11C");
            return constructInstance("com.bdmajora.impetus.lwjgl.lwjgl3.LWJGL3Service");
        } catch (ClassNotFoundException e) {
            return constructInstance("com.bdmajora.impetus.lwjgl.lwjgl2.LWJGL2Service");
        }
    }
}
