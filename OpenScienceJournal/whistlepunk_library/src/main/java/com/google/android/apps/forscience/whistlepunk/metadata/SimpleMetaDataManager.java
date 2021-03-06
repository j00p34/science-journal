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

package com.google.android.apps.forscience.whistlepunk.metadata;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.provider.BaseColumns;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.forscience.whistlepunk.Clock;
import com.google.android.apps.forscience.whistlepunk.CurrentTimeClock;
import com.google.android.apps.forscience.whistlepunk.ExternalSensorProvider;
import com.google.android.apps.forscience.whistlepunk.PictureUtils;
import com.google.android.apps.forscience.whistlepunk.ProtoUtils;
import com.google.android.apps.forscience.whistlepunk.R;
import com.google.android.apps.forscience.whistlepunk.data.GoosciSensorLayout;
import com.google.protobuf.nano.InvalidProtocolBufferNanoException;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * An implementation of the {@link MetaDataManager} which uses a simple database.
 */
public class SimpleMetaDataManager implements MetaDataManager {

    public static final int STABLE_EXPERIMENT_ID_LENGTH = 12;
    public static final int STABLE_PROJECT_ID_LENGTH = 6;
    private static final String TAG = "SimpleMetaDataManager";

    private DatabaseHelper mDbHelper;
    private Context mContext;
    private Clock mClock;
    private Object mLock = new Object();

    public void close() {
        mDbHelper.close();
    }

    /**
     * List of table names. NOTE: when adding a new table, make sure to delete the metadata in the
     * appropriate delete calls: {@link #deleteProject(Project)},
     * {@link #deleteExperiment(Experiment)}, {@link #deleteLabel(Label)},
     * {@link #deleteRun(String)}, {@link #deleteSensorTrigger(SensorTrigger)}, etc.
     */
    interface Tables {
        String PROJECTS = "projects";
        String EXPERIMENTS = "experiments";
        String LABELS = "labels";
        String EXTERNAL_SENSORS = "sensors";
        String EXPERIMENT_SENSORS = "experiment_sensors";
        String RUN_STATS = "run_stats";
        String RUNS = "runs";
        String RUN_SENSORS = "run_sensors";
        String EXPERIMENT_SENSOR_LAYOUT = "experiment_sensor_layout";
        String SENSOR_TRIGGERS = "sensor_triggers";
    }

    public SimpleMetaDataManager(Context context) {
        this(context, null /* default filename */, new CurrentTimeClock());
    }

    /* Visible for testing */ SimpleMetaDataManager(Context context, String filename, Clock clock) {
        mContext = context;
        mDbHelper = new DatabaseHelper(context, filename);
        mClock = clock;
    }

    @Override
    public Project getProjectById(String projectId) {
        Project project;

        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getReadableDatabase();
            final String selection = ExperimentColumns.PROJECT_ID + "=?";
            final String[] selectionArgs = new String[]{projectId};
            Cursor cursor = null;
            try {
                cursor = db.query(
                        Tables.PROJECTS, ProjectColumns.GET_COLUMNS, selection, selectionArgs,
                        null, null, null, "1");
                if (cursor == null || !cursor.moveToFirst()) {
                    return null;
                }
                project = createProjectFromCursor(cursor);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        return project;
    }

    private Project createProjectFromCursor(Cursor cursor) {
        Project project = new Project(cursor.getLong(0));
        project.setProjectId(cursor.getString(1));
        project.setTitle(cursor.getString(2));
        project.setCoverPhoto(cursor.getString(3));
        project.setArchived(cursor.getInt(4) == 1);
        project.setDescription(cursor.getString(5));
        project.setLastUsedTime(cursor.getLong(6));
        return project;
    }

    @Override
    public List<Project> getProjects(int maxNumber, boolean includeArchived) {
        List<Project> projects = new ArrayList<Project>();
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getReadableDatabase();

            String selection = ProjectColumns.ARCHIVED + "=?";
            String[] selectionArgs = new String[] {"0"};
            if (includeArchived) {
                selection = null;
                selectionArgs = null;
            }

            Cursor cursor = null;
            try {
                cursor = db.query(
                        Tables.PROJECTS, ProjectColumns.GET_COLUMNS, selection, selectionArgs,
                        null, null,
                        ProjectColumns.LAST_USED_TIME + " DESC, " + BaseColumns._ID + " DESC",
                        String.valueOf(maxNumber));
                while (cursor.moveToNext()) {
                    projects.add(createProjectFromCursor(cursor));
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return projects;
    }

    @Override
    public Project newProject() {
        String projectId = newStableId(STABLE_PROJECT_ID_LENGTH);
        ContentValues values = new ContentValues();
        values.put(ProjectColumns.PROJECT_ID, projectId);
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            long id = db.insert(Tables.PROJECTS, null, values);
            if (id != -1) {
                Project project = new Project(id);
                project.setProjectId(projectId);
                return project;
            }
        }
        return null;
    }

    @Override
    public void updateProject(Project project) {
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            final ContentValues values = new ContentValues();
            values.put(ProjectColumns.TITLE, project.getTitle());
            values.put(ProjectColumns.DESCRIPTION, project.getDescription());
            values.put(ProjectColumns.COVER_PHOTO, project.getCoverPhoto());
            values.put(ProjectColumns.ARCHIVED, project.isArchived());
            values.put(ProjectColumns.LAST_USED_TIME, project.getLastUsedTime());
            db.update(Tables.PROJECTS, values, ProjectColumns.PROJECT_ID + "=?",
                    new String[]{project.getProjectId()});
        }
    }

    @Override
    public void deleteProject(Project project) {
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            List<Experiment> experiments = getExperimentsForProject(project, true);
            db.beginTransaction();
            try {
                // Delete each experiment.
                for (Experiment experiment : experiments) {
                    deleteExperiment(experiment);
                }

                // Delete the project.
                db.delete(Tables.PROJECTS, ProjectColumns.PROJECT_ID + "=?",
                        new String[]{project.getProjectId()});

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }

    @Override
    public Experiment getExperimentById(String experimentId) {
        Experiment experiment;

        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getReadableDatabase();
            final String selection = ExperimentColumns.EXPERIMENT_ID + "=?";
            final String[] selectionArgs = new String[]{experimentId};
            Cursor cursor = null;
            try {
                cursor = db.query(
                        Tables.EXPERIMENTS, ExperimentColumns.GET_COLUMNS, selection, selectionArgs,
                        null, null, null, "1");
                if (cursor == null || !cursor.moveToFirst()) {
                    return null;
                }
                experiment = createExperimentFromCursor(cursor);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return experiment;
    }

    @Override
    public Experiment newExperiment(Project project) {
        String experimentId = newStableId(STABLE_EXPERIMENT_ID_LENGTH);
        ContentValues values = new ContentValues();
        values.put(ExperimentColumns.EXPERIMENT_ID, experimentId);
        values.put(ExperimentColumns.PROJECT_ID, project.getProjectId());
        values.put(ExperimentColumns.TIMESTAMP, getCurrentTime());
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            long id = db.insert(Tables.EXPERIMENTS, null, values);
            if (id != -1) {
                Experiment experiment = new Experiment(id);
                experiment.setExperimentId(experimentId);
                experiment.setProjectId(project.getProjectId());
                experiment.setTimestamp(getCurrentTime());
                return experiment;
            }
        }
        return null;
    }

    @Override
    public void deleteExperiment(Experiment experiment) {
        List<String> runIds = getExperimentRunIds(experiment.getExperimentId(),
                /* include archived runs */ true);
        for (String runId : runIds) {
            deleteRun(runId);
        }
        List<Label> labels = getLabelsForExperiment(experiment);
        for (Label label: labels) {
            deleteLabel(label);
        }
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            String[] experimentArgs = new String[]{experiment.getExperimentId()};
            db.delete(Tables.EXPERIMENTS, ExperimentColumns.EXPERIMENT_ID + "=?", experimentArgs);
            db.delete(Tables.EXPERIMENT_SENSORS, ExperimentSensorColumns.EXPERIMENT_ID + "=?",
                    experimentArgs);
            db.delete(Tables.EXPERIMENT_SENSOR_LAYOUT, ExperimentSensorLayoutColumns.EXPERIMENT_ID
                    + "=?", experimentArgs);
        }
    }

    private long getCurrentTime() {
        return mClock.getNow();
    }

    @Override
    public void updateExperiment(Experiment experiment) {
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            final ContentValues values = new ContentValues();
            values.put(ExperimentColumns.TITLE, experiment.getTitle());
            values.put(ExperimentColumns.DESCRIPTION, experiment.getDescription());
            values.put(ExperimentColumns.ARCHIVED, experiment.isArchived());
            values.put(ExperimentColumns.PROJECT_ID, experiment.getProjectId());
            values.put(ExperimentColumns.LAST_USED_TIME, experiment.getLastUsedTime());
            db.update(Tables.EXPERIMENTS, values, ExperimentColumns.EXPERIMENT_ID + "=?",
                    new String[]{experiment.getExperimentId()});
        }
    }

    @Override
    public List<Experiment> getExperimentsForProject(Project project, boolean includeArchived) {
        List<Experiment> experiments = new ArrayList<Experiment>();
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getReadableDatabase();

            String selection = ExperimentColumns.PROJECT_ID + "=?";
            if (!includeArchived) {
                selection += " AND " + ExperimentColumns.ARCHIVED + "=0";
            }
            String[] selectionArgs = new String[]{project.getProjectId()};
            Cursor cursor = null;
            try {
                cursor = db.query(Tables.EXPERIMENTS, ExperimentColumns.GET_COLUMNS, selection,
                        selectionArgs, null, null,
                        ExperimentColumns.LAST_USED_TIME + " DESC, " + BaseColumns._ID + " DESC");
                while (cursor.moveToNext()) {
                    experiments.add(createExperimentFromCursor(cursor));
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return experiments;
    }

    private Experiment createExperimentFromCursor(Cursor cursor) {
        Experiment experiment = new Experiment(cursor.getLong(0));
        experiment.setExperimentId(cursor.getString(1));
        experiment.setTimestamp(cursor.getLong(2));
        experiment.setTitle(cursor.getString(3));
        experiment.setDescription(cursor.getString(4));
        experiment.setProjectId(cursor.getString(5));
        experiment.setArchived(cursor.getInt(6) != 0);
        experiment.setLastUsedTime(cursor.getLong(7));
        return experiment;
    }

    @Override
    public Experiment getLastUsedExperiment() {
        Experiment experiment = null;
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getReadableDatabase();

            Cursor cursor = null;
            try {
                cursor = db.query(Tables.EXPERIMENTS, ExperimentColumns.GET_COLUMNS, null, null,
                        null, null,
                        ExperimentColumns.LAST_USED_TIME + " DESC, " + BaseColumns._ID + " DESC",
                        "1");
                if (cursor.moveToNext()) {
                    experiment = createExperimentFromCursor(cursor);
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return experiment;
    }

    @Override
    public void updateLastUsedExperiment(Experiment experiment) {
        long time = getCurrentTime();
        experiment.setLastUsedTime(time);
        updateExperiment(experiment);
        // Also update the project ID's last used time.
        updateLastUsedProject(experiment.getProjectId(), time);
    }

    @Override
    public void updateLastUsedProject(Project project) {
        long time = getCurrentTime();
        updateLastUsedProject(project.getProjectId(), time);
        project.setLastUsedTime(time);
    }

    private void updateLastUsedProject(String projectId, long time) {
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            final ContentValues values = new ContentValues();
            values.put(ProjectColumns.LAST_USED_TIME, time);
            db.update(Tables.PROJECTS, values, ProjectColumns.PROJECT_ID + "=?",
                    new String[]{projectId});
        }
    }

    @Override
    public Run newRun(Experiment experiment, String runId,
            List<GoosciSensorLayout.SensorLayout> sensorLayouts) {
        // How many runs already exist?
        List<String> runIds = getExperimentRunIds(experiment.getExperimentId(),
                /* include archived runs for indexing */ true);
        int runIndex = runIds.size();
        synchronized (mLock) {
            insertRun(runId, runIndex);
            insertRunSensors(runId, sensorLayouts);
        }

        return new Run(runId, runIndex, sensorLayouts, /* enable auto zoom by default */ true);
    }

    private void insertRun(String runId, int runIndex) {
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            final ContentValues values = new ContentValues();
            values.put(RunsColumns.RUN_ID, runId);
            values.put(RunsColumns.RUN_INDEX, runIndex);
            values.put(RunsColumns.TIMESTAMP, getCurrentTime());
            values.put(RunsColumns.ARCHIVED, false);
            values.put(RunsColumns.TITLE, "");
            values.put(RunsColumns.AUTO_ZOOM_ENABLED, true);
            db.insert(Tables.RUNS, null, values);
        }
    }

    private void insertRunSensors(String runId,
            List<GoosciSensorLayout.SensorLayout> sensorLayouts) {
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            final ContentValues values = new ContentValues();
            values.put(RunSensorsColumns.RUN_ID, runId);
            for (int i = 0; i < sensorLayouts.size(); i++) {
                GoosciSensorLayout.SensorLayout layout = sensorLayouts.get(i);
                values.put(RunSensorsColumns.SENSOR_ID, layout.sensorId);
                values.put(RunSensorsColumns.LAYOUT, ProtoUtils.makeBlob(layout));
                values.put(RunSensorsColumns.POSITION, i);
                db.insert(Tables.RUN_SENSORS, null, values);
            }
        }
    }

    private void updateRunSensors(String runId,
            List<GoosciSensorLayout.SensorLayout> sensorLayouts) {
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            final ContentValues values = new ContentValues();
            for (int i = 0; i < sensorLayouts.size(); i++) {
                GoosciSensorLayout.SensorLayout layout = sensorLayouts.get(i);
                values.put(RunSensorsColumns.LAYOUT, ProtoUtils.makeBlob(layout));
                db.update(Tables.RUN_SENSORS, values, RunSensorsColumns.RUN_ID + "=? AND " +
                        RunSensorsColumns.SENSOR_ID + "=?", new String[]{runId, layout.sensorId});
            }
        }
    }

    @Override
    public void updateRun(Run run) {
        // Only the layout, title, archived state, and autozoom selection can be edited.
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            final ContentValues values = new ContentValues();
            values.put(RunsColumns.TITLE, run.getTitle());
            values.put(RunsColumns.ARCHIVED, run.isArchived());
            values.put(RunsColumns.AUTO_ZOOM_ENABLED, run.getAutoZoomEnabled());
            db.update(Tables.RUNS, values, RunsColumns.RUN_ID + "=?",
                    new String[]{run.getId()});
        }
        updateRunSensors(run.getId(), run.getSensorLayouts());
    }

    @Override
    public Run getRun(String runId) {
        List<GoosciSensorLayout.SensorLayout> sensorLayouts = new ArrayList<>();
        int runIndex = -1;
        boolean archived = false;
        String title = "";
        boolean autoZoomEnabled = true;

        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getReadableDatabase();

            final String selection = RunSensorsColumns.RUN_ID + "=?";
            final String[] selectionArgs = new String[]{runId};

            Cursor cursor = null;
            try {
                cursor = db.query(Tables.RUNS, new String[] {RunsColumns.RUN_INDEX,
                        RunsColumns.TITLE, RunsColumns.ARCHIVED,
                        RunsColumns.AUTO_ZOOM_ENABLED},
                        selection, selectionArgs, null, null, null);
                if (cursor != null & cursor.moveToFirst()) {
                    runIndex = cursor.getInt(0);
                    title = cursor.getString(1);
                    archived = cursor.getInt(2) != 0;
                    autoZoomEnabled = cursor.getInt(3) != 0;
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }

            // Now get sensor layouts.
            GoosciSensorLayout.SensorLayout layout;
            int defaultColor = mContext.getResources().getColor(R.color.graph_line_color_blue);
            try {
                cursor = db.query(Tables.RUN_SENSORS, new String[]{RunSensorsColumns.LAYOUT,
                        RunSensorsColumns.SENSOR_ID}, selection, selectionArgs, null, null,
                        RunSensorsColumns.POSITION + " ASC");
                while (cursor.moveToNext()) {
                    try {
                        byte[] blob = cursor.getBlob(0);
                        if (blob != null) {
                            layout = GoosciSensorLayout.SensorLayout.parseFrom(blob);
                        } else {
                            // In this case, create a fake sensorLayout since none exists.
                            layout = new GoosciSensorLayout.SensorLayout();
                            layout.sensorId = cursor.getString(1);
                            layout.color = defaultColor;
                        }
                        sensorLayouts.add(layout);
                    } catch (InvalidProtocolBufferNanoException e) {
                        Log.d(TAG, "Couldn't parse layout", e);
                    }
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }

        }
        if (runIndex != -1) {
            Run result = new Run(runId, runIndex, sensorLayouts, autoZoomEnabled);
            result.setArchived(archived);
            result.setTitle(title);
            return result;
        } else {
            return null;
        }
    }

    @Override
    public void setExperimentSensorLayouts(String experimentId,
            List<GoosciSensorLayout.SensorLayout> sensorLayouts) {
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            for (int i = 0; i < sensorLayouts.size(); i++) {
                ContentValues values = new ContentValues();
                values.put(ExperimentSensorLayoutColumns.EXPERIMENT_ID, experimentId);
                values.put(ExperimentSensorLayoutColumns.POSITION, i);
                values.put(ExperimentSensorLayoutColumns.LAYOUT,
                        ProtoUtils.makeBlob(sensorLayouts.get(i)));
                db.insertWithOnConflict(Tables.EXPERIMENT_SENSOR_LAYOUT, null, values,
                        SQLiteDatabase.CONFLICT_REPLACE);
            }

            db.delete(Tables.EXPERIMENT_SENSOR_LAYOUT,
                    ExperimentSensorLayoutColumns.EXPERIMENT_ID + "=? AND "
                            + ExperimentSensorLayoutColumns.POSITION + " >= "
                            + sensorLayouts.size(), new String[]{experimentId});
        }
    }

    @Override
    public List<GoosciSensorLayout.SensorLayout> getExperimentSensorLayouts(String experimentId) {
        List<GoosciSensorLayout.SensorLayout> layouts = new ArrayList<>();
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getReadableDatabase();
            Cursor cursor = null;
            try {
                cursor = db.query(Tables.EXPERIMENT_SENSOR_LAYOUT,
                        new String[]{ExperimentSensorLayoutColumns.LAYOUT},
                        ExperimentSensorLayoutColumns.EXPERIMENT_ID + "=?",
                        new String[]{experimentId}, null, null,
                        ExperimentSensorLayoutColumns.POSITION + " ASC");
                Set<String> sensorIdsAdded = new HashSet<>();
                while (cursor.moveToNext()) {
                    try {
                        GoosciSensorLayout.SensorLayout layout =
                                GoosciSensorLayout.SensorLayout.parseFrom(cursor.getBlob(0));
                        if (!sensorIdsAdded.contains(layout.sensorId)) {
                            layouts.add(layout);
                        }
                        sensorIdsAdded.add(layout.sensorId);
                    } catch (InvalidProtocolBufferNanoException e) {
                        Log.e(TAG, "Couldn't parse layout", e);
                    }
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        return layouts;
    }

    @Override
    public void updateSensorLayout(String experimentId, int position,
            GoosciSensorLayout.SensorLayout layout) {
        ContentValues values = new ContentValues();
        values.put(ExperimentSensorLayoutColumns.LAYOUT, ProtoUtils.makeBlob(layout));
        String where = ExperimentSensorLayoutColumns.EXPERIMENT_ID + "=? AND " +
                ExperimentSensorLayoutColumns.POSITION + "=?";
        String[] params = new String[]{experimentId, String.valueOf(position)};
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            db.update(Tables.EXPERIMENT_SENSOR_LAYOUT, values, where, params);
        }
    }

    @Override
    public void deleteRun(String runId) {
        for (Label label : getLabelsWithStartId(runId)) {
            deleteLabel(label);
        }
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            String selectionRunId = RunsColumns.RUN_ID + "=?";
            String[] runIdArgs = new String[]{runId};
            db.delete(Tables.RUN_SENSORS, selectionRunId, runIdArgs);
            db.delete(Tables.RUN_STATS, RunStatsColumns.START_LABEL_ID + "=?", runIdArgs);
            db.delete(Tables.RUNS, selectionRunId, runIdArgs);
        }
    }

    @Override
    public String addOrGetExternalSensor(ExternalSensorSpec sensor,
            Map<String, ExternalSensorProvider> providerMap) {
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getReadableDatabase();
            String sql = "SELECT IFNULL(MIN(" + SensorColumns.SENSOR_ID + "), '') FROM " + Tables
                    .EXTERNAL_SENSORS + " WHERE " + SensorColumns.CONFIG + "=? AND " +
                    SensorColumns.TYPE + "=?";
            SQLiteStatement statement = db.compileStatement(sql);
            statement.bindBlob(1, sensor.getConfig());
            statement.bindString(2, sensor.getType());
            String sensorId = statement.simpleQueryForString();
            if (!sensorId.isEmpty()) {
                return sensorId;
            }
        }

        int suffix = 0;
        while (getExternalSensorById(ExternalSensorSpec.getSensorId(sensor, suffix), providerMap)
                != null) {
            suffix++;
        }

        String sensorId = ExternalSensorSpec.getSensorId(sensor, suffix);
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            ContentValues values = getContentValuesFromSensor(sensor);
            values.put(SensorColumns.SENSOR_ID, sensorId);
            db.insert(Tables.EXTERNAL_SENSORS, null, values);
        }
        return sensorId;
    }

    @Override
    public Project getLastUsedProject() {
        Project project = null;
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getReadableDatabase();
            Cursor cursor = null;
            try {
                cursor = db.query(
                        Tables.PROJECTS, ProjectColumns.GET_COLUMNS, null, null, null, null,
                        ProjectColumns.LAST_USED_TIME + " DESC, " + BaseColumns._ID + " DESC",
                        "1");
                if (cursor.moveToNext()) {
                    project = createProjectFromCursor(cursor);
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        return project;
    }

    @Override
    public void addLabel(Experiment experiment, Label label) {
        addLabel(experiment.getExperimentId(), label);
    }

    @Override
    public void addLabel(String experimentId, Label label) {
        ContentValues values = new ContentValues();
        values.put(LabelColumns.EXPERIMENT_ID, experimentId);
        values.put(LabelColumns.TYPE, label.getTag());
        values.put(LabelColumns.TIMESTAMP, label.getTimeStamp());
        values.put(LabelColumns.LABEL_ID, label.getLabelId());
        values.put(LabelColumns.START_LABEL_ID, label.getRunId());
        values.put(LabelColumns.VALUE, ProtoUtils.makeBlob(label.getValue()));
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            db.insert(Tables.LABELS, null, values);
        }
    }

    public static class LabelQuery {
        public static String[] PROJECTION = new String[]{
                LabelColumns.TYPE,
                LabelColumns.TIMESTAMP,
                LabelColumns.DATA,  // Deprecated for newer versions.
                LabelColumns.LABEL_ID,
                LabelColumns.START_LABEL_ID,
                LabelColumns.EXPERIMENT_ID,
                LabelColumns.VALUE,
        };

        public static int TYPE_INDEX = 0;
        public static int TIMESTAMP_INDEX = 1;
        public static int DATA_INDEX = 2;
        public static int LABEL_ID_INDEX = 3;
        public static int START_LABEL_ID_INDEX = 4;
        public static int EXPERIMENT_ID_INDEX = 5;
        public static int VALUE_INDEX = 6;
    }

    @Override
    public List<Label> getLabelsForExperiment(Experiment experiment) {
        final String selection = LabelColumns.EXPERIMENT_ID + "=?";
        final String[] selectionArgs = new String[]{experiment.getExperimentId()};
        return getLabels(selection, selectionArgs);
    }

    private List<Label> getLabels(String selection, String[] selectionArgs) {
        List<Label> labels = new ArrayList<>();
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getReadableDatabase();
            Cursor cursor = null;
            try {
                cursor = db.query(Tables.LABELS, LabelQuery.PROJECTION, selection, selectionArgs,
                        null, null, null);
                while (cursor.moveToNext()) {
                    String type = cursor.getString(LabelQuery.TYPE_INDEX);
                    Label label;
                    // TODO: fix code smell: perhaps make a factory?
                    final String labelId = cursor.getString(LabelQuery.LABEL_ID_INDEX);
                    final String startLabelId = cursor.getString(LabelQuery.START_LABEL_ID_INDEX);
                    long timestamp = cursor.getLong(LabelQuery.TIMESTAMP_INDEX);
                    GoosciLabelValue.LabelValue value = null;
                    try {
                        byte[] blob = cursor.getBlob(LabelQuery.VALUE_INDEX);
                        if (blob != null) {
                            value = GoosciLabelValue.LabelValue.parseFrom(blob);
                        }
                    } catch (InvalidProtocolBufferNanoException ex) {
                        Log.d(TAG, "Unable to parse label value");
                    }
                    if (value != null) {
                        // Add new types of labels to this list.
                        if (TextLabel.isTag(type)) {
                            label = new TextLabel(labelId, startLabelId, timestamp, value);
                        } else if (PictureLabel.isTag(type)) {
                            label = new PictureLabel(labelId, startLabelId, timestamp, value);
                        } else if (ApplicationLabel.isTag(type)) {
                            label = new ApplicationLabel(labelId, startLabelId, timestamp, value);
                        } else if (SensorTriggerLabel.isTag(type)) {
                            label = new SensorTriggerLabel(labelId, startLabelId, timestamp, value);
                        } else {
                            throw new IllegalStateException("Unknown label type: " + type);
                        }
                    } else {
                        // Old text, picture and application labels were added when label data
                        // was stored as a string. New types of labels should not be added to this
                        // list.
                        final String data = cursor.getString(LabelQuery.DATA_INDEX);
                        if (TextLabel.isTag(type)) {
                            label = new TextLabel(data, labelId, startLabelId, timestamp);
                        } else if (PictureLabel.isTag(type)) {
                            // Early picture labels had no captions.
                            label = new PictureLabel(data, "", labelId, startLabelId, timestamp);
                        } else if (ApplicationLabel.isTag(type)) {
                            label = new ApplicationLabel(data, labelId, startLabelId, timestamp);
                        } else {
                            throw new IllegalStateException("Unknown label type: " + type);
                        }
                    }
                    label.setTimestamp(timestamp);
                    label.setExperimentId(cursor.getString(LabelQuery.EXPERIMENT_ID_INDEX));
                    labels.add(label);
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return labels;
    }

    @Override
    public List<Label> getLabelsWithStartId(String startLabelId) {
        final String selection = LabelColumns.START_LABEL_ID + "=?";
        final String[] selectionArgs = new String[]{startLabelId};
        return getLabels(selection, selectionArgs);
    }

    @Override
    public void setStats(String startLabelId, String sensorId, RunStats stats) {
        ContentValues values = new ContentValues();
        values.put(RunStatsColumns.START_LABEL_ID, startLabelId);
        values.put(RunStatsColumns.SENSOR_TAG, sensorId);
        for (String key : stats.getKeys()) {
            values.put(RunStatsColumns.STAT_NAME, key);
            values.put(RunStatsColumns.STAT_VALUE, stats.getStat(key));
            synchronized (mLock) {
                final SQLiteDatabase db = mDbHelper.getWritableDatabase();
                db.insert(Tables.RUN_STATS, null, values);
            }
        }
    }

    @Override
    public RunStats getStats(String startLabelId, String sensorId) {
        final RunStats runStats = new RunStats();
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getReadableDatabase();
            Cursor cursor = null;
            try {
                cursor = db.query(Tables.RUN_STATS,
                        new String[]{RunStatsColumns.STAT_NAME, RunStatsColumns.STAT_VALUE},
                        RunStatsColumns.START_LABEL_ID + " =? AND " + RunStatsColumns.SENSOR_TAG
                                + " =?",
                        new String[]{startLabelId, sensorId}, null, null, null);
                while (cursor.moveToNext()) {
                    final String statName = cursor.getString(0);
                    final double statValue = cursor.getDouble(1);
                    runStats.putStat(statName, statValue);
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        return runStats;
    }

    @Override
    public List<String> getExperimentRunIds(String experimentId, boolean includeArchived) {
        // TODO: use start index as offset.
        List<String> ids = new ArrayList<>();
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getReadableDatabase();
            Cursor cursor = null;
            try {
                String selection = LabelColumns.LABEL_ID + "=" + LabelColumns.START_LABEL_ID +
                        " AND " + LabelColumns.EXPERIMENT_ID + "=?";
                if (!includeArchived) {
                    selection += " AND (" + RunsColumns.ARCHIVED + "=0 OR " +
                            RunsColumns.ARCHIVED + " IS NULL)";
                }
                cursor = db.query(
                        Tables.RUNS + " AS r JOIN " + Tables.LABELS + " AS l ON "
                                + RunsColumns.RUN_ID + "=" + LabelColumns.START_LABEL_ID,
                        new String[]{RunsColumns.RUN_ID}, selection, new String[] {experimentId},
                        null, null, "r." + RunsColumns.TIMESTAMP + " DESC", null);
                while (cursor.moveToNext()) {
                    ids.add(cursor.getString(0));
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return ids;
    }

    // TODO(saff): test
    @Override
    public void editLabel(Label updatedLabel) {
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            final ContentValues values = new ContentValues();
            values.put(LabelColumns.VALUE, ProtoUtils.makeBlob(updatedLabel.getValue()));
            values.put(LabelColumns.TIMESTAMP, updatedLabel.getTimeStamp());
            db.update(Tables.LABELS, values, LabelColumns.LABEL_ID + "=?",
                    new String[]{updatedLabel.getLabelId()});
        }
    }

    @Override
    public void deleteLabel(Label label) {
        if (label instanceof PictureLabel) {
            File file = new File(((PictureLabel) label).getAbsoluteFilePath());
            boolean deleted = file.delete();
            if (!deleted) {
                Log.w(TAG, "Could not delete " + file.toString());
            }
            PictureUtils.scanFile(file.getAbsolutePath(), mContext);
        }
        String selection = LabelColumns.LABEL_ID + "=?";
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            db.delete(Tables.LABELS, selection, new String[]{label.getLabelId()});
        }
    }

    @NonNull
    private ContentValues getContentValuesFromSensor(ExternalSensorSpec sensor) {
        ContentValues values = new ContentValues();
        values.put(SensorColumns.TYPE, sensor.getType());
        values.put(SensorColumns.NAME, sensor.getName());
        values.put(SensorColumns.CONFIG, sensor.getConfig());
        return values;
    }

    @Override
    public void removeExternalSensor(String databaseTag) {
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            db.delete(Tables.EXTERNAL_SENSORS, SensorColumns.SENSOR_ID + "=?",
                    new String[]{databaseTag});
            db.delete(Tables.EXPERIMENT_SENSORS, ExperimentSensorColumns.SENSOR_TAG + "=?",
                    new String[]{databaseTag});
        }
    }

    static class SensorQuery {
        public static String[] PROJECTION = new String[] {
                SensorColumns.SENSOR_ID,
                SensorColumns.TYPE,
                SensorColumns.NAME,
                SensorColumns.CONFIG
        };

        static int DATABASE_TAG_INDEX = 0;
        static int TYPE_INDEX = 1;
        static int NAME_INDEX = 2;
        static int CONFIG_INDEX = 3;
    }

    @Override
    public Map<String, ExternalSensorSpec> getExternalSensors(
            Map<String, ExternalSensorProvider> providerMap) {
        Map<String, ExternalSensorSpec> sensors = new HashMap<>();

        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getReadableDatabase();
            Cursor c = null;
            try {
                c = db.query(Tables.EXTERNAL_SENSORS, SensorQuery.PROJECTION, null, null, null,
                        null, null);
                while (c.moveToNext()) {
                    ExternalSensorSpec value = loadSensorFromDatabase(c, providerMap);
                    if (value != null) {
                        sensors.put(c.getString(SensorQuery.DATABASE_TAG_INDEX), value);
                    }
                }
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        }

        return sensors;
    }

    @Override
    public ExternalSensorSpec getExternalSensorById(String id,
            Map<String, ExternalSensorProvider> providerMap) {
        ExternalSensorSpec sensor = null;
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getReadableDatabase();
            Cursor c = null;
            try {
                c = db.query(Tables.EXTERNAL_SENSORS, SensorQuery.PROJECTION,
                        SensorColumns.SENSOR_ID + "=?", new String[]{id}, null, null, null);
                if (c.moveToNext()) {
                    sensor = loadSensorFromDatabase(c, providerMap);
                }
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        }
        return sensor;
    }

    private ExternalSensorSpec loadSensorFromDatabase(Cursor c,
            Map<String, ExternalSensorProvider> providerMap) {
        String type = c.getString(SensorQuery.TYPE_INDEX);
        ExternalSensorProvider externalSensorProvider = providerMap.get(type);
        if (externalSensorProvider == null) {
            throw new IllegalArgumentException("No provider for sensor type: " + type);
        }
        return externalSensorProvider.buildSensorSpec(c.getString(SensorQuery.NAME_INDEX),
                c.getBlob(SensorQuery.CONFIG_INDEX));
    }

    @Override
    public void addSensorToExperiment(String databaseTag, String experimentId) {
        ContentValues values = new ContentValues();
        values.put(ExperimentSensorColumns.SENSOR_TAG, databaseTag);
        values.put(ExperimentSensorColumns.EXPERIMENT_ID, experimentId);
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            db.insert(Tables.EXPERIMENT_SENSORS, ExperimentSensorColumns.SENSOR_TAG, values);
        }
    }

    @Override
    public void removeSensorFromExperiment(String databaseTag, String experimentId) {
        String selection = ExperimentSensorColumns.SENSOR_TAG + " =? AND " +
                ExperimentSensorColumns.EXPERIMENT_ID + "=?";
        String[] selectionArgs = new String[] {databaseTag, experimentId};
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            db.delete(Tables.EXPERIMENT_SENSORS, selection, selectionArgs);
        }
    }

    @Override
    public Map<String, ExternalSensorSpec> getExperimentExternalSensors(String experimentId,
            Map<String, ExternalSensorProvider> providerMap) {
        List<String> tags = new ArrayList<>();
        Map<String, ExternalSensorSpec> sensors = new HashMap<>();

        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getReadableDatabase();
            Cursor c = null;
            try {
                c = db.query(Tables.EXPERIMENT_SENSORS,
                        new String[] {ExperimentSensorColumns.SENSOR_TAG},
                        ExperimentSensorColumns.EXPERIMENT_ID + "=?",
                        new String[] {experimentId}, null, null, null);
                while (c.moveToNext()) {
                    tags.add(c.getString(0));
                }
            } finally {
                if (c != null) {
                    c.close();
                }
            }
            // This is somewhat inefficient to do nested queries, but in most cases there will
            // only be one or two, so we are trading off code complexity of doing a db join.
            for (String tag : tags) {
                sensors.put(tag, getExternalSensorById(tag, providerMap));
            }
        }

        return sensors;
    }

    @Override
    public void addSensorTrigger(SensorTrigger trigger, String experimentId) {
        ContentValues values = new ContentValues();
        values.put(SensorTriggerColumns.EXPERIMENT_ID, experimentId);
        values.put(SensorTriggerColumns.TRIGGER_ID, trigger.getTriggerId());
        values.put(SensorTriggerColumns.LAST_USED_TIMESTAMP_MS, trigger.getLastUsed());
        values.put(SensorTriggerColumns.SENSOR_ID, trigger.getSensorId());
        values.put(SensorTriggerColumns.TRIGGER_INFORMATION,
                ProtoUtils.makeBlob(trigger.getTriggerInformation()));
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            db.insert(Tables.SENSOR_TRIGGERS, null, values);
        }
    }

    @Override
    public void updateSensorTrigger(SensorTrigger trigger) {
        // Only the LastUsedTimestamp and TriggerInformation can be updated.
        ContentValues values = new ContentValues();
        values.put(SensorTriggerColumns.LAST_USED_TIMESTAMP_MS, trigger.getLastUsed());
        values.put(SensorTriggerColumns.TRIGGER_INFORMATION,
                ProtoUtils.makeBlob(trigger.getTriggerInformation()));
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            db.update(Tables.SENSOR_TRIGGERS, values, SensorTriggerColumns.TRIGGER_ID + "=?",
                    new String[]{trigger.getTriggerId()});
        }
    }

    @Override
    public List<SensorTrigger> getSensorTriggers(String[] triggerIds) {
        List<SensorTrigger> triggers = new ArrayList<>();
        if (triggerIds == null || triggerIds.length == 0) {
            return triggers;
        }

        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getReadableDatabase();
            String[] whereArr = new String[triggerIds.length];
            Arrays.fill(whereArr, "?");
            final String where = TextUtils.join(",", whereArr);
            final String selection = SensorTriggerColumns.TRIGGER_ID + " IN (" + where + ")";
            Cursor c = null;
            try {
                c = db.query(
                        Tables.SENSOR_TRIGGERS, new String[]{SensorTriggerColumns.TRIGGER_ID,
                                SensorTriggerColumns.SENSOR_ID,
                                SensorTriggerColumns.LAST_USED_TIMESTAMP_MS,
                                SensorTriggerColumns.TRIGGER_INFORMATION}, selection, triggerIds,
                        null, null, null, null);
                if (c == null || !c.moveToFirst()) {
                    return triggers;
                }
                while (!c.isAfterLast()) {
                    triggers.add(new SensorTrigger(c.getString(0), c.getString(1), c.getLong(2),
                            GoosciSensorTriggerInformation.TriggerInformation.parseFrom(
                                    c.getBlob(3))));
                    c.moveToNext();
                }
            } catch (InvalidProtocolBufferNanoException e) {
                e.printStackTrace();
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        }
        return triggers;
    }

    @Override
    public List<SensorTrigger> getSensorTriggersForSensor(String sensorId) {
        List<SensorTrigger> triggers = new ArrayList<>();

        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getReadableDatabase();
            Cursor c = null;
            String selection = SensorTriggerColumns.SENSOR_ID + "=?";
            String[] selectionArgs = new String[]{sensorId};
            try {
                c = db.query(Tables.SENSOR_TRIGGERS, new String[]{
                        SensorTriggerColumns.TRIGGER_ID,
                        SensorTriggerColumns.LAST_USED_TIMESTAMP_MS,
                        SensorTriggerColumns.TRIGGER_INFORMATION},
                        selection, selectionArgs, null, null,
                        SensorTriggerColumns.LAST_USED_TIMESTAMP_MS + " DESC");
                while (c.moveToNext()) {
                    triggers.add(new SensorTrigger(c.getString(0), sensorId, c.getLong(1),
                            GoosciSensorTriggerInformation.TriggerInformation.parseFrom(
                                    c.getBlob(2))));
                }
            } catch (InvalidProtocolBufferNanoException e) {
                e.printStackTrace();
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        }
        return triggers;
    }

    @Override
    public void deleteSensorTrigger(SensorTrigger trigger) {
        synchronized (mLock) {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            db.delete(Tables.SENSOR_TRIGGERS, SensorTriggerColumns.TRIGGER_ID + "=?",
                    new String[]{trigger.getTriggerId()});
        }
    }

    public interface ProjectColumns {

        /**
         * Stable project ID.
         */
        String PROJECT_ID = "project_id";

        /**
         * Project Title.
         */
        String TITLE = "title";

        /**
         * Project description.
         */
        String DESCRIPTION = "description";

        /**
         * Project cover photo. This is a local file URL.
         */
        String COVER_PHOTO = "cover_photo";


        /**
         * Whether the project is archived or not.
         */
        String ARCHIVED = "archived";

        /**
         * Timestamp in UTC based on phone system clock of the last time the project was used.
         */
        String LAST_USED_TIME = "last_used_time";

        /**
         * Selection args for getting a project data.
         */
        String[] GET_COLUMNS = new String[] {
            BaseColumns._ID,
            ProjectColumns.PROJECT_ID,
            ProjectColumns.TITLE,
            ProjectColumns.COVER_PHOTO,
            ProjectColumns.ARCHIVED,
            ProjectColumns.DESCRIPTION,
            ProjectColumns.LAST_USED_TIME
        };
    }

    public interface ExperimentColumns {

        /**
         * Project this experiment belongs to, corresponding to {@link ProjectColumns#PROJECT_ID}.
         */
        String PROJECT_ID = "project_id";

        /**
         * Stable experiment ID.
         */
        String EXPERIMENT_ID = "experiment_id";

        /**
         * Timestamp of this experiment when it was created.
         */
        String TIMESTAMP = "timestamp";

        /**
         * Experiment title.
         */
        String TITLE = "title";

        /**
         * Experiment description.
         */
        String DESCRIPTION = "description";

        /**
         * Whether the experiment is archived or not.
         */
        String ARCHIVED = "archived";

        /**
         * Timestamp of when this experiment was last used.
         */
        String LAST_USED_TIME = "last_used_time";

        String[] GET_COLUMNS = new String[]{
                BaseColumns._ID,
                ExperimentColumns.EXPERIMENT_ID,
                ExperimentColumns.TIMESTAMP,
                ExperimentColumns.TITLE,
                ExperimentColumns.DESCRIPTION,
                ExperimentColumns.PROJECT_ID,
                ExperimentColumns.ARCHIVED,
                ExperimentColumns.LAST_USED_TIME
        };
    }

    public interface LabelColumns {

        /**
         * Experiment this label belongs to, corresponding to
         * {@link ExperimentColumns#EXPERIMENT_ID}.
         */
        String EXPERIMENT_ID = "experiment_id";

        /**
         * Time when this label was created.
         */
        String TIMESTAMP = "timestamp";

        /**
         * Type of label, either {@link TextLabel#TAG} or {@link PictureLabel#TAG}
         */
        String TYPE = "type";

        /**
         * Data for the label: in the case of a text label, this is the text. In the case of other
         * types, a Uri pointing at the media.
         * This field is deprecated at database version 15, but is still read for old labels.
         */
        String DATA = "data";

        /**
         * Unique id for the label.
         */
        String LABEL_ID = "label_id";

        /**
         * ID for the run that this label is associated with.
         */
        String START_LABEL_ID = "start_label_id";

        /**
         * The GoosciLabelStorage stored as a blob for the value of the data.
         */
        String VALUE = "value";
    }

    public interface SensorColumns {

        /**
         * ID of the sensor. Should be unique.
         */
        String SENSOR_ID = "sensor_id";

        /**
         * Type of external sensor.
         */
        String TYPE = "type";

        /**
         * Human readable name of this sensor.
         */
        String NAME= "name";

        /**
         * Configuration data for this sensor.
         */
        String CONFIG = "config";
    }

    public interface ExperimentSensorColumns {

        /**
         * Database tag of a sensor that belongs to a particular experiment.
         */
        String SENSOR_TAG = "sensor_tag";

        /**
         * Experiment ID.
         */
        String EXPERIMENT_ID = "experiment_id";
    }

    public interface RunStatsColumns {
        /**
         * ID for the run that this stat is associated with.
         */
        String START_LABEL_ID = "start_label_id";

        /**
         * Database tag of the sensor that this stat is associated with.
         */
        String SENSOR_TAG = "sensor_tag";

        /**
         * Name of the stat being stored.
         */
        String STAT_NAME = "stat_name";

        /**
         * Value of the stat
         */
        String STAT_VALUE = "stat_value";
    }

    public interface RunsColumns {
        /**
         * ID for the run that this row is associated with.  (Matches "start_label_id" in some
         * other tables)
         */
        String RUN_ID = "run_id";

        /**
         * Index of this run in the total experiment list. Storing this because we retrieve runs
         * one at a time, so can't derive this at query time.
         */
        String RUN_INDEX = "run_index";

        /**
         * Time when this run was _completed_
         */
        String TIMESTAMP = "timestamp";

        /**
         * User chosen name for this run. This may be empty.
         */
        String TITLE = "run_title";

        /**
         * Whether the run is archived.
         */
        String ARCHIVED = "run_archived";

        /**
         * Whether auto zoom is enabled (i.e. RunReview should zoom in on the Y axis by default)
         */
        String AUTO_ZOOM_ENABLED = "auto_zoom_enabled";
    }

    public interface RunSensorsColumns {
        /**
         * ID for the run that this sensor is associated with.  (Matches "start_label_id" in some
         * other tables)
         */
        String RUN_ID = "run_id";

        /**
         * ID of the sensor
         */
        String SENSOR_ID = "sensor_id";

        /**
         * Position in the list of sensors on screen
         */
        String POSITION = "position";

        /**
         * ID for the sensor layout this sensor is associated with.
         */
        String LAYOUT = "layout";
    }

    public interface ExperimentSensorLayoutColumns {
        /**
         * Experiment ID.
         */
        String EXPERIMENT_ID = "experiment_id";

        /**
         * Position in the list of sensors on screen
         */
        String POSITION = "position";

        /**
         * Layout of this sensor (including sensorId)
         */
        String LAYOUT = "layout";
    }

    public interface SensorTriggerColumns {
        /**
         * Trigger ID. THis is unique.
         */
        String TRIGGER_ID = "trigger_id";

        /**
         * Sensor ID for this trigger.
         */
        String SENSOR_ID = "sensor_id";

        /**
         * The timestamp when this trigger was last used.
         */
        String LAST_USED_TIMESTAMP_MS = "last_used_timestamp";

        /**
         * The experiment ID that this trigger is associated with.
         */
        String EXPERIMENT_ID = "experiment_id";

        /**
         * The TriggerInformation proto containing the configuration of this trigger.
         */
        String TRIGGER_INFORMATION = "trigger_information";
    }

    /**
     * Manages the SQLite database backing the data for the entire app.
     */
    private static class DatabaseHelper extends SQLiteOpenHelper {
        private static final int DB_VERSION = 18;
        private static final String DB_NAME = "main.db";

        DatabaseHelper(Context context, String filename) {
            super(context, filename != null ? filename : DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            createProjectsTable(db);
            createExperimentsTable(db);
            createLabelsTable(db);
            createSensorsTable(db);
            createExperimentSensorsTable(db);
            createRunStatsTable(db);
            createRunsTable(db);
            createRunSensorsTable(db);
            createExperimentSensorLayoutTable(db);
            createSensorTriggersTable(db);
        }

        private void createExperimentsTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + Tables.EXPERIMENTS + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + ExperimentColumns.EXPERIMENT_ID + " TEXT NOT NULL, "
                    + ExperimentColumns.PROJECT_ID + " TEXT NOT NULL, "
                    + ExperimentColumns.TIMESTAMP + " INTEGER NOT NULL, "
                    + ExperimentColumns.TITLE + " TEXT, "
                    + ExperimentColumns.DESCRIPTION + " TEXT, "
                    + ExperimentColumns.ARCHIVED + " BOOLEAN NOT NULL DEFAULT 0, "
                    + ExperimentColumns.LAST_USED_TIME + " INTEGER, "
                    + "UNIQUE (" + ExperimentColumns.EXPERIMENT_ID + ") ON CONFLICT REPLACE)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            int version = oldVersion;

            if ((version == 1 || version == 2) && version < newVersion) {
                // 1 -> 2: Recreate labels table with label_id field.
                // 2 -> 3: Recreate labels table with start_label_id field.

                db.execSQL("DROP TABLE " + Tables.LABELS);
                createLabelsTable(db);
                //noinspection UnusedAssignment
                version = 3;
            }
            if (version == 3 && version < newVersion) {
                // 3 -> 4: Add sensors table and mapping table for experiment sensors.
                createSensorsTable(db);
                createExperimentSensorsTable(db);
                version = 4;
            }
            if (version == 4 && version < newVersion) {
                // 4 -> 5: Add new columns to projects table (title, cover and archived).
                // Also add title, archived and description to experiments table.
                db.execSQL("ALTER TABLE " + Tables.PROJECTS + " ADD COLUMN " +
                        ProjectColumns.TITLE + " TEXT");
                db.execSQL("ALTER TABLE " + Tables.PROJECTS + " ADD COLUMN " +
                        ProjectColumns.ARCHIVED + " BOOLEAN");
                db.execSQL("ALTER TABLE " + Tables.PROJECTS + " ADD COLUMN " +
                        ProjectColumns.COVER_PHOTO + " TEXT");

                db.execSQL("ALTER TABLE " + Tables.EXPERIMENTS + " ADD COLUMN " +
                        ExperimentColumns.TITLE + " TEXT");
                db.execSQL("ALTER TABLE " + Tables.EXPERIMENTS + " ADD COLUMN " +
                        ExperimentColumns.ARCHIVED + " BOOLEAN");
                db.execSQL("ALTER TABLE " + Tables.EXPERIMENTS + " ADD COLUMN " +
                        ExperimentColumns.DESCRIPTION + " TEXT");
                version = 5;
            }
            if (version == 5 && version < newVersion) {
                // 5 -> 6: Drop tables and recreate them to set a default FALSE value to archived
                // bit in projects and experiments tables. SQLite does not support ALTER TABLE
                // foo ALTER COLUMN or DROP COLUMN. SQLite implements a small subset of SQL.
                // Hence the need to drop the tables and re-create them.
                // See https://www.sqlite.org/lang_altertable.html for allowed syntax.
                db.execSQL("DROP TABLE " + Tables.PROJECTS);
                db.execSQL("DROP TABLE " + Tables.EXPERIMENTS);
                db.execSQL("DROP TABLE " + Tables.EXPERIMENT_SENSORS);
                db.execSQL("DROP TABLE " + Tables.LABELS);
                createProjectsTable(db);
                createExperimentsTable(db);
                createLabelsTable(db);
                createExperimentSensorsTable(db);
                version = 6;
            }
            if (version == 6 && version < newVersion) {
                createRunStatsTable(db);
                version = 7;
            }
            if (version == 7 && version < newVersion) {
                // 7 -> 8: Add description column to projects.
                db.execSQL("ALTER TABLE " + Tables.PROJECTS + " ADD COLUMN " +
                        ProjectColumns.DESCRIPTION + " TEXT");
            }

            if (version == 8 && version < newVersion) {
                // We could try to rebuild the runs table from the information in the labels
                // table, but it's likely not worth it pre-release.
                db.execSQL("DROP TABLE " + Tables.LABELS);
                createLabelsTable(db);
                createRunsTable(db);
                createRunSensorsTable(db);
                version = 9;
            }

            if (version == 9 && version < newVersion) {
                // Add last used columns.
                db.execSQL("ALTER TABLE " + Tables.PROJECTS + " ADD COLUMN " +
                        ProjectColumns.LAST_USED_TIME + " INTEGER");
                db.execSQL("ALTER TABLE " + Tables.EXPERIMENTS + " ADD COLUMN " +
                        ExperimentColumns.LAST_USED_TIME + " INTEGER");
                version = 10;
            }

            if (version == 10 && version < newVersion) {
                // Add experiment sensor layout table
                createExperimentSensorLayoutTable(db);
                version = 11;
            }

            if (version == 11 && version < newVersion) {
                // Add run index to runs table.
                db.execSQL("ALTER TABLE " + Tables.RUNS + " ADD COLUMN " +
                        RunsColumns.RUN_INDEX + " INTEGER");
                // Insert sentinel value for older runs.
                db.execSQL("UPDATE " + Tables.RUNS + " SET " + RunsColumns.RUN_INDEX + " = -1");
                version = 12;
            }

            if (version == 12 && version < newVersion) {
                // Add run archived state and title to the runs table.
                db.execSQL("ALTER TABLE " + Tables.RUNS + " ADD COLUMN " +
                        RunsColumns.TITLE + " TEXT");
                db.execSQL("ALTER TABLE " + Tables.RUNS + " ADD COLUMN " +
                        RunsColumns.ARCHIVED + " BOOLEAN");
                version = 13;
            }

            if (version == 13 && version < newVersion) {
                // Add SensorLayouts to the Runs Sensors table.
                db.execSQL("ALTER TABLE " + Tables.RUN_SENSORS + " ADD COLUMN " +
                        RunSensorsColumns.LAYOUT + " BLOB");
                db.execSQL("ALTER TABLE " + Tables.RUN_SENSORS + " ADD COLUMN " +
                        RunSensorsColumns.POSITION + " INTEGER");
                db.execSQL("UPDATE " + Tables.RUN_SENSORS + " SET " +
                        RunSensorsColumns.POSITION + " = -1");
                version = 14;
            }

            if (version == 14 && version < newVersion) {
                db.execSQL("ALTER TABLE " + Tables.LABELS + " ADD COLUMN " +
                    LabelColumns.VALUE + " BLOB");
                version = 15;
            }

            if (version == 15 && version < newVersion) {
                db.execSQL("ALTER TABLE " + Tables. RUNS + " ADD COLUMN " +
                    RunsColumns.AUTO_ZOOM_ENABLED + " BOOLEAN");
                version = 16;
            }

            if (version == 16 && version < newVersion) {
                db.execSQL("UPDATE " + Tables. RUNS + " SET " + RunsColumns.AUTO_ZOOM_ENABLED +
                        " = 1 WHERE " + RunsColumns.AUTO_ZOOM_ENABLED + " IS NULL");
                version = 17;
            }

            if (version == 17 && version < newVersion) {
                createSensorTriggersTable(db);
                version = 18;
            }
        }

        private void createProjectsTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + Tables.PROJECTS + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + ProjectColumns.PROJECT_ID + " TEXT NOT NULL, "
                    + ProjectColumns.TITLE + " TEXT, "
                    + ProjectColumns.DESCRIPTION + " TEXT, "
                    + ProjectColumns.COVER_PHOTO + " TEXT, "
                    + ProjectColumns.ARCHIVED + " BOOLEAN NOT NULL DEFAULT 0, "
                    + ProjectColumns.LAST_USED_TIME + " INTEGER, "
                    + "UNIQUE (" + ProjectColumns.PROJECT_ID + ") ON CONFLICT REPLACE)");
        }

        private void createLabelsTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + Tables.LABELS + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + LabelColumns.TIMESTAMP + " INTEGER NOT NULL, "
                    + LabelColumns.EXPERIMENT_ID + " TEXT NOT NULL, "
                    + LabelColumns.TYPE + " TEXT NOT NULL, "
                    + LabelColumns.DATA + " TEXT, "
                    + LabelColumns.LABEL_ID + " TEXT NOT NULL, "
                    + LabelColumns.START_LABEL_ID + " TEXT,"
                    + LabelColumns.VALUE + " BLOB)");
        }

        private void createSensorsTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + Tables.EXTERNAL_SENSORS + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + SensorColumns.TYPE + " TEXT NOT NULL,"
                    + SensorColumns.SENSOR_ID + " TEXT UNIQUE,"
                    + SensorColumns.NAME + " TEXT NOT NULL,"
                    + SensorColumns.CONFIG + " BLOB)");
        }

        private void createExperimentSensorsTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + Tables.EXPERIMENT_SENSORS + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + ExperimentSensorColumns.SENSOR_TAG + " TEXT,"
                    + ExperimentSensorColumns.EXPERIMENT_ID + " TEXT)");
        }

        private void createRunStatsTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + Tables.RUN_STATS + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + RunStatsColumns.START_LABEL_ID + " TEXT,"
                    + RunStatsColumns.SENSOR_TAG + " TEXT,"
                    + RunStatsColumns.STAT_NAME + " TEXT,"
                    + RunStatsColumns.STAT_VALUE + " REAL, "
                    + "UNIQUE(" + RunStatsColumns.START_LABEL_ID + ","
                    + RunStatsColumns.SENSOR_TAG + "," + RunStatsColumns
                    .STAT_NAME + ") ON CONFLICT REPLACE)");
        }

        private void createRunsTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + Tables.RUNS + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + RunsColumns.RUN_ID + " TEXT UNIQUE,"
                    + RunsColumns.RUN_INDEX + " INTEGER,"
                    + RunsColumns.TIMESTAMP + " INTEGER NOT NULL,"
                    + RunsColumns.TITLE + " TEXT,"
                    + RunsColumns.ARCHIVED + " BOOLEAN,"
                    + RunsColumns.AUTO_ZOOM_ENABLED + " BOOLEAN NOT NULL DEFAULT 1)");
        }

        private void createRunSensorsTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + Tables.RUN_SENSORS + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + RunSensorsColumns.RUN_ID + " TEXT,"
                    + RunSensorsColumns.SENSOR_ID + " TEXT,"
                    + RunSensorsColumns.LAYOUT + " BLOB,"
                    + RunSensorsColumns.POSITION + " INTEGER,"
                    + "UNIQUE(" + RunSensorsColumns.RUN_ID + ","
                    + RunSensorsColumns.SENSOR_ID + "))");
        }

        private void createExperimentSensorLayoutTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + Tables.EXPERIMENT_SENSOR_LAYOUT + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + ExperimentSensorLayoutColumns.EXPERIMENT_ID + " TEXT,"
                    + ExperimentSensorLayoutColumns.POSITION + " INTEGER,"
                    + ExperimentSensorLayoutColumns.LAYOUT + " BLOB,"
                    + "UNIQUE(" + ExperimentSensorLayoutColumns.EXPERIMENT_ID + ","
                    + ExperimentSensorLayoutColumns.POSITION + "))");
        }

        private void createSensorTriggersTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + Tables.SENSOR_TRIGGERS + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + SensorTriggerColumns.TRIGGER_ID + " TEXT UNIQUE,"
                    + SensorTriggerColumns.EXPERIMENT_ID + " TEXT,"
                    + SensorTriggerColumns.LAST_USED_TIMESTAMP_MS + " INTEGER,"
                    + SensorTriggerColumns.SENSOR_ID + " TEXT,"
                    + SensorTriggerColumns.TRIGGER_INFORMATION + " BLOB)"
            );
        }

        private void populateUpgradedRunsTable(SQLiteDatabase db) {
            db.execSQL("INSERT INTO " + Tables.RUNS + " SELECT (" + LabelColumns.START_LABEL_ID +
                    ") FROM " + Tables.LABELS + " WHERE " + LabelColumns.START_LABEL_ID +
                    " = " + LabelColumns.LABEL_ID);
        }
    }

    private static final String STABLE_ID_CHARS = "abcdefghijklmnopqrstuvwxyz" +
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /**
     * Creates a stable ID of alphanumeric characters. This is more useful than autoincremented
     * row IDs from the database because those can get moved around due to account data
     * synchronization or sync adapter munging.
     *
     * @return a stable ID which is a random String of alphanumeric characters at the desired
     * length.
     */
    private String newStableId(int length) {
        Random random = new Random();
        StringBuilder builder = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            builder.append(STABLE_ID_CHARS.charAt(random.nextInt(STABLE_ID_CHARS.length())));
        }
        return builder.toString();
    }
}
