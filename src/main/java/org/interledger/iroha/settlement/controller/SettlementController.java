package org.interledger.iroha.settlement.controller;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE;

import org.interledger.iroha.settlement.SettlementEngine;
import org.interledger.iroha.settlement.message.PaymentDetailsMessage;
import org.interledger.iroha.settlement.model.SettlementAccount;
import org.interledger.iroha.settlement.store.Store;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.ExponentialBackOff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@RestController
public class SettlementController {
  private final Logger logger = LoggerFactory.getLogger(this.getClass());

  private static HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
  private static JsonFactory JSON_FACTORY = new JacksonFactory();

  @Value("${connector-url:http://127.0.0.1:7771}")
  private String connectorUrl;

  @Autowired
  private SettlementEngine settlementEngine;

  @Autowired
  private Store store;

  /**
   * <p>Called by the Connector to inform the Settlement Engine that a new account was created within
   * the accounting system using the given account identifier.</p>
   *
   * @param settlementAccount The account identifier as supplied by the Connector.
   *
   * @return
   */
  @RequestMapping(
      path = "/accounts",
      method = RequestMethod.POST,
      consumes = APPLICATION_JSON_VALUE
  )
  public ResponseEntity<Void> setupAccount(
      @RequestBody final SettlementAccount settlementAccount
  ) {
    this.logger.info("POST /accounts { id: {} }", settlementAccount.getId());

    // Create a request for payment details for the current ILP account
    PaymentDetailsMessage paymentDetailsRequest = new PaymentDetailsMessage(
        this.settlementEngine.getAccountId()
    );

    // Only send request for payment details if we don't have that information
    if (this.store.getPeerAccount(settlementAccount.getId()) == null) {
      try {
        HttpRequestFactory requestFactory = HTTP_TRANSPORT.createRequestFactory(
            (HttpRequest request) -> {
                request.setParser(new JsonObjectParser(JSON_FACTORY));
            }
        );

        GenericUrl connectorMessageUrl = new GenericUrl(this.connectorUrl);
        connectorMessageUrl.appendRawPath("/accounts/" + settlementAccount.getId() + "/messages");

        HttpRequest request = requestFactory.buildPostRequest(
            connectorMessageUrl,
            ByteArrayContent.fromString(APPLICATION_JSON_VALUE, JSON_FACTORY.toString(paymentDetailsRequest))
        );

        // https://github.com/interledger/rfcs/blob/master/0038-settlement-engines/0038-settlement-engines.md#retry-behavior
        ExponentialBackOff backoff = new ExponentialBackOff.Builder()
            .setInitialIntervalMillis(500)
            .setMaxElapsedTimeMillis(900000)
            .setMaxIntervalMillis(6000)
            .setMultiplier(1.5)
            .setRandomizationFactor(0.5)
            .build();
        request.setUnsuccessfulResponseHandler(
            new HttpBackOffUnsuccessfulResponseHandler(backoff)
        );

        // Send the payment details request and wait for a corresponding response
        PaymentDetailsMessage paymentDetailsResponse = request.execute().parseAs(PaymentDetailsMessage.class);

        // Save peer's Iroha account id
        this.store.savePeerAccount(settlementAccount.getId(), paymentDetailsResponse.getFromAccount());

        this.logger.info(
            "Got peer's Iroha account id ({}) corresponding to account {}",
            paymentDetailsResponse.getFromAccount(),
            settlementAccount.getId()
        );

        return new ResponseEntity<>(HttpStatus.CREATED); 
      } catch (MalformedURLException err) {
        this.logger.error("Invalid connector-url: {}", err.getMessage());

        return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
      } catch (IOException err) {
        this.logger.error("Error while handling payment details: {}", err.getMessage());

        return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
      }
    } else {
      return new ResponseEntity<>(HttpStatus.CREATED); 
    }
  }

  /**
   * <p>Called by the Connector to inform the Settlement Engine that an account was deleted.</p>
   *
   * @param settlementAccountId The account identifier as supplied by the Connector.
   *
   * @return
   */
  @RequestMapping(
      path = "/accounts/{settlementAccountId}",
      method = RequestMethod.DELETE
  )
  public ResponseEntity<Void> deleteAccount(
      @PathVariable final String settlementAccountId
  ) {
    this.logger.info("DELETE /accounts/{}", settlementAccountId);

    // TODO: implement

    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  /**
   * <p>Called by the Connector to asynchronously trigger a settlement in the Settlement Engine.</p>
   *
   * @param idempotencyKey      The idempotence identifier defined in the Settlement Engine RFC
   *                            (typed as a {@link String}, but should always be a Type4 UUID).
   *
   * @param settlementAccountId The account identifier as supplied by the Connector.
   *
   * @return
   */
  @RequestMapping(
      path = "/accounts/{settlementAccountId}/settlements",
      method = RequestMethod.POST,
      consumes = APPLICATION_JSON_VALUE,
      produces = APPLICATION_JSON_VALUE
  )
  public ResponseEntity<Void> performOutgoingSettlement(
      @RequestHeader("Idempotency-Key") final String idempotencyKey,
      @PathVariable final String settlementAccountId
  ) {
    this.logger.info("POST /accounts/{}/settlements { Idempotency-Key: {} }", settlementAccountId, idempotencyKey);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(APPLICATION_JSON);

    // TODO: implement

    return new ResponseEntity<>(headers, HttpStatus.CREATED);
  }

  /**
   * <p>Called by the Connector to process and respond to an incoming message from the peer's
   * Settlement Engine.</p>
   *
   * @param message             A byte array of opaque data that was sent by the peer's Settlement Engine.
   *
   * @param settlementAccountId The account identifier as supplied by the Connector.
   *
   * @return A byte array representing the response message to be sent to the peer's Settlement Engine.
   */
  @RequestMapping(
      path = "/accounts/{settlementAccountId}/messages",
      method = RequestMethod.POST,
      consumes = APPLICATION_OCTET_STREAM_VALUE,
      produces = APPLICATION_OCTET_STREAM_VALUE
  )
  public ResponseEntity<byte[]> handleIncomingMessage(
      @RequestBody final byte[] message,
      @PathVariable final String settlementAccountId
  ) {
    String messageString = new String(message, StandardCharsets.UTF_8);

    this.logger.info("POST /accounts/{}/messages {}", settlementAccountId, messageString);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(APPLICATION_OCTET_STREAM);

    try {
      PaymentDetailsMessage paymentDetailsRequest = JSON_FACTORY.fromString(messageString, PaymentDetailsMessage.class);

      // Save peer's Iroha account id
      this.store.savePeerAccount(settlementAccountId, paymentDetailsRequest.getFromAccount());

      this.logger.info(
          "Got peer's Iroha account id ({}) corresponding to account {}",
          paymentDetailsRequest.getFromAccount(),
          settlementAccountId
      );

      PaymentDetailsMessage paymentDetailsResponse = new PaymentDetailsMessage(
          this.settlementEngine.getAccountId()
      );

      // Respond with our own Iroha account id
      return new ResponseEntity<>(
          JSON_FACTORY.toString(paymentDetailsResponse).getBytes(), headers, HttpStatus.CREATED
      );
    } catch (IOException err) {
      this.logger.error("Invalid payment details message: {}", err.getMessage());
      this.logger.info("Only payment details messages are accepted via /accounts/:id/messages");

      return new ResponseEntity<>(headers, HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }
}
