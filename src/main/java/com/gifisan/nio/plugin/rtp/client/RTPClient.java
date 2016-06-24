package com.gifisan.nio.plugin.rtp.client;

import java.io.IOException;

import com.gifisan.nio.client.ConnectorSession;
import com.gifisan.nio.client.UDPConnector;
import com.gifisan.nio.common.ByteUtil;
import com.gifisan.nio.component.ApplicationContextUtil;
import com.gifisan.nio.component.future.ReadFuture;
import com.gifisan.nio.component.protocol.DatagramPacket;
import com.gifisan.nio.plugin.jms.JMSException;
import com.gifisan.nio.plugin.jms.MapMessage;
import com.gifisan.nio.plugin.jms.client.MessageProducer;
import com.gifisan.nio.plugin.jms.client.impl.DefaultMessageProducer;
import com.gifisan.nio.plugin.jms.client.impl.FixedMessageConsumer;
import com.gifisan.nio.plugin.jms.client.impl.OnMappedMessage;
import com.gifisan.nio.plugin.rtp.RTPException;
import com.gifisan.nio.plugin.rtp.server.RTPCreateRoomServlet;
import com.gifisan.nio.plugin.rtp.server.RTPJoinRoomServlet;
import com.gifisan.nio.server.NIOContext;
import com.gifisan.security.Authority;

public class RTPClient {

	public static final String	CURRENT_MARK	= "CURRENT_MARK";
	public static final String	GROUP_SIZE	= "GROUP_SIZE";
	public static final String	MARK_INTERVAL	= "MARK_INTERVAL";

	private UDPConnector		connector		= null;
	private FixedMessageConsumer	consumer		= null;
	private NIOContext			context		= null;
	private String				inviteUsername	= null;
	private MessageProducer		producer		= null;
	private String				roomID		= null;
	private ConnectorSession		session		= null;
	private RTPHandle			handle		= null;

	public RTPClient(ConnectorSession session, UDPConnector connector) {
		this(session, connector, new FixedMessageConsumer(session), new DefaultMessageProducer(session));
	}

	// FIXME listen onf break
	public RTPClient(ConnectorSession session, UDPConnector connector, FixedMessageConsumer consumer,
			MessageProducer producer) {
		this.connector = connector;
		this.session = session;
		this.producer = producer;
		this.consumer = consumer;
		this.context = connector.getContext();
	}

	public void setRTPHandle(final RTPHandle handle) throws RTPException {

		if (this.handle != null) {
			return;
		}

		this.consumer.listen("invite", new OnMappedMessage() {

			public void onReceive(MapMessage message) {
				handle.onInvite(RTPClient.this, message);
			}
		});

		this.consumer.listen("invite-reply", new OnMappedMessage() {

			public void onReceive(MapMessage message) {
				handle.onInviteReplyed(RTPClient.this, message);
			}
		});

		this.consumer.listen("break", new OnMappedMessage() {

			public void onReceive(MapMessage message) {
				handle.onBreak(RTPClient.this, message);
			}
		});

		this.handle = handle;

		try {

			this.consumer.receive(null);
		} catch (JMSException e) {
			throw new RTPException(e);
		}
	}

	public RTPHandle getRTPHandle() {
		return handle;
	}

	public boolean createRoom(String inviteUsername) throws RTPException {

		ReadFuture future;

		try {
			future = session.request(RTPCreateRoomServlet.SERVICE_NAME, null);
		} catch (IOException e) {
			throw new RTPException(e.getMessage(), e);
		}

		String roomID = future.getText();

		if ("-1".equals(roomID)) {
			throw new RTPException("create room failed");
		}

		this.roomID = roomID;

		this.inviteCustomer(inviteUsername);

		return true;
	}

	protected NIOContext getContext() {
		return context;
	}

	public String getInviteUsername() {
		return inviteUsername;
	}

	public void inviteCustomer(String inviteUsername) throws RTPException {

		if (roomID == null) {
			throw new RTPException("none roomID,create room first");
		}
		
		Authority authority = ApplicationContextUtil.getAuthority(session);

		MapMessage message = new MapMessage("msgID", inviteUsername);

		message.put("eventName", "invite");
		message.put("roomID", roomID);
		message.put("inviteUsername", authority.getUsername());

		try {
			producer.offer(message);

		} catch (JMSException e) {
			throw new RTPException(e);
		}

		this.inviteUsername = inviteUsername;
	}

	public void inviteReply(String inviteUsername, int markinterval, long currentMark, int groupSize)
			throws RTPException {

		MapMessage message = new MapMessage("msgID", inviteUsername);

		message.put("eventName", "invite-reply");
		message.put(MARK_INTERVAL, markinterval);
		message.put(CURRENT_MARK, currentMark);
		message.put(GROUP_SIZE, groupSize);

		try {
			producer.offer(message);
		} catch (JMSException e) {
			throw new RTPException(e);
		}

		this.inviteUsername = inviteUsername;
	}

	public boolean joinRoom(String roomID) throws RTPException {
		try {

			ReadFuture future = session.request(RTPJoinRoomServlet.SERVICE_NAME, roomID);

			return ByteUtil.isTrue(future.getText());
		} catch (IOException e) {
			throw new RTPException(e.getMessage(), e);
		}
	}

	public boolean leaveRoom() throws RTPException {
		try {
			
			Authority authority = ApplicationContextUtil.getAuthority(session);

			ReadFuture future = session.request(RTPJoinRoomServlet.SERVICE_NAME, roomID);

			this.handle.onBreak(this, new MapMessage("", authority.getUuid()));

			return ByteUtil.isTrue(future.getText());
		} catch (IOException e) {
			throw new RTPException(e.getMessage(), e);
		}
	}

	public void sendDatagramPacket(DatagramPacket packet) throws RTPException {

		if (roomID == null) {
			throw new RTPException("none roomID,create room first");
		}

		connector.sendDatagramPacket(packet);
	}

	public void setRoomID(String roomID) {
		this.roomID = roomID;
	}

	public String getRoomID() {
		return roomID;
	}
}
