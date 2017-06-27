/*
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/
package com.android.packageinstaller;

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ManifestDigest;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageParser;
import android.content.pm.PackageUserState;
import android.content.pm.ResolveInfo;
import android.content.pm.VerificationParams;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AppSecurityPermissions;
import android.widget.Button;
import android.widget.TabHost;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/*
 * This activity is launched when a new application is installed via side loading
 * The package is first parsed and the user is notified of parse errors via a dialog.
 * If the package is successfully parsed, the user is notified to turn on the install unknown
 * applications setting. A memory check is made at this point and the user is notified of out
 * of memory conditions if any. If the package is already existing on the device, 
 * a confirmation dialog (to replace the existing package) is presented to the user.
 * Based on the user response the package is then installed by launching InstallAppConfirm
 * sub activity. All state transitions are handled in this activity
 */
public class PackageInstallerActivity extends Activity implements OnCancelListener, OnClickListener {
    private static final String TAG = "PackageInstaller";

    private int mSessionId = -1;
    private Uri mPackageURI;    
    private Uri mOriginatingURI;
    private Uri mReferrerURI;
    private int mOriginatingUid = VerificationParams.NO_UID;
    private ManifestDigest mPkgDigest;

    private boolean localLOGV = false;
    PackageManager mPm;
    PackageInstaller mInstaller;
    PackageInfo mPkgInfo;
    ApplicationInfo mSourceInfo;

    // ApplicationInfo object primarily used for already existing applications
    private ApplicationInfo mAppInfo = null;

    private InstallFlowAnalytics mInstallFlowAnalytics;

    // View for install progress
    View mInstallConfirm;
    // Buttons to indicate user acceptance
    private Button mOk;
    private Button mCancel;
    CaffeinatedScrollView mScrollView = null;
    private boolean mOkCanInstall = false;

    static final String PREFS_ALLOWED_SOURCES = "allowed_sources";

    private static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";

    private static final String TAB_ID_ALL = "all";
    private static final String TAB_ID_NEW = "new";

    // Dialog identifiers used in showDialog
    private static final int DLG_BASE = 0;
    private static final int DLG_UNKNOWN_APPS = DLG_BASE + 1;
    private static final int DLG_PACKAGE_ERROR = DLG_BASE + 2;
    private static final int DLG_OUT_OF_SPACE = DLG_BASE + 3;
    private static final int DLG_INSTALL_ERROR = DLG_BASE + 4;
    private static final int DLG_ALLOW_SOURCE = DLG_BASE + 5;

    private void startInstallConfirm() {
        TabHost tabHost = (TabHost)findViewById(android.R.id.tabhost);
        tabHost.setup();
        ViewPager viewPager = (ViewPager)findViewById(R.id.pager);
        TabsAdapter adapter = new TabsAdapter(this, tabHost, viewPager);
        adapter.setOnTabChangedListener(new TabHost.OnTabChangeListener() {
            @Override
            public void onTabChanged(String tabId) {
                if (TAB_ID_ALL.equals(tabId)) {
                    mInstallFlowAnalytics.setAllPermissionsDisplayed(true);
                } else if (TAB_ID_NEW.equals(tabId)) {
                    mInstallFlowAnalytics.setNewPermissionsDisplayed(true);
                }
            }
        });
        //该字段表示否时显示权限列表
        boolean permVisible = false;
        mScrollView = null;
        mOkCanInstall = false;
        int msg = 0;
        if (mPkgInfo != null) {
            //AppSecurityPermissions 是一个组件 封装了一些列处理权限的功能
            AppSecurityPermissions perms = new AppSecurityPermissions(this, mPkgInfo);
            //获取与隐私相关的权限数量
            final int NP = perms.getPermissionCount(AppSecurityPermissions.WHICH_PERSONAL);
            //获取与设备相关的权限数量
            final int ND = perms.getPermissionCount(AppSecurityPermissions.WHICH_DEVICE);
            if (mAppInfo != null) {
                msg = (mAppInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        ? R.string.install_confirm_question_update_system
                        : R.string.install_confirm_question_update;
                //显示权限列表的ScrollView控件
                mScrollView = new CaffeinatedScrollView(this);
                mScrollView.setFillViewport(true);
                //当安装的应用已经存在时(更新应用时),获取是否有额外的权限请求 AppSecurityPermissions.WHICH_NEW
                boolean newPermissionsFound =
                        (perms.getPermissionCount(AppSecurityPermissions.WHICH_NEW) > 0);
                mInstallFlowAnalytics.setNewPermissionsFound(newPermissionsFound);
                if (newPermissionsFound) {
                    permVisible = true;
                    mScrollView.addView(perms.getPermissionsView(
                            AppSecurityPermissions.WHICH_NEW));
                } else {
                    LayoutInflater inflater = (LayoutInflater)getSystemService(
                            Context.LAYOUT_INFLATER_SERVICE);
                    TextView label = (TextView)inflater.inflate(R.layout.label, null);
                    label.setText(R.string.no_new_perms);
                    mScrollView.addView(label);
                }
                adapter.addTab(tabHost.newTabSpec(TAB_ID_NEW).setIndicator(
                        getText(R.string.newPerms)), mScrollView);
            } else  {
                findViewById(R.id.tabscontainer).setVisibility(View.GONE);
                findViewById(R.id.divider).setVisibility(View.VISIBLE);
            }
            //将要安装的应用设置的权限请求数量大于0时，将设置的权限列表列出来;
            if (NP > 0 || ND > 0) {
                permVisible = true;
                LayoutInflater inflater = (LayoutInflater)getSystemService(
                        Context.LAYOUT_INFLATER_SERVICE);
                View root = inflater.inflate(R.layout.permissions_list, null);
                if (mScrollView == null) {
                    mScrollView = (CaffeinatedScrollView)root.findViewById(R.id.scrollview);
                }
                //向权限列表控件中添加 私有请求权限控件
                if (NP > 0) {
                    ((ViewGroup)root.findViewById(R.id.privacylist)).addView(
                            perms.getPermissionsView(AppSecurityPermissions.WHICH_PERSONAL));
                } else {//私有请求权限数量为0时，就将控件进行隐藏
                    root.findViewById(R.id.privacylist).setVisibility(View.GONE);
                }
                if (ND > 0) {
                    ((ViewGroup)root.findViewById(R.id.devicelist)).addView(
                            perms.getPermissionsView(AppSecurityPermissions.WHICH_DEVICE));
                } else {
                    root.findViewById(R.id.devicelist).setVisibility(View.GONE);
                }
                adapter.addTab(tabHost.newTabSpec(TAB_ID_ALL).setIndicator(
                        getText(R.string.allPerms)), root);
            }
        }
        mInstallFlowAnalytics.setPermissionsDisplayed(permVisible);
        if (!permVisible) {
            if (mAppInfo != null) {
                // This is an update to an application, but there are no
                // permissions at all.
                msg = (mAppInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        ? R.string.install_confirm_question_update_system_no_perms
                        : R.string.install_confirm_question_update_no_perms;
            } else {
                // This is a new application with no permissions.
                msg = R.string.install_confirm_question_no_perms;
            }
            tabHost.setVisibility(View.GONE);
            mInstallFlowAnalytics.setAllPermissionsDisplayed(false);
            mInstallFlowAnalytics.setNewPermissionsDisplayed(false);
            findViewById(R.id.filler).setVisibility(View.VISIBLE);
            findViewById(R.id.divider).setVisibility(View.GONE);
            mScrollView = null;
        }
        if (msg != 0) {
            ((TextView)findViewById(R.id.install_confirm_question)).setText(msg);
        }
        mInstallConfirm.setVisibility(View.VISIBLE);
        mOk = (Button)findViewById(R.id.ok_button);
        mCancel = (Button)findViewById(R.id.cancel_button);
        mOk.setOnClickListener(this);
        mCancel.setOnClickListener(this);
        if (mScrollView == null) {
            // There is nothing to scroll view, so the ok button is immediately
            // set to install.
            mOk.setText(R.string.install);
            mOkCanInstall = true;
        } else {
            //如果设置了权限列表，则当滚动到权限列表末尾时，显示“install”按钮,否则显示“next"下一步
            mScrollView.setFullScrollAction(new Runnable() {
                @Override
                public void run() {
                    mOk.setText(R.string.install);
                    mOkCanInstall = true;
                }
            });
        }
    }

    private void showDialogInner(int id) {
        // TODO better fix for this? Remove dialog so that it gets created again
        removeDialog(id);
        showDialog(id);
    }

    @Override
    public Dialog onCreateDialog(int id, Bundle bundle) {
        switch (id) {
        case DLG_UNKNOWN_APPS:
            return new AlertDialog.Builder(this)
                    .setTitle(R.string.unknown_apps_dlg_title)
                    .setMessage(R.string.unknown_apps_dlg_text)
                    .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            Log.i(TAG, "Finishing off activity so that user can navigate to settings manually");
                            finish();
                        }})
                    .setPositiveButton(R.string.settings, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            Log.i(TAG, "Launching settings");
                            launchSettingsAppAndFinish();
                        }
                    })
                    .setOnCancelListener(this)
                    .create(); 
        case DLG_PACKAGE_ERROR :
            return new AlertDialog.Builder(this)
                    .setTitle(R.string.Parse_error_dlg_title)
                    .setMessage(R.string.Parse_error_dlg_text)
                    .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            finish();
                        }
                    })
                    .setOnCancelListener(this)
                    .create();
        case DLG_OUT_OF_SPACE:
            // Guaranteed not to be null. will default to package name if not set by app
            CharSequence appTitle = mPm.getApplicationLabel(mPkgInfo.applicationInfo);
            String dlgText = getString(R.string.out_of_space_dlg_text, 
                    appTitle.toString());
            return new AlertDialog.Builder(this)
                    .setTitle(R.string.out_of_space_dlg_title)
                    .setMessage(dlgText)
                    .setPositiveButton(R.string.manage_applications, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            //launch manage applications
                            Intent intent = new Intent("android.intent.action.MANAGE_PACKAGE_STORAGE");
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);   
                            finish();
                        }
                    })
                    .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            Log.i(TAG, "Canceling installation");
                            finish();
                        }
                  })
                  .setOnCancelListener(this)
                  .create();
        case DLG_INSTALL_ERROR :
            // Guaranteed not to be null. will default to package name if not set by app
            CharSequence appTitle1 = mPm.getApplicationLabel(mPkgInfo.applicationInfo);
            String dlgText1 = getString(R.string.install_failed_msg,
                    appTitle1.toString());
            return new AlertDialog.Builder(this)
                    .setTitle(R.string.install_failed)
                    .setNeutralButton(R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            finish();
                        }
                    })
                    .setMessage(dlgText1)
                    .setOnCancelListener(this)
                    .create();
        case DLG_ALLOW_SOURCE:
            CharSequence appTitle2 = mPm.getApplicationLabel(mSourceInfo);
            String dlgText2 = getString(R.string.allow_source_dlg_text,
                    appTitle2.toString());
            return new AlertDialog.Builder(this)
                    .setTitle(R.string.allow_source_dlg_title)
                    .setMessage(dlgText2)
                    .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            setResult(RESULT_CANCELED);
                            finish();
                        }})
                    .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            SharedPreferences prefs = getSharedPreferences(PREFS_ALLOWED_SOURCES,
                                    Context.MODE_PRIVATE);
                            prefs.edit().putBoolean(mSourceInfo.packageName, true).apply();
                            startInstallConfirm();
                        }
                    })
                    .setOnCancelListener(this)
                    .create();
       }
       return null;
   }

    private void launchSettingsAppAndFinish() {
        // Create an intent to launch SettingsTwo activity
        Intent launchSettingsIntent = new Intent(Settings.ACTION_SECURITY_SETTINGS);
        launchSettingsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launchSettingsIntent);
        finish();
    }

    //并不需要再Manifest.xml文件中设置任何权限，但必须对APK文件进行系统签名。
    // 也就是说使用isInstallingUnknownAppsallowed方法的ape程序必须与Android源代码一起编译
    private boolean isInstallingUnknownAppsAllowed() {
        UserManager um = (UserManager) getSystemService(USER_SERVICE);

        boolean disallowedByUserManager = um.getUserRestrictions()
                .getBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, false);
        boolean allowedBySecureSettings = Settings.Secure.getInt(getContentResolver(),
            Settings.Secure.INSTALL_NON_MARKET_APPS, 0) > 0;

        return (allowedBySecureSettings && (!disallowedByUserManager));
    }

    private boolean isInstallRequestFromUnknownSource(Intent intent) {
        String callerPackage = getCallingPackage();
        if (callerPackage != null && intent.getBooleanExtra(
                Intent.EXTRA_NOT_UNKNOWN_SOURCE, false)) {
            try {
                mSourceInfo = mPm.getApplicationInfo(callerPackage, 0);
                if (mSourceInfo != null) {
                    if ((mSourceInfo.flags & ApplicationInfo.FLAG_PRIVILEGED) != 0) {
                        // Privileged apps are not considered an unknown source.
                        return false;
                    }
                }
            } catch (NameNotFoundException e) {
            }
        }

        return true;
    }

    private boolean isVerifyAppsEnabled() {
        UserManager um = (UserManager) getSystemService(USER_SERVICE);
        if (um.hasUserRestriction(UserManager.ENSURE_VERIFY_APPS)) {
            return true;
        }
        return Settings.Global.getInt(getContentResolver(),
                Settings.Global.PACKAGE_VERIFIER_ENABLE, 1) > 0;
    }

    private boolean isAppVerifierInstalled() {
        final PackageManager pm = getPackageManager();
        final Intent verification = new Intent(Intent.ACTION_PACKAGE_NEEDS_VERIFICATION);
        verification.setType(PACKAGE_MIME_TYPE);
        verification.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        final List<ResolveInfo> receivers = pm.queryBroadcastReceivers(verification, 0);
        return (receivers.size() > 0) ? true : false;
    }

    private void initiateInstall() {
        String pkgName = mPkgInfo.packageName;
        // Check if there is already a package on the device with this name
        // but it has been renamed to something else.
        String[] oldName = mPm.canonicalToCurrentPackageNames(new String[] { pkgName });
        if (oldName != null && oldName.length > 0 && oldName[0] != null) {
            pkgName = oldName[0];
            mPkgInfo.packageName = pkgName;
            mPkgInfo.applicationInfo.packageName = pkgName;
        }
        // Check if package is already installed. display confirmation dialog if replacing pkg
        try {
            // This is a little convoluted because we want to get all uninstalled
            // apps, but this may include apps with just data, and if it is just
            // data we still want to count it as "installed".
            mAppInfo = mPm.getApplicationInfo(pkgName,
                    PackageManager.GET_UNINSTALLED_PACKAGES);
            if ((mAppInfo.flags&ApplicationInfo.FLAG_INSTALLED) == 0) {
                mAppInfo = null;
            }
        } catch (NameNotFoundException e) {
            mAppInfo = null;
        }

        mInstallFlowAnalytics.setReplace(mAppInfo != null);
        mInstallFlowAnalytics.setSystemApp(
                (mAppInfo != null) && ((mAppInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0));

        startInstallConfirm();
    }

    void setPmResult(int pmResult) {
        Intent result = new Intent();
        result.putExtra(Intent.EXTRA_INSTALL_RESULT, pmResult);
        setResult(pmResult == PackageManager.INSTALL_SUCCEEDED
                ? RESULT_OK : RESULT_FIRST_USER, result);
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mPm = getPackageManager();
        mInstaller = mPm.getPackageInstaller();

        final Intent intent = getIntent();
        if (PackageInstaller.ACTION_CONFIRM_PERMISSIONS.equals(intent.getAction())) {
            final int sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
            final PackageInstaller.SessionInfo info = mInstaller.getSessionInfo(sessionId);
            if (info == null || !info.sealed || info.resolvedBaseCodePath == null) {
                Log.w(TAG, "Session " + mSessionId + " in funky state; ignoring");
                finish();
                return;
            }
            mSessionId = sessionId;
            //获取待安装Android应用的路径或Package
            mPackageURI = Uri.fromFile(new File(info.resolvedBaseCodePath));
            mOriginatingURI = null;
            mReferrerURI = null;
        } else {
            mSessionId = -1;
            mPackageURI = intent.getData();
            mOriginatingURI = intent.getParcelableExtra(Intent.EXTRA_ORIGINATING_URI);
            mReferrerURI = intent.getParcelableExtra(Intent.EXTRA_REFERRER);
        }

        boolean requestFromUnknownSource = isInstallRequestFromUnknownSource(intent);
        mInstallFlowAnalytics = new InstallFlowAnalytics();
        mInstallFlowAnalytics.setContext(this);
        mInstallFlowAnalytics.setStartTimestampMillis(SystemClock.elapsedRealtime());
        mInstallFlowAnalytics.setInstallsFromUnknownSourcesPermitted(
                isInstallingUnknownAppsAllowed());
        mInstallFlowAnalytics.setInstallRequestFromUnknownSource(requestFromUnknownSource);
        mInstallFlowAnalytics.setVerifyAppsEnabled(isVerifyAppsEnabled());
        mInstallFlowAnalytics.setAppVerifierInstalled(isAppVerifierInstalled());
        mInstallFlowAnalytics.setPackageUri(mPackageURI.toString());

        final String scheme = mPackageURI.getScheme();
        //从此处可以看到，scheme只有两个值：file或package，为空呢
        if (scheme != null && !"file".equals(scheme) && !"package".equals(scheme)) {
            Log.w(TAG, "Unsupported scheme " + scheme);
            setPmResult(PackageManager.INSTALL_FAILED_INVALID_URI);
            mInstallFlowAnalytics.setFlowFinished(
                    InstallFlowAnalytics.RESULT_FAILED_UNSUPPORTED_SCHEME);
            finish();
            return;
        }

        final PackageUtil.AppSnippet as;
        //scheme是package时
        if ("package".equals(mPackageURI.getScheme())) {
            mInstallFlowAnalytics.setFileUri(false);
            try {
                //获取与package对应的Android应用的信息,包含应用名称,权限列表，应用图标等信息
                mPkgInfo = mPm.getPackageInfo(mPackageURI.getSchemeSpecificPart(),
                        PackageManager.GET_PERMISSIONS | PackageManager.GET_UNINSTALLED_PACKAGES);
            } catch (NameNotFoundException e) {
            }
            if (mPkgInfo == null) {
                Log.w(TAG, "Requested package " + mPackageURI.getScheme()
                        + " not available. Discontinuing installation");
                showDialogInner(DLG_PACKAGE_ERROR);
                setPmResult(PackageManager.INSTALL_FAILED_INVALID_APK);
                mInstallFlowAnalytics.setPackageInfoObtained();
                mInstallFlowAnalytics.setFlowFinished(
                        InstallFlowAnalytics.RESULT_FAILED_PACKAGE_MISSING);
                return;
            }
            //创建AppSnippet对象。该对象封装了用于待安装Android应用的标题和图标
            as = new PackageUtil.AppSnippet(mPm.getApplicationLabel(mPkgInfo.applicationInfo),
                    mPm.getApplicationIcon(mPkgInfo.applicationInfo));
        } else {
            //scheme为file的情况，或者scheme为空，及从apk文件安装程序
            mInstallFlowAnalytics.setFileUri(true);
            //获取APK文件的绝对路径
            final File sourceFile = new File(mPackageURI.getPath());
            //创建APK文件的分析器
            PackageParser.Package parsed = PackageUtil.getPackageInfo(sourceFile);

            // Check for parse errors
            if (parsed == null) {
                Log.w(TAG, "Parse error when parsing manifest. Discontinuing installation");
                showDialogInner(DLG_PACKAGE_ERROR);
                setPmResult(PackageManager.INSTALL_FAILED_INVALID_APK);
                mInstallFlowAnalytics.setPackageInfoObtained();
                mInstallFlowAnalytics.setFlowFinished(
                        InstallFlowAnalytics.RESULT_FAILED_TO_GET_PACKAGE_INFO);
                return;
            }
            mPkgInfo = PackageParser.generatePackageInfo(parsed, null,
                    PackageManager.GET_PERMISSIONS, 0, 0, null,
                    new PackageUserState());
            mPkgDigest = parsed.manifestDigest;
            as = PackageUtil.getAppSnippet(this, mPkgInfo.applicationInfo, sourceFile);
        }
        mInstallFlowAnalytics.setPackageInfoObtained();
        
        //set view
        setContentView(R.layout.install_start);
        mInstallConfirm = findViewById(R.id.install_confirm_panel);
        mInstallConfirm.setVisibility(View.INVISIBLE);
        PackageUtil.initSnippetForNewApp(this, as, R.id.app_snippet);

        mOriginatingUid = getOriginatingUid(intent);

        // 未知源安装检测
        if ((requestFromUnknownSource) && (!isInstallingUnknownAppsAllowed())) {
            //ask user to enable setting first
            showDialogInner(DLG_UNKNOWN_APPS);
            mInstallFlowAnalytics.setFlowFinished(
                    InstallFlowAnalytics.RESULT_BLOCKED_BY_UNKNOWN_SOURCES_SETTING);
            return;
        }
        //为安装应用做一些准备工作
        initiateInstall();
    }

    /** Get the ApplicationInfo for the calling package, if available */
    private ApplicationInfo getSourceInfo() {
        String callingPackage = getCallingPackage();
        if (callingPackage != null) {
            try {
                return mPm.getApplicationInfo(callingPackage, 0);
            } catch (NameNotFoundException ex) {
                // ignore
            }
        }
        return null;
    }


    /** Get the originating uid if possible, or VerificationParams.NO_UID if not available */
    private int getOriginatingUid(Intent intent) {
        // The originating uid from the intent. We only trust/use this if it comes from a
        // system application
        int uidFromIntent = intent.getIntExtra(Intent.EXTRA_ORIGINATING_UID,
                VerificationParams.NO_UID);

        // Get the source info from the calling package, if available. This will be the
        // definitive calling package, but it only works if the intent was started using
        // startActivityForResult,
        ApplicationInfo sourceInfo = getSourceInfo();
        if (sourceInfo != null) {
            if (uidFromIntent != VerificationParams.NO_UID &&
                    (mSourceInfo.flags & ApplicationInfo.FLAG_PRIVILEGED) != 0) {
                return uidFromIntent;

            }
            // We either didn't get a uid in the intent, or we don't trust it. Use the
            // uid of the calling package instead.
            return sourceInfo.uid;
        }

        // We couldn't get the specific calling package. Let's get the uid instead
        int callingUid;
        try {
            callingUid = ActivityManagerNative.getDefault()
                    .getLaunchedFromUid(getActivityToken());
        } catch (android.os.RemoteException ex) {
            Log.w(TAG, "Could not determine the launching uid.");
            // nothing else we can do
            return VerificationParams.NO_UID;
        }

        // If we got a uid from the intent, we need to verify that the caller is a
        // privileged system package before we use it
        if (uidFromIntent != VerificationParams.NO_UID) {
            String[] callingPackages = mPm.getPackagesForUid(callingUid);
            if (callingPackages != null) {
                for (String packageName: callingPackages) {
                    try {
                        ApplicationInfo applicationInfo =
                                mPm.getApplicationInfo(packageName, 0);

                        if ((applicationInfo.flags & ApplicationInfo.FLAG_PRIVILEGED) != 0) {
                            return uidFromIntent;
                        }
                    } catch (NameNotFoundException ex) {
                        // ignore it, and try the next package
                    }
                }
            }
        }
        // We either didn't get a uid from the intent, or we don't trust it. Use the
        // calling uid instead.
        return callingUid;
    }

    @Override
    public void onBackPressed() {
        if (mSessionId != -1) {
            mInstaller.setPermissionsResult(mSessionId, false);
        }
        mInstallFlowAnalytics.setFlowFinished(
                InstallFlowAnalytics.RESULT_CANCELLED_BY_USER);
        super.onBackPressed();
    }

    // Generic handling when pressing back key
    public void onCancel(DialogInterface dialog) {
        finish();
    }

    public void onClick(View v) {
        //单击“next/ok“按钮浏览权限列表
        if(v == mOk) {
            //已经浏览完所有权限 “next"按钮已经变成了“ok”按钮，已经准备好可以显示安装口
            if (mOkCanInstall || mScrollView == null) {
                mInstallFlowAnalytics.setInstallButtonClicked();
                if (mSessionId != -1) {
                    mInstaller.setPermissionsResult(mSessionId, true);

                    // We're only confirming permissions, so we don't really know how the
                    // story ends; assume success.
                    mInstallFlowAnalytics.setFlowFinishedWithPackageManagerResult(
                            PackageManager.INSTALL_SUCCEEDED);
                } else {
                    // Start subactivity to actually install the application
                    //构造开启安装程序的Intent对象
                    Intent newIntent = new Intent();
                    newIntent.putExtra(PackageUtil.INTENT_ATTR_APPLICATION_INFO,
                            mPkgInfo.applicationInfo);
                    newIntent.setData(mPackageURI);
                    //将要显示的Activity类界面
                    newIntent.setClass(this, InstallAppProgress.class);
                    newIntent.putExtra(InstallAppProgress.EXTRA_MANIFEST_DIGEST, mPkgDigest);
                    newIntent.putExtra(
                            InstallAppProgress.EXTRA_INSTALL_FLOW_ANALYTICS, mInstallFlowAnalytics);
                    String installerPackageName = getIntent().getStringExtra(
                            Intent.EXTRA_INSTALLER_PACKAGE_NAME);
                    if (mOriginatingURI != null) {
                        newIntent.putExtra(Intent.EXTRA_ORIGINATING_URI, mOriginatingURI);
                    }
                    if (mReferrerURI != null) {
                        newIntent.putExtra(Intent.EXTRA_REFERRER, mReferrerURI);
                    }
                    if (mOriginatingUid != VerificationParams.NO_UID) {
                        newIntent.putExtra(Intent.EXTRA_ORIGINATING_UID, mOriginatingUid);
                    }
                    if (installerPackageName != null) {
                        newIntent.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME,
                                installerPackageName);
                    }
                    if (getIntent().getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false)) {
                        newIntent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                        newIntent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
                    }
                    if(localLOGV) Log.i(TAG, "downloaded app uri="+mPackageURI);
                    startActivity(newIntent);
                }
                finish();
            } else {
                mScrollView.pageScroll(View.FOCUS_DOWN);
            }
        } else if(v == mCancel) {
            // Cancel and finish
            setResult(RESULT_CANCELED);
            if (mSessionId != -1) {
                mInstaller.setPermissionsResult(mSessionId, false);
            }
            mInstallFlowAnalytics.setFlowFinished(
                    InstallFlowAnalytics.RESULT_CANCELLED_BY_USER);
            finish();
        }
    }
}
