package com.example.myapplication;

public class NativeUtils {
    static {
        System.loadLibrary("bspatch");
    }

    // 声明 native 方法
    public static native int bspatch(String oldFile, String newFile, String patchFile);
}
