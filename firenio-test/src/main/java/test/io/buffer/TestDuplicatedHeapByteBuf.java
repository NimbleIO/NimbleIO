/*
 * Copyright 2015 The FireNio Project
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
package test.io.buffer;

import org.junit.Test;

import com.firenio.buffer.ByteBuf;
import com.firenio.buffer.ByteBufAllocator;
import com.firenio.common.Assert;

/**
 * @author wangkai
 */
public class TestDuplicatedHeapByteBuf {

    static final String data = "abcdef";

    static void v(ByteBuf buf) {

        Assert.expectEquals(new String(buf.readBytes()), data);

    }

    @Test
    public void testDirect() {
        ByteBuf buf = ByteBuf.direct(16);

        buf.writeBytes(data.getBytes());
        ByteBuf buf2 = buf.duplicate();
        v(buf2);
    }

    @Test
    public void testDirectP() throws Exception {
        ByteBufAllocator a   = TestAllocUtil.direct();
        ByteBuf          buf = a.allocate(16);

        buf.writeBytes(data.getBytes());
        ByteBuf buf2 = buf.duplicate();
        v(buf2);
    }

    @Test
    public void testHeap() {
        ByteBuf buf = ByteBuf.direct(16);

        buf.writeBytes(data.getBytes());
        ByteBuf buf2 = buf.duplicate();
        v(buf2);
    }

    @Test
    public void testHeapP() throws Exception {
        ByteBufAllocator a   = TestAllocUtil.heap();
        ByteBuf          buf = a.allocate(16);

        buf.writeBytes(data.getBytes());
        ByteBuf buf2 = buf.duplicate();
        v(buf2);
    }

}
