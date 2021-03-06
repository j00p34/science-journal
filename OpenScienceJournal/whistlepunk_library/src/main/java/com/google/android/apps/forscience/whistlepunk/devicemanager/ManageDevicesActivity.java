/*
 *  Copyright 2016 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.google.android.apps.forscience.whistlepunk.devicemanager;

import android.Manifest;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.Menu;

import com.google.android.apps.forscience.javalib.Success;
import com.google.android.apps.forscience.whistlepunk.AppSingleton;
import com.google.android.apps.forscience.whistlepunk.DataController;
import com.google.android.apps.forscience.whistlepunk.DevOptionsFragment;
import com.google.android.apps.forscience.whistlepunk.LoggingConsumer;
import com.google.android.apps.forscience.whistlepunk.R;
import com.google.android.apps.forscience.whistlepunk.WhistlePunkApplication;
import com.google.android.apps.forscience.whistlepunk.analytics.TrackerConstants;
import com.google.android.apps.forscience.whistlepunk.metadata.Experiment;


public class ManageDevicesActivity extends AppCompatActivity implements
        DeviceOptionsDialog.DeviceOptionsListener {

    private static final String TAG = "ManageDevices";

    /**
     * String extra which stores the experiment ID that launched this activity.
     */
    public static final String EXTRA_EXPERIMENT_ID = "experiment_id";

    private BroadcastReceiver mBtReceiver;
    private DataController mDataController;
    private ManageFragment mManageFragment;
    private Experiment mCurrentExperiment;

    public static DeviceOptionsDialog.DeviceOptionsListener getOptionsListener(Activity activity) {
        if (activity instanceof DeviceOptionsDialog.DeviceOptionsListener) {
            return (DeviceOptionsDialog.DeviceOptionsListener) activity;
        } else {
            return DeviceOptionsDialog.NULL_LISTENER;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_devices);
        mDataController = AppSingleton.getInstance(this).getDataController();
        if (!ScanDisabledFragment.hasScanPermission(this)
                && !ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.ACCESS_COARSE_LOCATION)) {
            ActivityCompat.requestPermissions(this,
                    new String[] {Manifest.permission.ACCESS_COARSE_LOCATION}, 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setFragment();
        // Set up a broadcast receiver in case the adapter is disabled from the notification shade.
        registerBtReceiverIfNecessary();
        final String experimentId = getIntent().getStringExtra(EXTRA_EXPERIMENT_ID);
        WhistlePunkApplication.getUsageTracker(this).trackScreenView(
                TrackerConstants.SCREEN_DEVICE_MANAGER);
        mDataController.getExperimentById(experimentId,
                new LoggingConsumer<Experiment>(TAG, "load experiment with ID = " + experimentId) {
                    @Override
                    public void success(Experiment value) {
                        mCurrentExperiment = value;
                    }
                });
    }

    @Override
    protected void onPause() {
        unregisterBtReceiverIfNecessary();
        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return true;
    }

    private void setFragment() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        Fragment fragment;
        if (adapter.isEnabled() && ScanDisabledFragment.hasScanPermission(this)) {
            if (DevOptionsFragment.shouldUseNewManageDevicesUx(this)) {
                fragment = new ManageDevicesRecyclerFragment();
            } else {
                fragment = new ManageDevicesFragment();
            }
            mManageFragment = (ManageFragment) fragment;
        } else {
            fragment = new ScanDisabledFragment();
            mManageFragment = null;
        }
        Bundle args = new Bundle();
        args.putString(EXTRA_EXPERIMENT_ID, getIntent().getStringExtra(EXTRA_EXPERIMENT_ID));
        fragment.setArguments(args);
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.replace(R.id.fragment, fragment);
        ft.commitAllowingStateLoss();
    }

    private void registerBtReceiverIfNecessary() {
        if (mBtReceiver == null) {
            mBtReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    setFragment();
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            registerReceiver(mBtReceiver, filter);
        }
    }

    private void unregisterBtReceiverIfNecessary() {
        if (mBtReceiver != null) {
            unregisterReceiver(mBtReceiver);
            mBtReceiver = null;
        }
    }

    @Override
    public void onExperimentSensorReplaced(String oldSensorId, String newSensorId) {
        refreshAfterLoad();
    }

    @Override
    public void onRemoveDeviceFromExperiment(String experimentId, final String sensorId) {
        if (mCurrentExperiment != null && mCurrentExperiment.getExperimentId().equals(
                experimentId)) {
            removeSensorFromExperiment(sensorId);
        }
    }

    private void removeSensorFromExperiment(String sensorId) {
        mDataController.removeSensorFromExperiment(mCurrentExperiment.getExperimentId(), sensorId,
                new LoggingConsumer<Success>(TAG, "remove sensor from experiment") {
                    @Override
                    public void success(Success value) {
                        refreshAfterLoad();
                    }
                });
    }

    private void refreshAfterLoad() {
        if (mManageFragment != null) {
            mManageFragment.refreshAfterLoad();
        }
    }
}
