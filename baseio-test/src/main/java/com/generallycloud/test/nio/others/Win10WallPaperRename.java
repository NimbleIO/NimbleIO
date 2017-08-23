/*
 * Copyright 2015-2017 GenerallyCloud.com
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
package com.generallycloud.test.nio.others;

import java.io.File;

import com.generallycloud.baseio.log.DebugUtil;

/**
 * @author wangkai
 *
 */
public class Win10WallPaperRename {

    public static void main(String[] args) {

        rename("D://Program Files/DeskBizhi/temp");

    }

    public static void rename(String path) {

        File root = new File(path);
        if (!root.exists()) {
            DebugUtil.info("file not exists :{}", root.getAbsolutePath());
            return;
        }

        File[] fs = root.listFiles();
        if (fs == null) {
            DebugUtil.info("not files :{}", root.getAbsolutePath());
            return;
        }

        for (File f : root.listFiles()) {
            if (f.getName().endsWith(".png")) {
                continue;
            }
            f.renameTo(new File(f.getAbsolutePath() + ".png"));
            DebugUtil.info("rename: {}", f.getName());
        }

    }

}