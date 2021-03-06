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

import com.google.android.apps.forscience.javalib.Consumer;
import com.google.android.apps.forscience.javalib.FailureListener;
import com.google.android.apps.forscience.whistlepunk.ExternalSensorProvider;
import com.google.android.apps.forscience.whistlepunk.metadata.ExternalSensorSpec;
import com.google.android.apps.forscience.whistlepunk.sensorapi.SensorChoice;

class StubSensorDiscoverer implements ExternalSensorDiscoverer {
    @Override
    public boolean startScanning(
            Consumer<DiscoveredSensor> onEachSensorFound,
            Runnable onScanDone, FailureListener onScanError) {
        return false;
    }

    @Override
    public void stopScanning() {

    }

    @Override
    public ExternalSensorProvider getProvider() {
        return new ExternalSensorProvider() {
            @Override
            public SensorChoice buildSensor(String sensorId, ExternalSensorSpec spec) {
                return null;
            }

            @Override
            public String getProviderId() {
                return null;
            }

            @Override
            public ExternalSensorSpec buildSensorSpec(String name, byte[] config) {
                return null;
            }
        };
    }
}
