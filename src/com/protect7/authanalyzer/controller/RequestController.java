package com.protect7.authanalyzer.controller;

/**
 * The RequestController processes each HTTP message which is not previously rejected due to filter specification. The RequestController
 * extracts the defined values (CSRF Token and Grep Rules) and modifies the given HTTP Message for each session. Furthermore, the
 * RequestController is responsible for analyzing the response and declare the BYPASS status according to the specified definitions.
 * 
 * @author Simon Reinhart
 */

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.protect7.authanalyzer.entities.AnalyzerRequestResponse;
import com.protect7.authanalyzer.entities.Rule;
import com.protect7.authanalyzer.entities.Session;
import com.protect7.authanalyzer.util.BypassConstants;
import com.protect7.authanalyzer.util.CurrentConfig;
import com.protect7.authanalyzer.util.Logger;

import burp.IBurpExtenderCallbacks;
import burp.IHttpRequestResponse;
import burp.IParameter;
import burp.IRequestInfo;
import burp.IResponseInfo;

public class RequestController {

	private final IBurpExtenderCallbacks callbacks;
	private final CurrentConfig config = CurrentConfig.getCurrentConfig();
	private String currentOriginalCsrfValue = "";
	private final Logger logger;

	public RequestController(IBurpExtenderCallbacks callbacks) {
		this.callbacks = callbacks;
		if(callbacks != null) {
			this.logger = new Logger(new PrintWriter(callbacks.getStdout(), true));
		}
		else {
			this.logger = new Logger(new PrintWriter(System.out, true));
		}
	}

	public synchronized void analyze(IHttpRequestResponse originalMessageInfo) {
		// Fail-Safe - Check if messageInfo can be processed
		if (originalMessageInfo == null || originalMessageInfo.getRequest() == null || originalMessageInfo.getResponse() == null) {
			logger.writeLog(Logger.SEVERITY.WARNING, "Cannot analyze request with null values.");
		}
		else {
			logger.writeMarker();
			IRequestInfo originalRequestInfo = callbacks.getHelpers().analyzeRequest(originalMessageInfo);
			logger.writeLog(Logger.SEVERITY.INFO, "Handle New Request: " + originalRequestInfo.getUrl());
			
			// Extract original CSRF value if first session has replacement set
			Session firstSession = config.getSessions().get(0);
			if (!firstSession.getCsrfTokenName().equals("") && firstSession.getStaticCsrfTokenValue().equals("")) {
				logger.writeLog(Logger.SEVERITY.INFO, "Extract Original CSRF Token");
				extractOriginalCsrfValue(originalMessageInfo);
			}
		
			String originalMessageBody = getRequestBodyAsString(originalMessageInfo);
			
			int mapId = config.getNextMapId();
			boolean success = true;
			for(Session session : config.getSessions()) {
				if(session.isFilterRequestsWithSameHeader() && isSameHeader(originalRequestInfo.getHeaders(), session)) {
					// No other session will be analyzed if one session has same header. The assumption is that the given request was send
					// automatically by the application we are navigating through. We do not want to analyze such a request.
					success = false;
					break;
				}
				else {
					logger.writeLog(Logger.SEVERITY.INFO, "**** Handle Session: " + session.getName() + " ****");
					logger.writeLog(Logger.SEVERITY.INFO, "Modify Request Body");
					String modifiedMessageBody = getModifiedMessageBody(originalMessageBody, originalRequestInfo.getContentType() , session);
					logger.writeLog(Logger.SEVERITY.INFO, "Modify Request Header");
					// Headers must be modified after Message Body to calculate Content-Length
					ArrayList<String> modifiedHeaders = getModifiedHeaders(originalRequestInfo, session, modifiedMessageBody.length());
					logger.writeLog(Logger.SEVERITY.INFO, "Create bytestream");
					byte[] message = callbacks.getHelpers().buildHttpMessage(modifiedHeaders, modifiedMessageBody.getBytes());
					// Perform modified request
					logger.writeLog(Logger.SEVERITY.INFO, "Make http Request");
					IHttpRequestResponse modifiedMessageInfo = callbacks.makeHttpRequest(originalMessageInfo.getHttpService(), message);
					logger.writeLog(Logger.SEVERITY.INFO, "Response received");
					// Analyze Response of modified Request
					if (modifiedMessageInfo.getRequest() != null && modifiedMessageInfo.getResponse() != null) {
						// Extract CSRF Token
						if (!session.getCsrfTokenName().equals("") && session.getStaticCsrfTokenValue().equals("")) {
							logger.writeLog(Logger.SEVERITY.INFO, "Extract CSRF Token");
							extractCurrentCsrfValue(modifiedMessageInfo, session);
						}
						
						//Extract Rules Values
						extractResponseRuleValues(session, modifiedMessageInfo.getResponse());

						logger.writeLog(Logger.SEVERITY.INFO, "Analyze bypass status");
						BypassConstants bypassConstant = analyzeResponse(originalMessageInfo, modifiedMessageInfo);
						if (bypassConstant != null) {
							AnalyzerRequestResponse analyzerRequestResponse = new AnalyzerRequestResponse(modifiedMessageInfo, bypassConstant);
							session.putRequestResponse(mapId, analyzerRequestResponse);
						}
						else {
							// Fail-Safe
							success = false;
							logger.writeLog(Logger.SEVERITY.WARNING, "Cannot analyze if BYPASSED.");
							break;
						}
					}
					else {
						// Fail-Safe
						success = false;
						logger.writeLog(Logger.SEVERITY.WARNING, "Modified Request / Response has null value");
						break;
					}
				}
			}
			if(success) {
				config.getTableModel().putNewRequestResponse(mapId, originalMessageInfo);
				logger.writeLog(Logger.SEVERITY.INFO, "Analyze finished. Request added to Table.");
			}
		}
	}
	
	public boolean isSameHeader(List<String> headers, Session session) {
		String[] headersToReplace = session.getHeadersToReplace().split("\n");
		boolean requestContainsHeader = true;
		for (String headerToReplace : headersToReplace) {
			if(!headers.contains(headerToReplace)) {
				requestContainsHeader = false;
			}
		}
		if(requestContainsHeader) {
			logger.writeLog(Logger.SEVERITY.INFO, "Request filtered due to same header");
			// Update Session Panel
			session.getStatusPanel().incrementAmountOfFitleredRequests();
			return true;
		}
		return false;
	}

	//need content-length, header as string
	public ArrayList<String> getModifiedHeaders(IRequestInfo originalRequestInfo, Session session, int bodyLength) {
		ArrayList<String> headers = (ArrayList<String>) originalRequestInfo.getHeaders();
		String[] headersToReplace = session.getHeadersToReplace().replace("\r", "").split("\n");
		for (String headerToReplace : headersToReplace) {
			String trimmedHeaderToReplace = headerToReplace.trim();
			String[] headerKeyValuePair = trimmedHeaderToReplace.split(":");
			if (headerKeyValuePair.length > 1) {
				String headerKey = headerKeyValuePair[0];
				boolean headerReplaced = false;
				for (int i = 0; i < headers.size(); i++) {
					if (headers.get(i).startsWith(headerKey)) {
						headers.set(i, trimmedHeaderToReplace);
						headerReplaced = true;
						break;
					}
				}
				//Set new header if it not occurs
				if (!headerReplaced) {
					headers.add(trimmedHeaderToReplace);
				}
			}
		}
		//Apply Rules and Update Content-Length
		for (int i = 0; i < headers.size(); i++) {
			if(session.getRules().size() > 0) {
				headers.set(i, applyRulesInHeader(session, headers.get(i)));
			}
			if(headers.get(i).startsWith("Content-Length:")) {
				headers.set(i, "Content-Length: "+ bodyLength);
			}
		}		
		
		// Check for CSRF Token as Query Parameter
		if (!session.getCsrfTokenName().equals("")) {
			// CSRF Remove feature. Syntax remove_token#csrf_token
			if (session.getCsrfTokenName().toLowerCase().startsWith("remove_token")) {
				String[] csrfSplit = session.getCsrfTokenName().split("#");
				if (csrfSplit.length > 1) {
					// Only replace in request query string --> headers.get(0)
					String modifiedHeader = headers.get(0).replace(csrfSplit[1],"dummyparam");
					headers.set(0, modifiedHeader);
				}
			}
			else {
				for (IParameter param : originalRequestInfo.getParameters()) {
					if (param.getName().equals(session.getCsrfTokenName())) {
						String modifiedHeader = headers.get(0).replace(param.getValue(), session.getCurrentCsrftTokenValue());
						headers.set(0, modifiedHeader);
						break;
					}
				}
			}
		}
		return headers;
	}

	public String getModifiedMessageBody(String originalMessageBody, byte contentType, Session session) {
		// Apply rules in body
		String messageBodyWithAppliedRules = applyRulesInBody(session, originalMessageBody); 
		// Check and modify if csrf modification is needed
		if(session.getCsrfTokenName().equals("")) {
			return messageBodyWithAppliedRules;
		}
		else {
			String modifiedMessageBody = "";
			// CSRF Remove feature. Syntax remove_token#csrf_token
			if (session.getCsrfTokenName().toLowerCase().startsWith("remove_token")) {
				String[] csrfSplit = session.getCsrfTokenName().split("#");
				if (csrfSplit.length > 0) {
					modifiedMessageBody = messageBodyWithAppliedRules.replace(csrfSplit[1], "dummyparam");
				}
			}
			else {
				if (messageBodyWithAppliedRules.length() > 0) {
					// Check and replace if original csrf value present (request body content type not relevant)
					if(!currentOriginalCsrfValue.equals("") && messageBodyWithAppliedRules.contains(currentOriginalCsrfValue)) {
						modifiedMessageBody = messageBodyWithAppliedRules.replace(currentOriginalCsrfValue, session.getCurrentCsrftTokenValue());
					}
					else if(messageBodyWithAppliedRules.contains(session.getCsrfTokenName())) {
						// Handle Multipart Form Data
						if (contentType == IRequestInfo.CONTENT_TYPE_MULTIPART) {
							String[] splitAtCsrfTokenName = messageBodyWithAppliedRules.split(session.getCsrfTokenName());
							if (splitAtCsrfTokenName.length > 1) {
								String[] csrfTokenValueSplit = splitAtCsrfTokenName[1].split("\\n");
								if (csrfTokenValueSplit.length > 2) {
									String csrfValue = csrfTokenValueSplit[2].split("---")[0].trim();
									modifiedMessageBody = messageBodyWithAppliedRules.replace(csrfValue,
											session.getCurrentCsrftTokenValue());
								}
							}
						} 
						// Handle URL Encoded
						if(contentType == IRequestInfo.CONTENT_TYPE_URL_ENCODED) {
							String[] params = messageBodyWithAppliedRules.split("&");
							for (String param : params) {
								if (param.split("=")[0].equals(session.getCsrfTokenName())) {
									modifiedMessageBody = messageBodyWithAppliedRules.replace(param,
											session.getCsrfTokenName() + "=" + session.getCurrentCsrftTokenValue());
								}
							}
						}
						// Handle JSON Body
						if(contentType == IRequestInfo.CONTENT_TYPE_JSON) {
							JsonElement jelement = JsonParser.parseString(messageBodyWithAppliedRules).getAsJsonObject();
							JsonObject jobject = null;
							if(jelement.isJsonObject()) {
								jobject = jelement.getAsJsonObject();
							}
							else if(jelement.isJsonArray()) {
								if(jelement.getAsJsonArray().get(0).isJsonObject()) {
									jobject = jelement.getAsJsonArray().get(0).getAsJsonObject();
								}
							}
						    if(jobject != null) {
						    	String oldCsrfValue = jobject.get(session.getCsrfTokenName()).getAsString();
						    	modifiedMessageBody = messageBodyWithAppliedRules.replace(oldCsrfValue, session.getCurrentCsrftTokenValue());
						    }
						}
					}
				}
			}
			//Fail-Safe
			if (modifiedMessageBody.equals("")) {
				modifiedMessageBody = messageBodyWithAppliedRules;
			}
			return modifiedMessageBody;
		}
	}
	
	public void extractOriginalCsrfValue(IHttpRequestResponse messageInfo) {
		String csrfTokenName = config.getSessions().get(0).getCsrfTokenName();
		IResponseInfo response = callbacks.getHelpers().analyzeResponse(messageInfo.getResponse());
		String responseBody = getResponseBodyAsString(messageInfo);
		if(responseBody.contains(csrfTokenName)) {
			if (response.getStatedMimeType().equals("HTML") || response.getInferredMimeType().equals("HTML")) {
				currentOriginalCsrfValue = getCsrfTokenValueFromInputField(responseBody, csrfTokenName);
			}
			else if (response.getStatedMimeType().equals("JSON") || response.getInferredMimeType().equals("JSON")) {
				currentOriginalCsrfValue = getCsrfTokenValueFromJson(responseBody, csrfTokenName);
			}
		}
	}

	public void extractCurrentCsrfValue(IHttpRequestResponse messageInfo, Session session) {
		IResponseInfo response = callbacks.getHelpers().analyzeResponse(messageInfo.getResponse());
		String responseBody = getResponseBodyAsString(messageInfo);
		if(responseBody.contains(session.getCsrfTokenName())) {
			if (response.getStatedMimeType().equals("HTML") || response.getInferredMimeType().equals("HTML")) {
				String value = getCsrfTokenValueFromInputField(responseBody, session.getCsrfTokenName());
				session.setCsrfTokenValue(value);
				session.getStatusPanel().updateCsrfTokenValue(value);
			}
			else if (response.getStatedMimeType().equals("JSON") || response.getInferredMimeType().equals("JSON")) {
				String value = getCsrfTokenValueFromJson(responseBody, session.getCsrfTokenName());
				session.setCsrfTokenValue(value);
				session.getStatusPanel().updateCsrfTokenValue(value);
			}
		}
	}
	
	public String getCsrfTokenValueFromInputField(String document, String csrfName) {
		Document doc = Jsoup.parse(document);
		Elements csrfFields = doc.getElementsByAttributeValue("name", csrfName);
		if (csrfFields.size() > 0) {
			String csrfValue = csrfFields.get(0).attr("value");
			return csrfValue;
		}
		return "";
	}

	public String getCsrfTokenValueFromJson(String json, String csrfName) {
		JsonElement jelement = JsonParser.parseString(json).getAsJsonObject();
		JsonObject jobject = null;
		if(jelement.isJsonObject()) {
			jobject = jelement.getAsJsonObject();
		}
		else if(jelement.isJsonArray()) {
			if(jelement.getAsJsonArray().get(0).isJsonObject()) {
				jobject = jelement.getAsJsonArray().get(0).getAsJsonObject();
			}
		}
	    if(jobject != null) {
	    	String csrfValue = jobject.get(csrfName).getAsString();
	    	return csrfValue;
	    }
	    return "";
	}
	
	public String applyRulesInBody(Session session, String body) {
		if(session.getRules().size() > 0) {
			String messageAsString = body;
			for(Rule rule : session.getRules()) {
				if(rule.replaceInBody() && rule.getReplacementValue() != null) {
					int beginIndex = messageAsString.indexOf(rule.getReplaceFromString());
					if(beginIndex != -1) {
						beginIndex = beginIndex + rule.getReplaceFromString().length();
					}
					int endIndex = -1;
					if(rule.getReplaceToString().equals("EOF")) {
						endIndex = messageAsString.length();
					}
					else {
						endIndex = messageAsString.indexOf(rule.getReplaceToString(), beginIndex);	
					}
					if(beginIndex != -1 && endIndex != -1) {
						String beginString = messageAsString.substring(0, beginIndex);
						String endString = messageAsString.substring(endIndex, messageAsString.length());
						messageAsString = beginString + rule.getReplacementValue() + endString;
					}				
				}
			}
			return messageAsString;
		}
		return body;
	}
	
	public String applyRulesInHeader(Session session, String header) {
		if(session.getRules().size() > 0) {
			String messageAsString = header;
			for(Rule rule : session.getRules()) {
				if(rule.replaceInHeader() && rule.getReplacementValue() != null) {
					int beginIndex = messageAsString.indexOf(rule.getReplaceFromString());
					if(beginIndex != -1) {
						beginIndex = beginIndex + rule.getReplaceFromString().length();
					}
					int endIndex = -1;
					// Threat CR / LF as end of file (every header line is processed by its own). Also take escaped CR LF because
					if(rule.getReplaceToString().equals("EOF") || rule.getReplaceToString().equals("\n")) {
						endIndex = messageAsString.length();
					}
					else {
						endIndex = messageAsString.indexOf(rule.getReplaceToString(), beginIndex);
					}
					if(beginIndex != -1 && endIndex != -1) {
						String beginString = messageAsString.substring(0, beginIndex);
						String endString = messageAsString.substring(endIndex, messageAsString.length());
						messageAsString = beginString + rule.getReplacementValue() + endString;
					}				
				}
			}
			return messageAsString;
		}
		return header;
	}

	public void extractResponseRuleValues(Session session, byte[] response) {
		if(session.getRules().size() > 0) {
			logger.writeLog(Logger.SEVERITY.INFO, "Extract rule values");
			String messageAsString = new String(response);
			int bodyOffset = -1;
			bodyOffset = messageAsString.indexOf("\r\n\r\n");
			if(bodyOffset == -1) {
				bodyOffset = messageAsString.indexOf("\n\n");
			}
			if(bodyOffset == -1) {
				bodyOffset = messageAsString.indexOf(System.lineSeparator());
			}
			if(bodyOffset != -1) {
				String header = messageAsString.substring(0, bodyOffset);
				String body = messageAsString.substring(bodyOffset).trim(); 
				for(Rule rule : session.getRules()) {
					String stringToCheck = "";
					if(rule.grepInHeader() && !rule.grepInBody()) {
						stringToCheck = header;
					}
					else if(!rule.grepInHeader() && rule.grepInBody()) {
						stringToCheck = body;
					}
					else if(rule.grepInHeader() && rule.grepInBody()) {
						stringToCheck = messageAsString;
					}
					int beginIndex = stringToCheck.indexOf(rule.getGrepFromString());
					if(beginIndex != -1) {
						beginIndex = beginIndex + rule.getGrepFromString().length();
					}
					int endIndex = -1;
					if(rule.getGrepToString().equals("EOF")) {
						// Grep to end of file
						endIndex = stringToCheck.length();
					}
					else {
						endIndex = stringToCheck.indexOf(rule.getGrepToString(), beginIndex);
					}
					if(beginIndex != -1 && endIndex != -1) {
						String value = stringToCheck.substring(beginIndex, endIndex);
						rule.setReplacementValue(value);
						session.getStatusPanel().setRuleValue(rule, value);
					}
				}
			}
		}
	}
	
	/*
	 * Bypass if: 
	 * - Both Responses have same Response Body and Status Code
	 * 
	 * Potential Bypass if: 
	 * - Both Responses have same Response Code 
	 * - Both Responses have +-5% of response body length 
	 *
	 */
	public BypassConstants analyzeResponse(IHttpRequestResponse originalMessageInfo,
			IHttpRequestResponse modifiedMessageInfo) {
		try {
			String originalMessageBody = getResponseBodyAsString(originalMessageInfo);
			String modifiedMessageBody = getResponseBodyAsString(modifiedMessageInfo);
			IResponseInfo originalResponseInfo = callbacks.getHelpers().analyzeResponse(originalMessageInfo.getResponse());
			IResponseInfo modifiedResponseInfo = callbacks.getHelpers().analyzeResponse(modifiedMessageInfo.getResponse());
			if (originalMessageBody.equals(modifiedMessageBody) && (originalResponseInfo.getStatusCode() == modifiedResponseInfo.getStatusCode())) {
				return BypassConstants.BYPASSED;
			}
			if (originalResponseInfo.getStatusCode() == modifiedResponseInfo.getStatusCode()) {
				int range = originalMessageBody.length() / 20; // calc 5% of response length
				int difference = originalMessageBody.length() - modifiedMessageBody.length();
				// Check if difference is in range
				if (difference < range && difference > -range) {
					return BypassConstants.POTENTIAL_BYPASSED;
				}
			}
			return BypassConstants.NOT_BYPASSED;
			
		} catch (Exception e) {
			PrintWriter stderr = new PrintWriter(callbacks.getStderr(), true);
			stderr.println("Failed to analyze Response");
			e.printStackTrace();
			return null;
		}
	}
	
	private String getRequestBodyAsString(IHttpRequestResponse messageInfo) {
		IRequestInfo requestInfo = callbacks.getHelpers().analyzeRequest(messageInfo);
		String requestString = new String(messageInfo.getRequest());
		String messageBody = requestString.substring(requestInfo.getBodyOffset());
		return messageBody;
	}
	
	private String getResponseBodyAsString(IHttpRequestResponse messageInfo) {
		IResponseInfo response = callbacks.getHelpers().analyzeResponse(messageInfo.getResponse());
		String currentResponse = new String(messageInfo.getResponse());
		String responseBody = currentResponse.substring(response.getBodyOffset());
		return responseBody;
	}
	
	public void setOriginalCsrfTokenValue(String value) {
		this.currentOriginalCsrfValue = value;
	}
}
