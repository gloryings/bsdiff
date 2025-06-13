package com.example.myapplication;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_STORAGE_PERMISSION = 100;
    private Button patchButton;
    private boolean isPatching = false;

    private File patchDir;
    private File newApk;
    private File patch;

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        patchDir = new File(getFilesDir(), "test");
        newApk = new File(patchDir, "Newer.apk");
        patch = new File(patchDir, "Patch.patch");

        patchButton = findViewById(R.id.patch_button);

        // Android 11+ 申请所有文件访问权限
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//            if (!Environment.isExternalStorageManager()) {
//                try {
//                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
//                    intent.setData(Uri.parse("package:" + getPackageName()));
//                    startActivity(intent);
//                } catch (ActivityNotFoundException e) {
//                    e.printStackTrace();
//                    Toast.makeText(this, "无法打开文件权限设置，请手动授权", Toast.LENGTH_LONG).show();
//                }
//            }
//        }
        // 检查权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            // 如果没有权限，请求权限
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_CODE_STORAGE_PERMISSION);
        }


        patchButton.setOnClickListener(v -> {
            if (isPatching) return;
            isPatching = true;

            if (hasStoragePermission()) {
                copyPatchFromExternalToInternal();
                if(!isPatching)
                        return;
                startPatchProcess();
            } else {
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, 2);
            }
        });
    }

    /** 判断是否有存储权限 */
    @RequiresApi(api = Build.VERSION_CODES.M)
    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11及以上需要特殊权限
            return Environment.isExternalStorageManager();
        } else {
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    /** 复制外部 Download 目录的 Patch.patch 到内部目录 */
    private void copyPatchFromExternalToInternal() {
        File externalPatchFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Patch.patch");
        Log.d("PatchDebug", "Patch Origin file path: " + externalPatchFile.getAbsolutePath());
        if (!externalPatchFile.exists()) {
            showToast("外部 PATCH.patch 不存在，请先放入下载目录");
            isPatching = false;
            return;
        }

        if (!patchDir.exists()) {
            patchDir.mkdirs();
        }

        try (FileInputStream fis = new FileInputStream(externalPatchFile);
             FileOutputStream fos = new FileOutputStream(patch)) {

            byte[] buffer = new byte[1024];
            int length;
            while ((length = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            fos.flush();
            showToast("PATCH.patch 复制到内部目录成功");
        } catch (IOException e) {
            e.printStackTrace();
            showToast("复制 PATCH.patch 失败：" + e.getMessage());
            isPatching = false;
        }
    }

    /** 创建 newApk 文件，如果不存在 */
    private File createNewApk() {
        try {
            if (newApk.createNewFile())
                Log.d("PatchDebug", "newApk 文件创建成功");
        } catch (IOException e) {
            e.printStackTrace();
        }
        return newApk;
    }

    /** patch 主逻辑，后台线程执行 */
    private void startPatchProcess() {
        new Thread(() -> {
            Log.d("PatchDebug", "Patch directory: " + patchDir.getAbsolutePath());
            Log.d("PatchDebug", "New APK path: " + newApk.getAbsolutePath());
            Log.d("PatchDebug", "Patch file path: " + patch.getAbsolutePath());

            String oldApkPath = extractInstalledApkPath(this);
            Log.d("PatchDebug", "oldApkPath：" + oldApkPath);

            if (!patch.exists()) {
                runOnUiThread(() -> {
                    patchButton.setEnabled(true);
                    showToast("PATCH.patch 不存在！");
                    isPatching = false;
                });
                return;
            }

            createNewApk();
            if (!newApk.exists()) {
                runOnUiThread(() -> {
                    patchButton.setEnabled(true);
                    showToast("newApk 不存在！");
                    isPatching = false;
                });
                return;
            }

            int result = NativeUtils.bspatch(
                    oldApkPath,
                    newApk.getAbsolutePath(),
                    patch.getAbsolutePath()
            );

            runOnUiThread(() -> {
                patchButton.setEnabled(true);
                if (result == 0 && newApk.exists()) {
                    showToast("Patch 成功，准备安装");
                    installApk(this, newApk.getAbsolutePath());
                } else {
                    showToast("合并失败或新 APK 不存在");
                }
                isPatching = false;
            });
        }).start();
    }

    /** 获取当前安装的 APK 路径 */
    public static String extractInstalledApkPath(Context context) {
        ApplicationInfo appInfo = context.getApplicationInfo();
        return appInfo.sourceDir;
    }

    /** 安装 APK，兼容 Android 7.0+ */
    public static void installApk(Context context, String apkPath) {
        File apkFile = new File(apkPath);
        if (!apkFile.exists()) return;

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Uri apkUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            apkUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    apkFile
            );
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            apkUri = Uri.fromFile(apkFile);
        }

        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        context.startActivity(intent);
    }

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 权限请求回调 */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show();
                copyPatchFromExternalToInternal();
            } else {
                Toast.makeText(this, "未授予存储权限，无法读取补丁文件", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isPatching = false;
    }
}
