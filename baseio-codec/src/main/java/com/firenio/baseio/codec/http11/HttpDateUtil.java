/*
 * Copyright 2015 The Baseio Project
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.firenio.baseio.codec.http11;

import com.firenio.baseio.common.DateUtil;
import com.firenio.baseio.common.Util;

/**
 * @author wangkai
 *
 */
public class HttpDateUtil {

    private static final HttpDateTimeClock         CLOCK = new HttpDateTimeClock();
    private static boolean                         inited;

    private static final ThreadLocal<HttpDateUtil> LOCAL;

    static {

        LOCAL = new ThreadLocal<HttpDateUtil>() {

            protected HttpDateUtil initialValue() {
                return new HttpDateUtil();
            }
        };

    }

    private long   time;
    private byte[] value;

    private static class HttpDateTimeClock implements Runnable {

        volatile boolean running;

        volatile long    time;

        @Override
        public void run() {
            running = true;
            for (; running;) {
                time = System.currentTimeMillis();
                Util.sleep(1000);
            }
        }

        void stop() {
            running = false;
            Thread.currentThread().interrupt();
        }

    }

    public static byte[] getDate() {
        HttpDateUtil u = LOCAL.get();
        long now = CLOCK.time;
        if (now > u.time) {
            u.time = now + 1000;
            u.value = DateUtil.get().formatHttpBytes(now);
        }
        return u.value;
    }

    public static synchronized void start() {
        if (!inited) {
            Util.exec(CLOCK, "http-date-clock");
        }
    }

    public static synchronized void stop() {
        if (inited) {
            CLOCK.stop();
        }
    }

}
