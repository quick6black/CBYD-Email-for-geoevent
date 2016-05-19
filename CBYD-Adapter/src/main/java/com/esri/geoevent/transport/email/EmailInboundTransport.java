/*
  Copyright 1995-2016 Esri

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

  For additional information, contact:
  Environmental Systems Research Institute, Inc.
  Attn: Contracts Dept
  380 New York Street
  Redlands, California, USA 92373

  com.esri.geoevent.transport.email: contracts@esri.com
*/

package com.esri.geoevent.transport.email;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;

import javax.mail.BodyPart;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.search.FlagTerm;
import javax.mail.search.SearchTerm;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.esri.ges.core.component.ComponentException;
import com.esri.ges.core.component.RunningException;
import com.esri.ges.core.component.RunningState;
import com.esri.ges.transport.InboundTransportBase;
import com.esri.ges.transport.TransportDefinition;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

public class EmailInboundTransport extends InboundTransportBase implements
		Runnable {
	private static final Log log = LogFactory
			.getLog(EmailInboundTransport.class);

	private int eventSize = 5000;
	private int eventRate = 1;
	private String mailImapsPort = "993";
	private String mailStoreProtocol = "imaps";
	private String host = ""; // "redowa.esri.com";
	private String user = "";
	private String password = "";
	private Thread thread = null;
	private ByteBuffer byteBuffer = ByteBuffer.allocate(eventSize);
	private String channelId = "1";
	private int emailReadRate = 1 * 60; // second
	// private String keyword = "Locate Request";
	private String emailSubfolder = ""; // "INBOX/GEP";
	private static List<String> keyCodes = generateKeycodeList();

	public EmailInboundTransport(TransportDefinition definition)
			throws ComponentException {
		super(definition);
	}

	public void applyProperties() throws Exception {
		if (getProperty("eventSize").isValid()) {
			int value = (Integer) getProperty("eventSize").getValue();
			if (value > 0 && value != eventSize) {
				eventSize = value;
				byteBuffer = ByteBuffer.allocate(eventSize);
			}
		}
		if (getProperty("eventRate").isValid()) {
			int value = (Integer) getProperty("eventRate").getValue();
			if (value > 0 && value != eventRate) {
				eventRate = value;
			}
		}
		if (getProperty("mailImapsPort").isValid()) {
			String value = (String) getProperty("mailImapsPort").getValue();
			if (value != null && !value.isEmpty()
					&& !value.equals(mailImapsPort)) {
				mailImapsPort = value;
			}
		}
		if (getProperty("mailStoreProtocol").isValid()) {
			String value = (String) getProperty("mailStoreProtocol").getValue();
			if (value != null && !value.isEmpty()
					&& !value.equals(mailStoreProtocol)) {
				mailStoreProtocol = value;
			}
		}
		if (getProperty("host").isValid()) {
			String value = (String) getProperty("host").getValue();
			if (value != null && !value.isEmpty() && !value.equals(host)) {
				host = value;
			}
		}
		if (getProperty("user").isValid()) {
			String value = (String) getProperty("user").getValue();
			if (value != null && !value.isEmpty() && !value.equals(user)) {
				user = value;
			}
		}
		if (getProperty("password").isValid()) {
			String value = (String) getProperty("password").getValue();
			if (value != null && !value.isEmpty() && !value.equals(password)) {
				password = value;
			}
		}
		if (getProperty("emailReadRate").isValid()) {
			int value = (Integer) getProperty("emailReadRate").getValue();
			if (value > 0 && value != emailReadRate) {
				emailReadRate = value;
			}
		}
		if (getProperty("emailSubfolder").isValid()) {
			String value = (String) getProperty("emailSubfolder").getValue();
			if (value != null && !value.isEmpty()
					&& !value.equals(emailSubfolder)) {
				emailSubfolder = value;
			}
		}
	}

	public void run() {
		try {
			applyProperties();
			setRunningState(RunningState.STARTED);

			while (getRunningState() == RunningState.STARTED) {
				try {
					String parsedMailBodys = this.getMailBodys();

					if (parsedMailBodys != null && !parsedMailBodys.isEmpty()) {						
						byteBuffer = ByteBuffer
								.wrap(parsedMailBodys.getBytes());
						byteListener.receive(byteBuffer, channelId);

						byteBuffer.compact();
					}
					// Such as Thread.sleep(1*60*1000);
					Thread.sleep(emailReadRate * 1000); // To millisecond
				} catch (BufferOverflowException boe) {
					log.error(
							"Buffer overflow.  Flushing the buffer and continuing.",
							boe);
					byteBuffer.clear();
				} catch (Exception e) {
					log.error("Unexpected error, stopping the Transport.", e);
					stop();
				}
			}
		} catch (Throwable ex) {
			log.error(ex);
			setRunningState(RunningState.ERROR);
		}
	}

	/*
	 * Currently this only returns one mail body
	 */
	/**
	 * @return
	 */
	private String getMailBodys() {

		// mail server connection parameters
		//String host = "redowa.esri.com";
		//String user = "*****";
		//String password = "*****74!";
		//String emailSubfolder = "inbox/gep";
		log.info("++++++++++++++++++++ Email read started");

		String mailBodies = "";
		StringBuilder stringBuilder = new StringBuilder();
		try {
			// connect to my IMAP inbox
			Properties props = new Properties();
			props.setProperty("mail.imaps.port", mailImapsPort);
			props.setProperty("mail.store.protocol", mailStoreProtocol);
			//props.setProperty("mail.imaps.port", "993");
			//props.setProperty("mail.store.protocol", "imaps");

			Session session = Session.getInstance(props, null);
			Store store = session.getStore();
			store.connect(host, user, password);
			Folder inbox = store.getFolder(emailSubfolder);
			inbox.open(Folder.READ_WRITE);

			Flags seen = new Flags(Flags.Flag.SEEN);
			SearchTerm unseenFlagTerm = new FlagTerm(seen, false);
			Message[] messages = inbox.search(unseenFlagTerm);

			// get the list of inbox messages
			// Message msg = inbox.getMessage(inbox.getMessageCount());

			if (messages.length == 0) {
				log.info("No messages found.");
				return mailBodies;
			} else if (messages.length > 0) {
				for (int i = 0; i < messages.length; i++) {
					String mailBody = "";
					Message msg = messages[i];
					mailBody = parseMailBody(msg);
					stringBuilder.append(mailBody);
				}
			}

			mailBodies = stringBuilder.toString();
			log.info(messages.length
					+ " emails are read out to GeoEvent channel");
			log.info("Parsed Mail bodies: " + mailBodies);
			inbox.close(true);
			store.close();
		} catch (Exception mex) {
			log.error("Can't read com.esri.geoevent.transport.email from " + emailSubfolder
					+ ". Please check the issue.");
			log.error(mex);
		}

		return mailBodies;
	}

	private String parseMailBody(Message msg) {
		StringBuilder builder = new StringBuilder();
		//builder.append("GeoEventDef,");
		
		String mailBody = "";
		//String requestType = "";

		try {
			Object content = msg.getContent();
			String contentType = msg.getContentType();
			log.info("Content Type: " + contentType);

			if (content instanceof String) {
				mailBody = (String) content;
			} else if (content instanceof Multipart) {
				Multipart mp = (Multipart) content;
				BodyPart bp = mp.getBodyPart(0);
				mailBody = bp.getContent().toString();
			}

			if (contentType.equalsIgnoreCase("text/html; charset=us-ascii")) {
				mailBody = parseToTextMailBody(mailBody);
			}
			
			//log.info(mailBody);

			for (int i = 0; i < keyCodes.size(); i++) {
				String value = "";
				if (i == 0) {
					value = StringUtils.substringBetween(mailBody,
							keyCodes.get(i), keyCodes.get(i));
					//requestType = StringUtils.trim(value);
				}
				if (i == 1 || i == 4 || i == 10)
					value = StringUtils.substringBetween(mailBody,
							keyCodes.get(i), " ");
				if (i == 3) {
					value = StringUtils.substringBetween(mailBody,
							keyCodes.get(i), "\n");
					if (value == null)
						value = StringUtils.substringBetween(mailBody,
								keyCodes.get(i), " ");
				}				
				if (i == 2 || (i > 4 && i < 10) || i == 11) {
					value = StringUtils.substringBetween(mailBody,
							keyCodes.get(i), "\n");
				}
				/*if (i == 10) {
					value = StringUtils.substringBetween(mailBody,
							keyCodes.get(i), "-------");
					if (value == null)
						value = StringUtils.substringBetween(mailBody,
								"REMARKS:", "CONTACT");
					if (value == null)
						value = StringUtils.substringBetween(mailBody,
								"REMARKS:", " ");
				}*/
				
				if(value == null)
					value = "";
				
				if (i != keyCodes.size() - 1)
					builder.append(value.trim() + ",");
				else
					builder.append(value.trim() + "\n");	
				
			}
			log.debug("Builder: " + builder.toString());
		} catch (MessagingException e) {
			log.error("Can't parse the mail message.");
			log.error(e);
		} catch (IOException e) {
			log.error("Can't read out the mail body, please check your mail server and connection.");
			log.error(e);
		}
		return builder.toString();
	}

	private static List<String> generateKeycodeList() {
		List<String> keys = new ArrayList<String>();
		keys.add("*****"); // Request type 0
		keys.add("TIME.."); // 1
		keys.add("DATE.."); // 2
		keys.add("REQUEST NO..."); // 3
		keys.add("LATITUDE.."); // 4
		keys.add("LONGITUDE.."); // 5
		keys.add("TOWN......"); // 6
		keys.add("ADDRESS..."); // 7
		keys.add("STREET...."); // 8
		keys.add("CROSS STREET.."); // 9
		//keys.add("REMARKS:\n"); // 10
		keys.add("START DATE......"); // 10
		keys.add("START TIME.."); // 11
		// keyCodes.add("TIME");
		// keyCodes.add("TIME");
		// keyCodes.add("TIME");
		// keyCodes.add("TIME");
		return keys;
	}

	@SuppressWarnings("incomplete-switch")
	public void start() throws RunningException {
		switch (getRunningState()) {
		case STARTING:
		case STARTED:
		case STOPPING:			
			return;
		}
		setRunningState(RunningState.STARTING);
		thread = new Thread(this);
		thread.start();
	}

	public static void main(String[] args) {
		 //getMailBodys();
	}

	private static class SentDateSearchTerm extends SearchTerm {
		private Date afterDate;

		public SentDateSearchTerm(Date afterDate) {
			this.afterDate = afterDate;
		}

		@Override
		public boolean match(Message message) {
			try {
				if (message.getSentDate().after(afterDate)) {
					return true;
				}
			} catch (MessagingException ex) {
				ex.printStackTrace();
			}
			return false;
		}
	}

	private String parseToTextMailBody(String htmlMailBody) {
		StringBuilder builder = new StringBuilder();

		htmlMailBody = StringUtils.remove(htmlMailBody, "&nbsp;");
		String[] stringArray = StringUtils.substringsBetween(htmlMailBody,
				"<p class=\"MsoPlainText\">", "<o:p></o:p></p>");

		for (String each : stringArray) {
			builder.append(each + "\n");
		}

		log.info("parsed html body: " + builder.toString());
		 
		return builder.toString();
	}
	

	@Override
	public synchronized void stop() {
		if (getRunningState() == RunningState.STARTED) {
			setRunningState(RunningState.STOPPING);			
			thread.interrupt();
			thread = null;
		}
		setRunningState(RunningState.STOPPED);
	}
}