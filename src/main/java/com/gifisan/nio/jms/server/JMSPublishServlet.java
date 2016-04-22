package com.gifisan.nio.jms.server;

import java.io.OutputStream;

import com.gifisan.nio.common.ByteUtil;
import com.gifisan.nio.component.BufferedOutputStream;
import com.gifisan.nio.jms.Message;
import com.gifisan.nio.server.session.NIOSession;

public class JMSPublishServlet extends JMSServlet {

	public void accept(NIOSession session, JMSSessionAttachment attachment) throws Exception {

		MQContext context = getMQContext();

		if (context.isLogined(session)) {
			
			if (session.isStream()) {
				
				OutputStream outputStream = session.getServerOutputStream();
				
				if (outputStream == null) {
					session.setServerOutputStream(new BufferedOutputStream());
					return;
				}
			}
			
			Message message = context.parse(session);

			context.publishMessage(message);

			session.write(ByteUtil.TRUE);

		} else {

			session.write("用户未登录！");

		}

		session.flush();

	}

}