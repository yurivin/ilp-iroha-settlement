package org.interledger.settlement.iroha;

import static javax.xml.bind.DatatypeConverter.parseHexBinary;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import org.interledger.settlement.common.model.SettlementQuantity;
import org.interledger.settlement.iroha.IrohaException;
import org.interledger.settlement.iroha.config.DefaultArgumentValues;
import org.interledger.settlement.iroha.store.Store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.util.ExponentialBackOff;
import io.grpc.StatusRuntimeException;
import io.reactivex.functions.Consumer;
import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.Endpoint.ToriiResponse;
import iroha.protocol.QryResponses.AccountResponse;
import iroha.protocol.TransactionOuterClass;
import jp.co.soramitsu.crypto.ed25519.Ed25519Sha3;
import jp.co.soramitsu.iroha.java.ErrorResponseException;
import jp.co.soramitsu.iroha.java.IrohaAPI;
import jp.co.soramitsu.iroha.java.QueryAPI;
import jp.co.soramitsu.iroha.java.Transaction;
import jp.co.soramitsu.iroha.java.TransactionStatusObserver;
import jp.co.soramitsu.iroha.java.Utils;
import jp.co.soramitsu.iroha.java.ValidationException;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.PostConstruct;

@Component
public class SettlementEngine {
  private final Logger logger = LoggerFactory.getLogger(this.getClass());

  private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();

  // The number of transactions to be retrieved on each Iroha poll query
  private static final int TRANSACTIONS_PAGE_SIZE = 10;

  @Value("${asset-id}")
  private String assetId;

  @Value("${asset-scale:" + DefaultArgumentValues.ASSET_SCALE + "}")
  private String assetScale;

  @Value("${connector-url:" + DefaultArgumentValues.CONNECTOR_URL + "}")
  private String connectorUrl;

  @Getter
  @Value("${iroha-account-id}")
  private String irohaAccountId;

  @Value("${keypair-name}")
  private String keypairName;

  @Value("${torii-url:" + DefaultArgumentValues.TORII_URL + "}")
  private String toriiUrl;

  private KeyPair irohaAccountKeypair;

  private IrohaAPI irohaApi;
  private QueryAPI queryApi;

  @Autowired
  private Store store;

  /**
   * <p>Initializes the account keys and connection to Iroha.</p>
   */
  @PostConstruct
  public void init() {
    // Read and initialize account keys
    try {
      String privKey = new String(Files.readAllBytes(
          Paths.get(this.keypairName + ".priv")
      ));
      String pubKey = new String(Files.readAllBytes(
          Paths.get(this.keypairName + ".pub")
      ));

      this.irohaAccountKeypair = Ed25519Sha3.keyPairFromBytes(
          parseHexBinary(privKey),
          parseHexBinary(pubKey)
      );
    } catch (IOException err) {
      this.logger.error("Could not read key pair: {}", err.getMessage());

      // Fatal error, so we exit
      System.exit(1);
    } catch (IllegalArgumentException err) {
      this.logger.error("One of the keys is invalid: {}", err.getMessage());

      // Fatal error, so we exit
      System.exit(1);
    }

    // Initialize Iroha APIs
    try {
      URL irohaUrl = new URL(this.toriiUrl);
      this.irohaApi = new IrohaAPI(irohaUrl.getHost(), irohaUrl.getPort()); 
      this.queryApi = new QueryAPI(this.irohaApi, this.irohaAccountId, this.irohaAccountKeypair);
    } catch (MalformedURLException err) {
      // Should be unreachable, as validation is done at startup
    }

    // Make sure the provided Iroha account is correct by performing a simple query
    try {
      this.queryApi.getAccount(this.irohaAccountId);
    } catch (StatusRuntimeException err) {
      this.logger.error("Error querying Iroha: {}", err.getMessage());

      // Fatal error, so we exit
      System.exit(1);
    } catch (ErrorResponseException err) {
      this.logger.error("Error response from Iroha on getAccount: {}", err.getMessage());

      // Fatal error, so we exit
      System.exit(1);
    } catch (ValidationException err) {
      this.logger.error("Iroha transaction failed validation: {}", err.getMessage());

      // Fatal error, so we exit
      System.exit(1);
    }
  }

  /**
   * <p>Sends a TransferAsset command to Iroha for transferring a given asset amount to a recipient.</p>
   *
   * @param toIrohaAccountId The recipient of the asset transfer.
   *
   * @param amount           The amount that is to be transferred.
   *
   * @return
   */
  @Retryable(
      value = { IrohaException.class }, 
      maxAttempts = 10,
      backoff = @Backoff(delay = 1000, multiplier = 2)
  )
  public void transfer(String toIrohaAccountId, BigDecimal amount) throws IrohaException {
    Consumer<? super ToriiResponse> irohaErrorConsumer = err -> {
      throw new IrohaException(err);
    };
    Consumer<? super Throwable> internalErrorConsumer = err -> {
      throw new IrohaException(err);
    };

    TransactionStatusObserver txObserver = TransactionStatusObserver.builder()
        .onNotReceived(irohaErrorConsumer)
        .onMstExpired(irohaErrorConsumer)
        .onUnrecognizedStatus(irohaErrorConsumer)
        .onError(internalErrorConsumer)
        .build();

    TransactionOuterClass.Transaction tx = Transaction.builder(this.irohaAccountId)
        .transferAsset(this.irohaAccountId, toIrohaAccountId, this.assetId, "ILP Settlement", amount)
        .sign(this.irohaAccountKeypair)
        .build();

    this.irohaApi.transaction(tx)
        .blockingSubscribe(txObserver);
  }

  // Regularly poll Iroha for transfers to this instance's Iroha account in order
  // to keep track of incoming settlements and notify the connector
  @Scheduled(fixedRate = 1000)
  private void pollIroha() {
    // Only retrieve transactions that are newer than the last checked transaction
    List<TransactionOuterClass.Transaction> newTxs = this.queryApi.getAccountAssetTransactions(
        this.irohaAccountId,
        this.assetId,
        this.TRANSACTIONS_PAGE_SIZE,
        this.store.getLastCheckedTxHash()
    )
        .getTransactionsList();

    // Check all retrieved transactions for settlement related transfers
    for (TransactionOuterClass.Transaction tx : newTxs) {
      handleTx(tx);
    }

    // We can't query with an empty transactions hashes list
    List<String> uncheckedTxHashes = this.store.getUncheckedTxHashes();
    if (uncheckedTxHashes.size() > 0) {
      List<TransactionOuterClass.Transaction> uncheckedTxs = this.queryApi.getTransactions(
          this.store.getUncheckedTxHashes()
      )
          .getTransactionsList();
    
      // Re-check all previously unchecked transactions
      for (TransactionOuterClass.Transaction tx : uncheckedTxs) {
        handleTx(tx);
      }
    }
  }

  // Checks a transaction for settlement related transfers and notifies the connector if needed
  private void handleTx(TransactionOuterClass.Transaction tx) {
    boolean successfullyCheckedTx = true;

    // Only check newly seen transactions
    if (!this.store.wasTxChecked(Utils.toHexHash(tx))) {
      List<Command> commands = tx.getPayload().getReducedPayload().getCommandsList();
      for (Command cmd : commands) {
        // We assume that settlements are only performed via TransferAsset commands
        if (cmd.hasTransferAsset()) {
          TransferAsset transferCmd = cmd.getTransferAsset();

          // Settlement related transfers have a pre-established description
          if (transferCmd.getDescription().equals("ILP Settlement")) {
            // Retrieve the settlement account corresponding to the peer that initiated the transfer
            String settlementAccountId = this.store.getSettlementAccountId(transferCmd.getSrcAccountId());

            // If this check passes, we can be mostly sure that the transfer was part
            // of a settlement with this instance's Iroha account as a recipient
            if (settlementAccountId != null
                && transferCmd.getDestAccountId().equals(this.irohaAccountId)
                && transferCmd.getAssetId().equals(this.assetId)) {
              try {
                SettlementQuantity quantity = new SettlementQuantity(
                    // The amount of a Quantity object is unscaled
                    new BigDecimal(new BigDecimal(transferCmd.getAmount()).unscaledValue()),
                    Integer.parseInt(this.assetScale)
                );

                // We can't serialize using Google's http client @Key because the connector expects a String as amount
                // and @Key does not support such configuration (Jackson does via @JsonFormat(shape = ...))
                String serializedQuantity = new ObjectMapper().writeValueAsString(quantity);

                this.logger.info("Serialized Quantity object to be sent to connector: " + serializedQuantity);

                GenericUrl connectorMessageUrl = new GenericUrl(this.connectorUrl);
                connectorMessageUrl.appendRawPath("/accounts/" + settlementAccountId + "/settlements");

                HttpHeaders headers = new HttpHeaders();
                headers.setAccept(APPLICATION_JSON_VALUE);
                headers.setContentType(APPLICATION_JSON_VALUE);
                headers.set("Idempotency-Key", UUID.randomUUID().toString());

                HttpRequestFactory requestFactory = HTTP_TRANSPORT.createRequestFactory();

                HttpRequest request = requestFactory.buildPostRequest(
                    connectorMessageUrl,
                    ByteArrayContent.fromString(APPLICATION_JSON_VALUE, serializedQuantity)
                );
                request.setHeaders(headers);

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

                this.logger.info(
                    "Notifying connector of new settlement on settlement account {} "
                    + "(from Iroha account {} to Iroha account {}) for an amount of {}",
                    settlementAccountId,
                    transferCmd.getSrcAccountId(),
                    transferCmd.getDestAccountId(),
                    transferCmd.getAmount()
                );

                // Notify the connector
                HttpResponse response = request.execute();

                this.logger.info("The connector responded with: {}", response.getStatusMessage());
              } catch (IOException err) {
                this.logger.error("Error while notifying connector of settlement: {}", err.getMessage());

                // We encountered errors, so we mark the transaction as unchecked and skip processing the rest
                successfullyCheckedTx = false;
                break;
              }
            }
          }
        }
      }

      // Mark the current transaction as either checked or unchecked
      if (successfullyCheckedTx) {
        this.store.saveCheckedTx(Utils.toHexHash(tx));
      } else {
        this.store.saveUncheckedTx(Utils.toHexHash(tx));
      }
    }
  }
}
