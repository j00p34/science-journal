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
package android.util;

public class Log {
    /**
     * Priority constant for the println method; use Log.e.
     */
    public static final int ERROR = 6;

    public static boolean isLoggable(String tag, int level) {
        return level == ERROR;
    }

    public static int e(String tag, String message) {
        throw new RuntimeException(tag + ": " + message);
    }

    public static int e(String tag, String message, Exception e) {
        throw new RuntimeException(tag + ": " + message, e);
    }
}
