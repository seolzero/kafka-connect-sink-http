/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.co.threefi.connect.http.sink;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.connect.sink.SinkRecord;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

import javax.ws.rs.core.Response;

public class HttpApiWriter {

	private final HttpSinkConfig config;
	private static final Logger log = LoggerFactory.getLogger(HttpApiWriter.class);
	private Map<String, List<SinkRecord>> batches = new HashMap<>();
	private JSONObject bucket = new JSONObject();
	private JSONObject finalBucket = new JSONObject();
	private JSONObject responseResultObject = new JSONObject();
	private ArrayList<String> exceptKeys = new ArrayList<String>();
	private KafkaProducer<String, String> producer;
	private static Integer betchSize;
	private JSONParser jsonParser = new JSONParser();

	HttpApiWriter(final HttpSinkConfig config) {
		this.config = config;
		Properties prop = new Properties();
		prop.put("bootstrap.servers", config.KafkaApiUrl);
		prop.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
		prop.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
		//// prop.put("acks", "all");

		producer = new KafkaProducer<String, String>(prop);
		exceptKeys.add("tmpA");
		betchSize = config.batchMaxSize;

	}

	public void write(final Collection<SinkRecord> records) throws IOException {
		log.info("SSUL write 1234!");
		for (SinkRecord record : records) {

			// build batch key
			String formattedKeyPattern = config.batchKeyPattern
					.replace("${key}", record.key() == null ? "" : record.key().toString())
					.replace("${topic}", record.topic());

			// add to batch and check for batch size limit
			if (!batches.containsKey(formattedKeyPattern)) {
				batches.put(formattedKeyPattern, new ArrayList<SinkRecord>(Arrays.asList(new SinkRecord[] { record })));
			} else {
				batches.get(formattedKeyPattern).add(record);
			}
			//if (batches.get(formattedKeyPattern).size() >= batchSize) {//config.batchMaxSize
			//sendBatch(formattedKeyPattern);
			//}
		}
		flushBatches();

	}

	public void flushBatches() throws IOException {
		// send any outstanding batches
		for (Map.Entry<String, List<SinkRecord>> entry : batches.entrySet()) {
			sendBatch(entry.getKey());
		}
	}


	private void sendBatch(String formattedKeyPattern) throws IOException {
		List<SinkRecord> records = batches.get(formattedKeyPattern);
		SinkRecord record0 = records.get(0);
		//System.out.println("records.size(): " + records.size());
		StringBuilder builder = new StringBuilder(config.batchPrefix);
		//System.out.println("records.value().toString(): " + records.toString());
		for (SinkRecord record : records) {
			if (record == null) {
				continue;
			}

			if (record.value() == null) {
				continue;
			}
			
			try {	
				
				JSONObject jsonObject = (JSONObject) jsonParser.parse(record.value().toString());
				System.out.println(">>>>>>>>>>>>>>>>>>jsonObject: "+ jsonObject);
				
				for(Object key : jsonObject.keySet()) {
					// record에 제외하고 싶은 키는 skip
					if(exceptKeys.contains(key)) {
						continue;
					}
					// bucket의 각 key들의 value는 ArrayList 타입이므로 value를 담기 위한 변수 생성
					ArrayList<String> arr = new ArrayList<String>();
					// bucket에 해당 key가 없을 경우
					if(bucket.get(key) == null) {
						arr.add((String) jsonObject.get(key).toString());
					}
					// 해당 key가 있을 경우. bucket의 해당 key의 값을 가져와서 add
					else {
						arr = (ArrayList<String>) bucket.get(key);
						arr.add((String) jsonObject.get(key).toString());
					}
					// bucket의 해당 key에 array list value 대입
					bucket.put(key, arr);
					
					System.out.println("bucket: " + bucket);

					if(isReadyToSend(bucket)) {
						System.out.println(" >> ready here !!");

						// slice
						bucket = sliding(bucket);
						System.out.println("builder append bucket: " + bucket);

						// builder send
						ArrayList<String> TimeStampList = (ArrayList<String>) bucket.get("rowtime");
						String lastTimeStamp = TimeStampList.get(betchSize-1);
						finalBucket = (JSONObject) bucket.clone();
						finalBucket.put("name", record0.topic());
						System.out.println("finalBucket: " + finalBucket);
						builder.append(finalBucket);
						
						
						////////////////////////
						/*build.append -> send*/
						String formattedUrl = config.httpApiUrl
								.replace("${key}", record0.key() == null ? "" : record0.key().toString())
								.replace("${topic}", record0.topic());
						HttpSinkConfig.RequestMethod requestMethod = config.requestMethod;
						URL url = new URL(formattedUrl);
						HttpURLConnection con = (HttpURLConnection) url.openConnection();
						con.setDoOutput(true);
						con.setRequestMethod(requestMethod.toString());

						// add headers
						for (String headerKeyValue : config.headers.split(config.headerSeparator)) {
							if (headerKeyValue.contains(":")) {
								con.setRequestProperty(headerKeyValue.split(":")[0], headerKeyValue.split(":")[1]);
							}
						}

						try {
							OutputStreamWriter writer = new OutputStreamWriter(con.getOutputStream(), "UTF-8");
							log.info("WRITE builder.toString(): " + builder.toString());
							writer.write(builder.toString());
							writer.close();

						} catch (Exception e) {
							log.info("writer exception detection: " + e);
						}

						/* batches clear */
						batches.remove(formattedKeyPattern);
						log.debug("Submitted payload: " + builder.toString() + ", url:" + formattedUrl);




						// get response
						int status = con.getResponseCode();
						if (Response.Status.Family.familyOf(status) != Response.Status.Family.SUCCESSFUL) {
							BufferedReader in = new BufferedReader(new InputStreamReader(con.getErrorStream()));
							String inputLine;
							StringBuffer error = new StringBuffer();
							while ((inputLine = in.readLine()) != null) {
								error.append(inputLine);
							}
							in.close();
							log.info("HTTP Response code: " + status + ", " + con.getResponseMessage() + ", " + error
									//		                    + ", Submitted payload: " + builder.toString()
									+ ", url: " + formattedUrl + ", topic: " + record0.topic());
						} else {
							log.debug(", response code: " + status + ", " + con.getResponseMessage() + ", headers: "
									+ config.headers);
							log.info("SSUL INFO response code: " + status + ", " + con.getResponseMessage() + ", headers: "
									+ config.headers + ", url:" + formattedUrl);
							
							// write the response to the log
							BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
							String inputLine;
							StringBuffer content = new StringBuffer();
							while ((inputLine = in.readLine()) != null) {
								content.append(inputLine);
							}

							responseResultObject.put("timestamp", lastTimeStamp);
							responseResultObject.put("input", finalBucket);
							responseResultObject.put("result", content.toString());
							log.info("SSUL content: " + content.toString());
							log.info("SSUL config.ResponseTopic: " + config.ResponseTopic);
							log.info("SSUL config.KafkaApiUrl: " + config.KafkaApiUrl);
							log.info("SSUL produce send");
							producer.send(new ProducerRecord<String, String>(config.ResponseTopic, responseResultObject.toString()));
							log.info("SSUL write 5678!");
							in.close();

						}
						con.disconnect();
						
					}
					
				}

			} catch (ParseException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			/*
			 * builder 초기화
			 * */
			System.out.println("========================================clear");
			builder.setLength(0);


		}

	}

	private static boolean isReadyToSend(JSONObject bucket) {
		Boolean result = false;
		ArrayList<Boolean> check = new ArrayList<Boolean>();
		ArrayList<String> arr = new ArrayList<String>();
		
		for(Object key : bucket.keySet()) {
			arr = (ArrayList<String>) bucket.get(key);
			if(arr.size() >= betchSize+1) {
				check.add(true);
			} else {
				check.add(false);
			}
		}
		
		if(check.contains(false)) {
			result = false;
		} else {
			result = true;
		}
		
		return result;
	}

	
	private static JSONObject sliding(JSONObject bucket) {
		JSONObject slicedBucket = new JSONObject();

		for(Object key : bucket.keySet()) {
			ArrayList<String> arr = new ArrayList<String>();
			arr = (ArrayList<String>) bucket.get(key);
			// TODO: arr slice
			slicedBucket.put(key,new ArrayList<String>(arr.subList(1, betchSize+1)));
		}
		
		return slicedBucket;
	}
	
	private String buildRecord(SinkRecord record) {
		// add payload
		String value = record.value().toString();

		// apply regexes
		int replacementIndex = 0;
		for (String pattern : config.regexPatterns.split(config.regexSeparator)) {
			String replacement = "";
			if (replacementIndex < config.regexReplacements.split(config.regexSeparator).length) {
				replacement = config.regexReplacements.split(config.regexSeparator)[replacementIndex]
						.replace("${key}", record.key() == null ? "" : record.key().toString())
						.replace("${topic}", record.topic());
			}
			value = value.replaceAll(pattern, replacement);
			replacementIndex++;
		}
		return value;
	}

}

