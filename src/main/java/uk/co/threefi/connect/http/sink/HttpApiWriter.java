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
	private KafkaProducer<String, String> producer;
	int batchIndex = 0;
	int batchSize = 10;
	List<Double> s1List = new ArrayList<Double>();
	List<Double> s2List = new ArrayList<Double>();
	List<String> timestampList = new ArrayList<String>();

	HttpApiWriter(final HttpSinkConfig config) {
		this.config = config;
		Properties prop = new Properties();
		prop.put("bootstrap.servers", config.KafkaApiUrl);
		prop.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
		prop.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
		//// prop.put("acks", "all");

		producer = new KafkaProducer<String, String>(prop);

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



	List<Double> s1subList = new ArrayList<>();
	List<Double> s2subList = new ArrayList<>();
	List<String> timestampsubList = new ArrayList<>();
	String sensor1_id;
	String sensor2_id;
	JSONObject sensorArrJsonObject = new JSONObject();
	JSONParser jsonParser = new JSONParser();

	private void sendBatch(String formattedKeyPattern) throws IOException {
		List<SinkRecord> records = batches.get(formattedKeyPattern);
		SinkRecord record0 = records.get(0);
		//System.out.println("records.size(): " + records.size());
		StringBuilder builder = new StringBuilder(config.batchPrefix);
		//System.out.println("records.value().toString(): " + records.toString());
		int recordIndex = 0;
		for (SinkRecord record : records) {


			if (record == null) {
				continue;
			}

			if (record.value() == null) {
				continue;
			}

			//System.out.println("record.value().toString(): " + record.value().toString());
			// {"sensor1_id":"angle","sensor2_id":"heading","sensor1_value":"40","sensor1_rowtime":"2022-05-25 11:07:18.249","tmpA":1,"sensor2_value":"243"}

			try {	
				recordIndex++;
				//System.out.println("recordIndex: "+ recordIndex);
				JSONObject jsonObject = (JSONObject) jsonParser.parse(record.value().toString());
				//System.out.println("jsonObject: " + jsonObject);
				sensor1_id = (String) jsonObject.get("sensor1_id");
				//sensor2_id = (String) jsonObject.get("sensor2_id");
				double sensor1_value = Double.parseDouble((String) jsonObject.get("sensor1_value"));
				s1List.add(sensor1_value);
				//System.out.println("s1List: " + s1List);
				//double sensor2_value = Double.parseDouble((String) jsonObject.get("sensor2_value"));
				//s2List.add(sensor2_value);
				String sensor1_rowtime = (String) jsonObject.get("sensor1_rowtime");
				timestampList.add(sensor1_rowtime);
			} catch (ParseException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}



			if(s1List.size() > batchSize) {
				//System.out.println("recordIndex: " + recordIndex + "  ,s1List.size(): " + s1List.size());
				s1subList = s1List.subList(0, batchSize);
				//s2subList = s2List.subList(0, batchSize);
				timestampsubList = timestampList.subList(0, batchSize);

				sensorArrJsonObject.put(sensor1_id, s1subList);
				//sensorArrJsonObject.put(sensor2_id, s2subList);
				sensorArrJsonObject.put("timestamp", timestampsubList);
				//System.out.println("sensorArrJsonObject: " + sensorArrJsonObject);

				builder.append(sensorArrJsonObject);
				System.out.println("builder.length(): " + builder.length());





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

				// clear batch
				//if(records.size() == recordIndex) {
				System.out.println("!!!clear records batch!!! recordIndex: " + recordIndex);
				batches.remove(formattedKeyPattern);

				//}
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
					/*
					 * throw new IOException("HTTP Response code: " + status + ", " +
					 * con.getResponseMessage() + ", " + error + ", Submitted payload: " +
					 * builder.toString() + ", url:" + formattedUrl);
					 */



				} else {
					log.debug(", response code: " + status + ", " + con.getResponseMessage() + ", headers: "
							+ config.headers);
					log.info("SSUL INFO response code: " + status + ", " + con.getResponseMessage() + ", headers: "
							+ config.headers + ", url:" + formattedUrl);
					//		                    + ", Submitted payload: " + builder.toString()

					// write the response to the log
					BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
					String inputLine;
					StringBuffer content = new StringBuffer();
					while ((inputLine = in.readLine()) != null) {
						content.append(inputLine);
					}

					log.info("SSUL content: " + content.toString());
					log.info("SSUL config.ResponseTopic: " + config.ResponseTopic);
					log.info("SSUL config.KafkaApiUrl: " + config.KafkaApiUrl);
					// try {
					log.info("SSUL produce send");
					producer.send(new ProducerRecord<String, String>(config.ResponseTopic, content.toString()));
					log.info("SSUL write 5678!");
					// }catch(Exception e) {
					// log.info("catch kafka produce");
					// }
					in.close();



				}
				con.disconnect();
				/*
				 * 배열 초기화
				 * */
				System.out.println("========================================clear");
				//s1subList.clear();
				//timestampsubList.clear();
				sensorArrJsonObject.clear();
				System.out.println("sensorArrJsonObject: " + sensorArrJsonObject);
				s1List.remove(0);
				timestampList.remove(0);
				builder.setLength(0);


			}



		}

		// if we dont't have anything to send, skip
		//		if (builder.length() == 0) {
		//			log.debug("nothing to send; skipping the http request");
		//			return;
		//		}

		// build url - ${key} and ${topic} can be replaced with message values
		// the first record in the batch is used to build the url as we assume it will
		// be consistent across all records.

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
