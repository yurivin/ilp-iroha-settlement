package org.interledger.iroha.settlement.controller;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE;

import org.interledger.iroha.settlement.model.SettlementAccount;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SettlementController {
  protected final Logger logger = LoggerFactory.getLogger(this.getClass());

  /**
   * <p>Called by the Connector to inform the Settlement Engine that a new account was created within
   * the accounting system using the given account identifier.</p>
   *
   * @param account The {@link SettlementAccount} as supplied by the Connector.
   *
   * @return
   */
  @RequestMapping(
      path = "/accounts",
      method = RequestMethod.POST,
      consumes = APPLICATION_JSON_VALUE
  )
  public ResponseEntity<Void> setupAccount(
      @RequestBody final SettlementAccount account
  ) {
    // TODO: implement

    this.logger.info("POST /accounts { id: {} }", account.getId());

    return new ResponseEntity<>(HttpStatus.CREATED); 
  }

  /**
   * <p>Called by the Connector to inform the Settlement Engine that an account was deleted.</p>
   *
   * @param account The {@link SettlementAccount} as supplied by the Connector.
   *
   * @return
   */
  @RequestMapping(
      path = "/accounts/{account}",
      method = RequestMethod.DELETE
  )
  public ResponseEntity<Void> deleteAccount(
      @PathVariable final SettlementAccount account
  ) {
    // TODO: implement

    this.logger.info("DELETE /accounts/{}", account.getId());

    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  /**
   * <p>Called by the Connector to asynchronously trigger a settlement in the Settlement Engine.</p>
   *
   * @param idempotencyKey The idempotence identifier defined in the Settlement Engine RFC
   *                       (typed as a {@link String}, but should always be a Type4 UUID).
   *
   * @param account        The {@link SettlementAccount} as supplied by the Connector.
   *
   * @return
   */
  @RequestMapping(
      path = "/accounts/{account}/settlements",
      method = RequestMethod.POST,
      consumes = APPLICATION_JSON_VALUE,
      produces = APPLICATION_JSON_VALUE
  )
  public ResponseEntity<Void> performOutgoingSettlement(
      @RequestHeader("Idempotency-Key") final String idempotencyKey,
      @PathVariable final SettlementAccount account
  ) {
    // TODO: implement

    this.logger.info("POST /accounts/{}/settlements { Idempotency-Key: {} }", account.getId(), idempotencyKey);

    final HttpHeaders headers = new HttpHeaders();
    headers.setContentType(APPLICATION_JSON);

    return new ResponseEntity<>(headers, HttpStatus.CREATED);
  }

  /**
   * <p>Called by the Connector to process and respond to an incoming message from the peer's
   * Settlement Engine.</p>
   *
   * @param message A byte array of opaque data that was sent by the peer's Settlement Engine.
   *
   * @param account The {@link SettlementAccount} as supplied by the Connector.
   *
   * @return A byte array representing the response message to be sent to the peer's Settlement Engine.
   */
  @RequestMapping(
      path = "/accounts/{account}/messages",
      method = RequestMethod.POST,
      consumes = APPLICATION_OCTET_STREAM_VALUE,
      produces = APPLICATION_OCTET_STREAM_VALUE
  )
  public ResponseEntity<byte[]> handleIncomingMessage(
      @RequestBody final byte[] message,
      @PathVariable final SettlementAccount account
  ) {
    // TODO: implement

    this.logger.info("POST /accounts/{}/messages {}", account.getId(), message);

    final HttpHeaders headers = new HttpHeaders();
    headers.setContentType(APPLICATION_OCTET_STREAM);

    return new ResponseEntity<>(new String("foo").getBytes(), headers, HttpStatus.CREATED);
  }
}