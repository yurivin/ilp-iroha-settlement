package org.interledger.settlement.iroha.controller;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;

import org.interledger.settlement.common.controller.SettlementController;
import org.interledger.settlement.common.model.SettlementAccount;
import org.interledger.settlement.common.model.SettlementQuantity;
import org.interledger.settlement.iroha.IrohaException;
import org.interledger.settlement.iroha.SettlementEngine;
import org.interledger.settlement.iroha.Util;
import org.interledger.settlement.iroha.config.DefaultArgumentValues;
import org.interledger.settlement.iroha.message.PaymentDetailsMessage;
import org.interledger.settlement.iroha.store.Store;

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
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

@RestController
public class IrohaSettlementController implements SettlementController {
  private final Logger logger = LoggerFactory.getLogger(this.getClass());

  private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
  private static final JsonFactory JSON_FACTORY = new JacksonFactory();

  @Value("${asset-scale:" + DefaultArgumentValues.ASSET_SCALE + "}")
  private String assetScale;

  @Value("${connector-url:" + DefaultArgumentValues.CONNECTOR_URL + "}")
  private String connectorUrl;

  @Autowired
  private SettlementEngine settlementEngine;

  @Autowired
  private Store store;

  @Override
  public ResponseEntity<Void> setupAccount(
      @RequestBody SettlementAccount settlementAccount
  ) {
    this.logger.info("POST /accounts { id: {} }", settlementAccount.getId());

    // Create a request for payment details for the current ILP account
    PaymentDetailsMessage paymentDetailsRequest = new PaymentDetailsMessage(
        this.settlementEngine.getIrohaAccountId()
    );

    // Only send request for payment details if we don't have that information
    if (this.store.getPeerIrohaAccountId(settlementAccount.getId()) == null) {
      try {
        this.logger.info(
            "Serialized PaymentDetailsMessage object to be sent to peer: "
            + JSON_FACTORY.toString(paymentDetailsRequest)
        );

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

        this.logger.info(
            "Got peer's Iroha account id ({}) corresponding to settlement account {}",
            paymentDetailsResponse.getIrohaAccountId(),
            settlementAccount.getId()
        );

        // Save peer's Iroha account id
        this.store.savePeerIrohaAccountId(settlementAccount.getId(), paymentDetailsResponse.getIrohaAccountId());

        return new ResponseEntity<>(HttpStatus.CREATED); 
      } catch (IOException err) {
        this.logger.error("Error while handling payment details: {}", err.getMessage());

        // Fatal error
        return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
      }
    } else {
      return new ResponseEntity<>(HttpStatus.CREATED); 
    }
  }

  @Override
  public ResponseEntity<Void> deleteAccount(
      @PathVariable String settlementAccountId
  ) {
    this.logger.info("DELETE /accounts/{}", settlementAccountId);

    // Require the settlement account to already exist in the store
    if (!this.store.existsSettlementAccount(settlementAccountId)) {
      return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    this.store.deleteSettlementAccount(settlementAccountId);

    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  @Override
  public ResponseEntity<Void> performOutgoingSettlement(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody SettlementQuantity quantity,
      @PathVariable String settlementAccountId
  ) {
    this.logger.info("POST /accounts/{}/settlements { Idempotency-Key: {} }", settlementAccountId, idempotencyKey);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(APPLICATION_JSON);

    try {
      // We need to convert from the scale the connector sent us to our own scale
      int fromScale = quantity.getScale();
      int toScale = Integer.parseInt(this.assetScale);

      // Scale the amount (together with any pre-existing leftovers)
      Map.Entry<BigDecimal, BigDecimal> scalingResult = Util.scaleWithPrecisionLoss(
          quantity.getAmount().add(this.store.getLeftover(settlementAccountId)),
          fromScale,
          toScale
      );
      BigDecimal scaledAmount = scalingResult.getKey();

      // Retrieve the peer's Iroha account id corresponding to the current settlement account
      String peerIrohaAccountId = this.store.getPeerIrohaAccountId(settlementAccountId);
      if (peerIrohaAccountId == null) {
        // Fatal error
        return new ResponseEntity<>(headers, HttpStatus.INTERNAL_SERVER_ERROR);
      }

      this.logger.info(
          "Performing settlement on settlement account {} "
          + "(from Iroha account {} to Iroha account {}) for an amount of {}",
          settlementAccountId,
          this.settlementEngine.getIrohaAccountId(),
          peerIrohaAccountId,
          scaledAmount.toString()
      );

      // Only allow a single peer at a time to access this part of the code
      // in order to properly handle idempotent requests
      synchronized (this.store) {
        Integer requestStatus = this.store.getRequestStatus(idempotencyKey);
        if (requestStatus != null) {
          this.logger.info("Skipping the settlement request as it was already processed before");

          return new ResponseEntity<>(headers, HttpStatus.resolve(requestStatus));
        } else {
          // Perform the actual ledger settlement
          this.settlementEngine.transfer(peerIrohaAccountId, scaledAmount);

          // Save any leftovers due to precision loss
          BigDecimal precisionLoss = scalingResult.getValue();
          this.store.saveLeftover(settlementAccountId, precisionLoss);

          // Persist the status of the request
          this.store.saveRequestStatus(idempotencyKey, HttpStatus.CREATED.value());
        }
      }

      return new ResponseEntity<>(headers, HttpStatus.CREATED);
    } catch (IrohaException err) {
      this.logger.error("Could not send transfer command to Iroha: {}", err.getMessage());

      // Fatal error
      return new ResponseEntity<>(headers, HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  @Override
  public ResponseEntity<byte[]> handleIncomingMessage(
      @RequestBody byte[] message,
      @PathVariable String settlementAccountId
  ) {
    String messageString = new String(message, StandardCharsets.UTF_8);

    this.logger.info("POST /accounts/{}/messages {}", settlementAccountId, messageString);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(APPLICATION_OCTET_STREAM);

    try {
      PaymentDetailsMessage paymentDetailsRequest = JSON_FACTORY.fromString(messageString, PaymentDetailsMessage.class);

      // Save peer's Iroha account id
      this.store.savePeerIrohaAccountId(settlementAccountId, paymentDetailsRequest.getIrohaAccountId());

      this.logger.info(
          "Got peer's Iroha account id ({}) corresponding to settlement account {}",
          paymentDetailsRequest.getIrohaAccountId(),
          settlementAccountId
      );

      PaymentDetailsMessage paymentDetailsResponse = new PaymentDetailsMessage(
          this.settlementEngine.getIrohaAccountId()
      );

      this.logger.info(
          "Serialized PaymentDetailsMessage object to be sent to peer: " + JSON_FACTORY.toString(paymentDetailsResponse)
      );

      // Respond with our own Iroha account id
      return new ResponseEntity<>(
          JSON_FACTORY.toString(paymentDetailsResponse).getBytes(), headers, HttpStatus.CREATED
      );
    } catch (IOException err) {
      this.logger.error("Invalid payment details message: {}", err.getMessage());
      this.logger.info("Only payment details messages are accepted via /accounts/:id/messages");

      // Fatal error
      return new ResponseEntity<>(headers, HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }
}
