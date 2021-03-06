/*
 * Part of Phonk http://www.phonk.io
 * A prototyping platform for Android devices
 *
 * Copyright (C) 2013 - 2017 Victor Diaz Barrales @victordiaz (Protocoder)
 * Copyright (C) 2017 - Victor Diaz Barrales @victordiaz (Phonk)
 *
 * Phonk is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Phonk is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Phonk. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package io.phonk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.view.ContextThemeWrapper;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnticipateOvershootInterpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.FragmentManager;
import androidx.viewpager.widget.ViewPager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import io.phonk.appinterpreter.AppRunnerCustom;
import io.phonk.appinterpreter.PhonkApp;
import io.phonk.events.Events;
import io.phonk.gui.CombinedFolderAndProjectFragment;
import io.phonk.gui.ConnectionInfoFragment;
import io.phonk.gui.EmptyFragment;
import io.phonk.gui.SectionsPagerAdapter;
import io.phonk.gui._components.APIWebviewFragment;
import io.phonk.gui._components.NewProjectDialogFragment;
import io.phonk.gui.folderchooser.FolderListFragment;
import io.phonk.gui.projectlist.ProjectListFragment;
import io.phonk.gui.settings.PhonkSettings;
import io.phonk.gui.settings.UserPreferences;
import io.phonk.helpers.PhonkAppHelper;
import io.phonk.helpers.PhonkScriptHelper;
import io.phonk.runner.apprunner.AppRunnerHelper;
import io.phonk.runner.apprunner.api.other.PDelay;
import io.phonk.runner.base.BaseActivity;
import io.phonk.runner.base.models.Project;
import io.phonk.runner.base.network.NetworkUtils;
import io.phonk.runner.base.utils.AndroidUtils;
import io.phonk.runner.base.utils.MLog;
import io.phonk.server.PhonkServerService;

public class MainActivity extends BaseActivity {

    private static final java.lang.String TAG = MainActivity.class.getSimpleName();

    private AppRunnerCustom mAppRunner;

    private Intent mServerIntent;

    private int mCurrentPagerPosition;
    private SectionsPagerAdapter mSectionsPagerAdapter;
    private ViewPager mViewPager;
    private RelativeLayout mHeader;
    private RelativeLayout mLaunchScreenLogo;

    private ImageButton mToggleConnectionInfo;
    private RelativeLayout mConnectionInfo;

    private EmptyFragment mEmptyFragment;
    private FolderListFragment mFolderListFragment;
    private ProjectListFragment mProjectListFragment;
    private ConnectionInfoFragment mConnectionInfoFragment;
    private CombinedFolderAndProjectFragment mCombinedFolderAndProjectFragment;
    private APIWebviewFragment mWebViewFragment;

    private boolean mIsTablet = true;
    private boolean mIsLandscapeBig = false;
    private boolean isWebIdeMode = false;

    private boolean mUiInit = false;
    private PDelay mDelay;

    private boolean alreadyStartedServices = false;
    private boolean isConfigChanging = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // PhonkAppHelper.launchSchedulerList(this);
        EventBus.getDefault().register(this);

        UserPreferences.getInstance().load();
        isWebIdeMode = (boolean) UserPreferences.getInstance().get("webide_mode");

        if (savedInstanceState != null) {
           alreadyStartedServices = savedInstanceState.getBoolean("alreadyStartedServices", false);
        }

        mAppRunner = new AppRunnerCustom(this);
        mAppRunner.initDefaultObjects(AppRunnerHelper.createSettings()).initInterpreter();
        // PhonkApp phonkApp = new PhonkApp(mAppRunner);
        // phonkApp.network.checkVersion();
        // mAppRunner.interp.eval("device.vibrate(100);");

        // startServers if conf specifies. In webidemode always have to start it
        MLog.d(TAG, "isWebIdeMode " + isWebIdeMode);
        if (isWebIdeMode) startServers();

        if (alreadyStartedServices) {
            loadUI(1);
        } else {
            loadUI(0);
            mDelay = mAppRunner.pUtil.delay(3000, () -> mViewPager.setCurrentItem(1));
        }
        setScreenAlwaysOn((boolean) UserPreferences.getInstance().get("screen_always_on"));

        // execute onLaunch script
        String script = (String) UserPreferences.getInstance().get("launch_script_on_app_launch");
        if (!script.isEmpty()) {
            Project p = new Project(script);
            PhonkAppHelper.launchScript(this, p);
        }

        // PhonkAppHelper.launchScript(this, new Project("playground/User Projects/grid"));
        // PhonkAppHelper.launchScript(this, new Project("examples/Graphical User Interface/Basic Views"));
        // PhonkAppHelper.launchScript(this, new Project("examples/Graphical User Interface/Extra Views"));
    }

    @Override
    protected void onResume() {
        super.onResume();

        startBroadCastReceiver();

        // send appIsClosed
        Intent i = new Intent("io.phonk.intent.CLOSED");
        sendBroadcast(i);
        registerReceiver(connectivityChangeReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(adbBroadcastReceiver);
        unregisterReceiver(connectivityChangeReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        if (mDelay != null) mDelay.stop();
        mAppRunner.byebye();

        if (!isConfigChanging) stopServers();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        MLog.d(TAG, "alreadyStartedChanging");
        outState.putBoolean("alreadyStartedServices", true);
        isConfigChanging = true;
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        MLog.d(TAG, "alreadyRestoring");
    }

    /*
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        MLog.d(TAG, "changing conf");
        removeFragment(mConnectionInfoFragment);
        removeFragment(mEmptyFragment);
        removeFragment(mProjectListFragment);
        removeFragment(mFolderListFragment);
        if (mCombinedFolderAndProjectFragment != null)
            removeFragment(mCombinedFolderAndProjectFragment);
        RelativeLayout mainContent = findViewById(R.id.main_content);
        mainContent.removeAllViews();
        super.onConfigurationChanged(newConfig);
        loadUI(1);
    }
     */

    private void loadUI(int toPage) {
        // load UI
        setContentView(R.layout.main_activity);

        // Show PHONK version on load
        TextView txtPhonkVersion = findViewById(R.id.phonkVersion);
        txtPhonkVersion.setText(BuildConfig.VERSION_NAME);

        mIsTablet = getResources().getBoolean(R.bool.isTablet);
        mIsLandscapeBig = getResources().getBoolean(R.bool.isLandscapeBig);

        if (!mUiInit) {
            mEmptyFragment = EmptyFragment.newInstance();

            mFolderListFragment = FolderListFragment.newInstance(PhonkSettings.EXAMPLES_FOLDER, true);
            mProjectListFragment = ProjectListFragment.newInstance("", true);
            mConnectionInfoFragment = ConnectionInfoFragment.newInstance();

            /*
            if (mIsTablet) {
                mCombinedFolderAndProjectFragment = CombinedFolderAndProjectFragment.newInstance(mFolderListFragment, mProjectListFragment);
                mSectionsPagerAdapter = new SectionsPagerAdapter(getSupportFragmentManager(), mEmptyFragment, mCombinedFolderAndProjectFragment);
            } else {
            }
            */

            mSectionsPagerAdapter = new SectionsPagerAdapter(getSupportFragmentManager(), mEmptyFragment, mFolderListFragment, mProjectListFragment);
            mUiInit = true;
        }
        addFragment(mConnectionInfoFragment, R.id.infoLayout, false);

        mHeader = findViewById(R.id.header);
        mLaunchScreenLogo = findViewById(R.id.appintro);

        if (alreadyStartedServices) {
            mLaunchScreenLogo.setAlpha(0);
        } else {
            AnimationSet set = new AnimationSet(true);

            Animation anim = new ScaleAnimation(0.2f, 1f, 0.2f, 1f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
            Animation animT = new TranslateAnimation(0, 0, 100, 0);
            Animation animO = new AlphaAnimation(0, 1);

            set.addAnimation(anim);
            set.addAnimation(animT);
            set.addAnimation(animO);
            set.setInterpolator(new AnticipateOvershootInterpolator());
            set.setDuration(800);
            set.setStartOffset(100);
            // set.setFillAfter(true);
            mLaunchScreenLogo.startAnimation(set);
        }


        mConnectionInfo = findViewById(R.id.ip_container);

        mToggleConnectionInfo = findViewById(R.id.toggleConnectionInfo);
        mToggleConnectionInfo.setOnClickListener(view -> {
            if (mConnectionInfo.getVisibility() == View.GONE)
                mConnectionInfo.setVisibility(View.VISIBLE);
            else mConnectionInfo.setVisibility(View.GONE);
        });

        // Set up the ViewPager with the sections adapter.
        mViewPager = findViewById(R.id.container);
        mViewPager.setOffscreenPageLimit(2);
        mViewPager.setAdapter(mSectionsPagerAdapter);
        mViewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                if (positionOffset > 0.1) {
                    if (mDelay != null) mDelay.stop();
                }

                if (position == 0) {
                    // MLog.d(TAG, position + " " + positionOffset + " " + positionOffsetPixels);
                    mLaunchScreenLogo.setAlpha(1 - positionOffset);

                    float scale = positionOffset / 5.0f;
                    mLaunchScreenLogo.setScaleX(1 - scale);
                    mLaunchScreenLogo.setScaleY(1 - scale);

                    mHeader.setAlpha(positionOffset);
                }
            }

            @Override
            public void onPageSelected(int position) {
                mCurrentPagerPosition = position;
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                // MLog.d("selected", "state " + state + " " + mCurrentPagerPosition);
            }
        });
        mViewPager.setCurrentItem(toPage);

        final ImageButton moreOptionsButton = findViewById(R.id.more_options);
        moreOptionsButton.setOnClickListener(view -> {
            Context wrapper = new ContextThemeWrapper(MainActivity.this, R.style.phonk_PopupMenu);
            PopupMenu myPopup = new PopupMenu(wrapper, moreOptionsButton);
            myPopup.inflate(R.menu.more_options);
            myPopup.setOnMenuItemClickListener(menuItem -> {
                int itemId = menuItem.getItemId();

                if (itemId == R.id.more_options_new) {
                    PhonkAppHelper.newProjectDialog(MainActivity.this);
                    return true;
                } else if (itemId == R.id.more_options_settings) {
                    PhonkAppHelper.launchSettings(MainActivity.this);
                    return true;
                } else if (itemId == R.id.more_options_help) {
                    PhonkAppHelper.launchHelp(MainActivity.this);
                    return true;
                } else if (itemId == R.id.more_options_about) {
                    PhonkAppHelper.launchHelp(MainActivity.this);
                    return true;
                }

                return false;
            });

            myPopup.show();
        });
    }

    public void loadWebIde() {
        MLog.d(TAG, "loadWebIde");

        if (mWebViewFragment != null) return;

        FrameLayout fl = findViewById(R.id.fragmentEditor);
        fl.setVisibility(View.VISIBLE);
        MLog.d(TAG, "using webide");
        mWebViewFragment = new APIWebviewFragment();

        Bundle bundle = new Bundle();
        String url = "http://127.0.0.1:8585";
        // String url = "http://10.0.2.2:8080";
        bundle.putString("url", url);
        bundle.putBoolean("isTablet", mIsTablet);
        mWebViewFragment.setArguments(bundle);

        addFragment(mWebViewFragment, R.id.fragmentEditor, "qq");

        // mWebViewFragment.webView.loadUrl(url);
    }

    public void createProjectDialog() {
        FragmentManager fm = getSupportFragmentManager();
        NewProjectDialogFragment newProjectDialog = new NewProjectDialogFragment();
        newProjectDialog.show(fm, "fragment_edit_name");

        String[] templates = PhonkScriptHelper.listTemplates(this);
        for (String template : templates) {
            MLog.d(TAG, "template " + template);
        }

        newProjectDialog.setListener(inputText -> {
            String template = "default";
            Toast.makeText(MainActivity.this, "Creating " + inputText, Toast.LENGTH_SHORT).show();
            Project p = PhonkScriptHelper.createNewProject(MainActivity.this, template, "user_projects/User Projects/", inputText);
            EventBus.getDefault().post(new Events.ProjectEvent(Events.PROJECT_NEW, p));
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) return true;
        return super.onOptionsItemSelected(item);
    }

    /*
     * This broadcast will receive JS commands if is in debug mode, useful to debug the app through adb
     */
    BroadcastReceiver adbBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String cmd = intent.getStringExtra("cmd");
            MLog.d(TAG, "executing >> " + cmd);
            // mAppRunner.interp.eval(cmd);
        }
    };

    private void startBroadCastReceiver() {
        if (PhonkSettings.DEBUG) {
            //execute commands from intents
            //ie: adb shell am broadcast -a io.phonk.intent.EXECUTE --es cmd "device.vibrate(100)"

            IntentFilter filterSend = new IntentFilter();
            filterSend.addAction("io.phonk.intent.EXECUTE");
            registerReceiver(adbBroadcastReceiver, filterSend);
        }
    }

    /*
     * Server
     */
    private void startServers() {
        MLog.d(TAG, "starting servers");
        mServerIntent = new Intent(this, PhonkServerService.class);
        //serverIntent.putExtra(Project.FOLDER, folder);
        startService(mServerIntent);
    }

    private void stopServers() {
        stopService(mServerIntent);
    }

    // execute lines
    @Subscribe
    public void onEventMainThread(Events.ExecuteCodeEvent e) {
        String code = e.getCode();
        MLog.d(TAG, "connect -> " + code);

        if (PhonkSettings.DEBUG) {
            // mAppRunner.interp.eval(code);
        }
    }

    @Subscribe
    public void onEventMainThread(Events.ProjectEvent e) {
        if (e.getAction().equals(Events.CLOSE_APP)) {
            MLog.d(TAG, "closing app (not implemented)");
        }
    }

    // folder choose
    @Subscribe
    public void onEventMainThread(Events.FolderChosen e) {
        MLog.d(TAG, "< Event (folderChosen)");
        mViewPager.setCurrentItem(2, true);
    }

    @Subscribe
    public void onEventMainThread(Events.AppUiEvent e) {
        String action = e.getAction();
        Object value = e.getValue();
        MLog.d(TAG, "got AppUiEvent " + action);

        switch (action) {
            case "page":
                mViewPager.setCurrentItem((int) value);
                break;
            case "stopServers":
                stopServers();
                break;
            case "startServers":
                if (!alreadyStartedServices)
                    startServers();
                break;
            case "serversStarted":
                // show webview
                if (isWebIdeMode) loadWebIde();
                break;
            case "recreate":
                // recreate();
                // finish();
                break;
        }
    }

    /*
     * Network Connectivity listener
     */
    BroadcastReceiver connectivityChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            MLog.d(TAG, "connectivity changed");
            AndroidUtils.debugIntent("connectivityChangerReceiver", intent);

            // check if there is a WIFI connection or we can connect via USB
            if (NetworkUtils.getLocalIpAddress(MainActivity.this).get("ip").equals("127.0.0.1")) {
                MLog.d(TAG, "No WIFI, still you can hack via USB using the adb command");
                EventBus.getDefault().post(new Events.Connection("none", ""));
            } else {
                MLog.d(TAG, "Hack via your browser @ http://" + NetworkUtils.getLocalIpAddress(MainActivity.this) + ":" + PhonkSettings.HTTP_PORT);
                String ip = NetworkUtils.getLocalIpAddress(MainActivity.this).get("ip") + ":" + PhonkSettings.HTTP_PORT;
                String type = (String) NetworkUtils.getLocalIpAddress(MainActivity.this).get("type");
                EventBus.getDefault().post(new Events.Connection(type, ip));
            }
        }
    };

    @Override
    public void onBackPressed() {
        if (mCurrentPagerPosition == 2) {
            mViewPager.setCurrentItem(1, true);
        } else {
            super.onBackPressed();
        }
    }
}
