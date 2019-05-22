package li.joker.ondevicelayoutinspector.plugin;

import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.IDevice;
import com.android.tools.idea.adb.AdbService;
import com.android.tools.idea.explorer.adbimpl.*;
import com.android.tools.idea.explorer.fs.FileTransferProgress;
import com.android.tools.idea.model.AndroidModel;
import com.intellij.facet.ProjectFacetManager;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.util.concurrency.EdtExecutorService;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.sdk.AndroidSdkUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.SystemIndependent;
import org.jetbrains.ide.PooledThreadExecutor;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class Sync extends AnAction {

    private static Set<String> collectAppIds(Project project) {
        final List<AndroidFacet> facets = ProjectFacetManager.getInstance(project).getFacets(AndroidFacet.ID);
        final Set<String> result = new HashSet<>();

        for (AndroidFacet facet : facets) {
            AndroidModel androidModel = null;
            try {
                androidModel = facet.getConfiguration().getModel();
            } catch (NoSuchMethodError error) {
                try {
                    Method getMethod = facet.getClass().getDeclaredMethod("getAndroidModel");
                    androidModel = (AndroidModel) getMethod.invoke(facet);
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                    e.printStackTrace();
                }
            }
            if (androidModel != null) {
                result.addAll(androidModel.getAllApplicationIds());
            }
        }

        return result;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent evt) {
        Task.Backgroundable task = new Task.Backgroundable(evt.getProject(), "Fetching SuperInspector Results...") {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    @SystemIndependent String projectPath = Objects.requireNonNull(evt.getProject()).getBasePath();
                    File capturePath = new File(projectPath + "/captures");
                    capturePath.mkdir();
                    File adb = AndroidSdkUtils.getAdb(evt.getProject());
                    indicator.setFraction(0.1);

                    Set<String> ids = collectAppIds(evt.getProject());
                    String appid = ids.iterator().next();
                    indicator.setText("项目包名：" + appid);
                    indicator.setFraction(0.2);

                    AdbDeviceFileSystemService service = new AdbDeviceFileSystemService(a -> adb,
                            EdtExecutorService.getInstance(),
                            PooledThreadExecutor.INSTANCE,
                            () -> System.out.println("disposing AdbDeviceFileSystemService"));
                    AndroidDebugBridge bridge = AndroidDebugBridge.getBridge();
                    if (bridge == null || !bridge.hasInitialDeviceList()) {
                        indicator.setFraction(0.3);
                        indicator.setText("初始化ADB...");
                        AndroidDebugBridge.IDeviceChangeListener deviceListener = new AndroidDebugBridge.IDeviceChangeListener() {
                            @Override
                            public void deviceConnected(IDevice iDevice) {
                                indicator.setFraction(0.4);
                                Sync.this.syncLiFiles(evt.getProject(), capturePath, appid, service, iDevice, indicator);
                                AndroidDebugBridge.removeDeviceChangeListener(this);
                            }

                            @Override
                            public void deviceDisconnected(IDevice iDevice) {

                            }

                            @Override
                            public void deviceChanged(IDevice iDevice, int i) {

                            }
                        };
                        AndroidDebugBridge.addDeviceChangeListener(deviceListener);
                        AdbService.getInstance().getDebugBridge(adb);
                    } else {
                        indicator.setFraction(0.4);
                        IDevice[] connected = bridge.getDevices();
                        if (connected.length == 0) {
                            indicator.cancel();
                            Notifications.Bus.notify(new Notification("SuperInspector", "SuperInspector",
                                    "No connected device", NotificationType.INFORMATION), evt.getProject());
                            return;
                        }
                        syncLiFiles(evt.getProject(), capturePath, appid, service, connected[0], indicator);
                    }
                } catch (Throwable e) {
                    indicator.cancel();
                    throw new RuntimeException(e);
                }
            }
        };
        ProgressManager.getInstance().runProcessWithProgressAsynchronously(task, new BackgroundableProcessIndicator(task));

    }

    private void syncLiFiles(Project project, File capturePath, String appid,
                             AdbDeviceFileSystemService service, IDevice device,
                             ProgressIndicator indicator) {
        try {
            indicator.setText("使用设备:" + device.getSerialNumber());
            indicator.setFraction(0.5);

            AdbDeviceFileSystem deviceFileSystem = new AdbDeviceFileSystem(service, device);
            AdbDeviceCapabilities deviceCapabilities = new AdbDeviceCapabilities(device);
            AdbFileListing afl = new AdbFileListing(device, deviceCapabilities, PooledThreadExecutor.INSTANCE);
            List<AdbFileListingEntry> lis = afl.getChildrenRunAs(new AdbFileListingEntry("/data/data/" + appid + "/files/OnDeviceLayoutInspector",
                    AdbFileListingEntry.EntryKind.DIRECTORY, null, null, null, null, null, null, null), appid).get();
            File[] currentLis = capturePath.listFiles();

            List<AdbFileListingEntry> newLis = currentLis == null ? lis :
                    lis.stream().filter(aflie -> Arrays.stream(currentLis).noneMatch(f -> {
                        return aflie.getName().equals(f.getName());
                    })).collect(Collectors.toList());
            boolean openAfterDownload = newLis.size() <= 3;

            indicator.setFraction(0.6);
            if (newLis.isEmpty()) {
                indicator.cancel();
                Notifications.Bus.notify(new Notification("SuperInspector", "Fetching finished",
                        "没有找到新的Inspector文件", NotificationType.INFORMATION), project);
                return;
            }
            double liFileFraction = (1 - indicator.getFraction()) / newLis.size();
            CountDownLatch latch = new CountDownLatch(newLis.size());
            newLis.forEach(lie -> {
                File local = new File(capturePath, lie.getName());
                try {
                    deviceFileSystem.getAdbFileTransfer().downloadFileViaTempLocation(lie.getFullPath(), lie.getSize(), local.toPath(),
                            new FileTransferProgress() {
                                @Override
                                public void progress(long l, long l1) {
                                    if (l == l1) {
                                        indicator.setFraction(indicator.getFraction() + liFileFraction);
                                        latch.countDown();
                                        indicator.setText(lie.getName());
                                        if (latch.getCount() == 0) {
                                            indicator.cancel();
                                            if (!openAfterDownload) {
                                                Notifications.Bus.notify(new Notification("SuperInspector",
                                                        "Fetching finished", "已保存" + newLis.size() + "个文件到" + capturePath,
                                                        NotificationType.INFORMATION), project);
                                                ApplicationManager.getApplication().invokeLater(() ->
                                                        Objects.requireNonNull(VfsUtil.findFileByIoFile(capturePath, true))
                                                                .refresh(true, true));
                                            }
                                        }
                                        if (openAfterDownload) {
                                            ApplicationManager.getApplication().invokeLater(() -> {
                                                FileEditorManager.getInstance(project).openFile(Objects.requireNonNull(
                                                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(local)
                                                ), true);
                                            });
                                        }
                                    }
                                }

                                @Override
                                public boolean isCancelled() {
                                    return false;
                                }
                            }, appid).get();
                } catch (InterruptedException | ExecutionException e) {
                    indicator.cancel();
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            indicator.cancel();
            throw new RuntimeException(e);
        }
    }
}
