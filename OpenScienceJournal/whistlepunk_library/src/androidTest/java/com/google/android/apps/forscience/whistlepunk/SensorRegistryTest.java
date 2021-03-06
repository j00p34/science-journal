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
package com.google.android.apps.forscience.whistlepunk;

import android.support.annotation.NonNull;

import com.google.android.apps.forscience.javalib.Consumer;
import com.google.android.apps.forscience.whistlepunk.devicemanager.NativeBleDiscoverer;
import com.google.android.apps.forscience.whistlepunk.metadata.BleSensorSpec;
import com.google.android.apps.forscience.whistlepunk.metadata.ExternalSensorSpec;
import com.google.android.apps.forscience.whistlepunk.sensorapi.SensorChoice;
import com.google.android.apps.forscience.whistlepunk.sensors.SineWavePseudoSensor;
import com.google.common.collect.ImmutableMap;

import java.util.HashMap;
import java.util.Map;

// TODO: make this a java-only unit test
public class SensorRegistryTest extends DevOptionsTestCase {
    @NonNull
    @Override
    protected String getRememberedPrefKey() {
        return DevOptionsFragment.KEY_SINE_WAVE_SENSOR;
    }

    public void testImmediatelyReset() {
        SensorRegistry reg = SensorRegistry.createWithBuiltinSensors(getContext());

        setPrefValue(false);
        reg.refreshBuiltinSensors(getContext());
        assertEquals(false, reg.getAllSources().contains(SineWavePseudoSensor.ID));

        setPrefValue(true);
        reg.refreshBuiltinSensors(getContext());
        assertEquals(true, reg.getAllSources().contains(SineWavePseudoSensor.ID));

        // double-check removal
        setPrefValue(false);
        reg.refreshBuiltinSensors(getContext());
        assertEquals(false, reg.getAllSources().contains(SineWavePseudoSensor.ID));
    }

    public void testDontClearExternalSensors() {
        SensorRegistry reg = SensorRegistry.createWithBuiltinSensors(getContext());

        Map<String, ExternalSensorSpec> sensors = new HashMap<>();
        String bleSensorId = "bleSensor1";
        sensors.put(bleSensorId, new BleSensorSpec("address", "name"));

        HashMap<String, ExternalSensorProvider> providers = new HashMap<>();
        providers.put(BleSensorSpec.TYPE, bleProvider());

        reg.updateExternalSensors(sensors, providers);
        assertEquals(true, reg.getAllSources().contains(bleSensorId));
        reg.refreshBuiltinSensors(getContext());
        assertEquals(true, reg.getAllSources().contains(bleSensorId));
    }

    public void testNotifyListener() {
        SensorRegistry reg = SensorRegistry.createWithBuiltinSensors(getContext());
        TestSensorRegistryListener listener = new TestSensorRegistryListener();
        assertEquals(0, listener.numRefreshes);
        reg.setSensorRegistryListener(listener);
        reg.refreshBuiltinSensors(getContext());
        assertEquals(1, listener.numRefreshes);
    }

    public void testLoggingId() {
        SensorRegistry reg = SensorRegistry.createWithBuiltinSensors(getContext());

        Map<String, ExternalSensorSpec> sensors = new HashMap<>();
        String bleSensorId = "aa:bb:cc:dd";
        sensors.put(bleSensorId, new BleSensorSpec(bleSensorId, "name"));

        HashMap<String, ExternalSensorProvider> providers = new HashMap<>();
        providers.put(BleSensorSpec.TYPE, bleProvider());

        reg.updateExternalSensors(sensors, providers);
        assertEquals(true, reg.getAllSources().contains(bleSensorId));

        assertEquals("bluetooth_le:raw", reg.getLoggingId(bleSensorId));
    }

    public void testDropWaitingOperations() {
        SensorRegistry reg = SensorRegistry.createWithBuiltinSensors(getContext());
        StoringConsumer cyes = new StoringConsumer();
        String tagyes = "yes";

        StoringConsumer cno = new StoringConsumer();
        String tagno = "no";

        String newSensorId = "newSensorId";
        reg.withSensorChoice(tagyes, newSensorId, cyes);
        reg.withSensorChoice(tagno, newSensorId, cno);
        reg.removePendingOperations(tagno);
        ExternalSensorSpec spec = new BleSensorSpec("address", "name");
        reg.updateExternalSensors(
                ImmutableMap.of(newSensorId, spec),
                ImmutableMap.<String, ExternalSensorProvider>of(BleSensorSpec.TYPE, bleProvider()));
        assertNull(cno.latestChoice);
        assertNotNull(cyes.latestChoice);
    }

    private ExternalSensorProvider bleProvider() {
        return new NativeBleDiscoverer(getContext()).getProvider();
    }

    private static class TestSensorRegistryListener implements SensorRegistryListener {
        public int numRefreshes = 0;

        @Override
        public void updateExternalSensors(Map<String, ExternalSensorSpec> sensors) {

        }

        @Override
        public void refreshBuiltinSensors() {
            numRefreshes++;
        }
    }

    private static class StoringConsumer extends Consumer<SensorChoice> {
        public SensorChoice latestChoice = null;

        @Override
        public void take(SensorChoice sensorChoice) {
            latestChoice = sensorChoice;
        }
    }
}