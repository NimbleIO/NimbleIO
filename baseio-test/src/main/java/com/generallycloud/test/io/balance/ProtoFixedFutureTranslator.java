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
package com.generallycloud.test.io.balance;

import com.generallycloud.baseio.balance.BalanceFuture;
import com.generallycloud.baseio.balance.FutureTranslator;
import com.generallycloud.baseio.balance.facade.FacadeSocketSession;
import com.generallycloud.baseio.balance.reverse.ReverseSocketSession;
import com.generallycloud.baseio.codec.fixedlength.FixedLengthFuture;
import com.generallycloud.baseio.codec.fixedlength.FixedLengthFutureImpl;
import com.generallycloud.baseio.codec.protobase.ProtobaseFuture;

/**
 * @author wangkai
 *
 */
public class ProtoFixedFutureTranslator implements FutureTranslator {

    @Override
    public BalanceFuture translateIn(FacadeSocketSession fs, ReverseSocketSession rs,
            BalanceFuture future) {
        
        
        
        return null;
    }

    @Override
    public BalanceFuture translateOut(ReverseSocketSession rs, BalanceFuture future) {
        ProtobaseFuture in = (ProtobaseFuture) future;
        FixedLengthFuture out = new FixedLengthFutureImpl();
        out.write(in.getReadText(), rs);
//        return out;
        return null;
    }
    
}
