/*
 * Copyright 2002-2013 the original author or authors.
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

package org.springframework.web.socket.sockjs.transport;

import java.io.IOException;
import java.nio.charset.Charset;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.sockjs.SockJsConfiguration;
import org.springframework.web.socket.sockjs.SockJsFrame.DefaultFrameFormat;
import org.springframework.web.socket.sockjs.SockJsFrame.FrameFormat;
import org.springframework.web.socket.sockjs.TransportErrorException;
import org.springframework.web.socket.sockjs.TransportHandler;
import org.springframework.web.socket.sockjs.TransportType;
import org.springframework.web.util.JavaScriptUtils;

/**
 * An HTTP {@link TransportHandler} that uses a famous browsder document.domain technique:
 * <a href="http://stackoverflow.com/questions/1481251/what-does-document-domain-document-domain-do">
 * 		http://stackoverflow.com/questions/1481251/what-does-document-domain-document-domain-do</a>
 *
 * @author Rossen Stoyanchev
 * @since 4.0
 */
public class HtmlFileTransportHandler extends AbstractHttpSendingTransportHandler {

	private static final String PARTIAL_HTML_CONTENT;

	// Safari needs at least 1024 bytes to parse the website.
	// http://code.google.com/p/browsersec/wiki/Part2#Survey_of_content_sniffing_behaviors
	private static final int MINIMUM_PARTIAL_HTML_CONTENT_LENGTH = 1024;

	static {
		StringBuilder sb = new StringBuilder(
				"<!doctype html>\n" +
				"<html><head>\n" +
				"  <meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\" />\n" +
				"  <meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />\n" +
				"</head><body><h2>Don't panic!</h2>\n" +
				"  <script>\n" +
				"    document.domain = document.domain;\n" +
				"    var c = parent.%s;\n" +
				"    c.start();\n" +
				"    function p(d) {c.message(d);};\n" +
				"    window.onload = function() {c.stop();};\n" +
				"  </script>"
				);

		while(sb.length() < MINIMUM_PARTIAL_HTML_CONTENT_LENGTH) {
			sb.append(" ");
		}

		PARTIAL_HTML_CONTENT = sb.toString();
	}


	@Override
	public TransportType getTransportType() {
		return TransportType.HTML_FILE;
	}

	@Override
	protected MediaType getContentType() {
		return new MediaType("text", "html", Charset.forName("UTF-8"));
	}

	@Override
	public StreamingSockJsSession createSession(String sessionId, WebSocketHandler handler) {
		return new HtmlFileStreamingSockJsSession(sessionId, getSockJsConfig(), handler);
	}

	@Override
	public void handleRequestInternal(ServerHttpRequest request, ServerHttpResponse response,
			AbstractHttpSockJsSession session) throws TransportErrorException {

		try {
			String callback = request.getQueryParams().getFirst("c");
			if (! StringUtils.hasText(callback)) {
				response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
				response.getBody().write("\"callback\" parameter required".getBytes("UTF-8"));
				return;
			}
		}
		catch (Throwable t) {
			throw new TransportErrorException("Failed to send error to client", t, session.getId());
		}
		super.handleRequestInternal(request, response, session);
	}

	@Override
	protected FrameFormat getFrameFormat(ServerHttpRequest request) {
		return new DefaultFrameFormat("<script>\np(\"%s\");\n</script>\r\n") {
			@Override
			protected String preProcessContent(String content) {
				return JavaScriptUtils.javaScriptEscape(content);
			}
		};
	}


	private final class HtmlFileStreamingSockJsSession extends StreamingSockJsSession {

		private HtmlFileStreamingSockJsSession(String sessionId, SockJsConfiguration config, WebSocketHandler handler) {
			super(sessionId, config, handler);
		}

		@Override
		protected void writePrelude() throws IOException {
			// we already validated the parameter..
			String callback = getRequest().getQueryParams().getFirst("c");

			String html = String.format(PARTIAL_HTML_CONTENT, callback);
			getResponse().getBody().write(html.getBytes("UTF-8"));
			getResponse().flush();
		}
	}

}