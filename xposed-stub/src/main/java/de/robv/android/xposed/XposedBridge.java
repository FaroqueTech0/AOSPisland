package de.robv.android.xposed;

import java.util.Set;

public final class XposedBridge {
    private XposedBridge() { }
    public static void log(String text) { throw new UnsupportedOperationException("stub"); }
    public static void log(Throwable t) { throw new UnsupportedOperationException("stub"); }
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) { throw new UnsupportedOperationException("stub"); }
}
