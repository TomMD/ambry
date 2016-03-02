package com.github.ambry.network;

import com.github.ambry.config.NetworkConfig;
import com.github.ambry.utils.Time;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The NetworkClient provides a method for sending a list of requests in the form of {@link Send} to a host:port,
 * and receive responses for sent requests. Requests that come in via {@link #sendAndPoll(java.util.List)} call,
 * that could not be immediately sent is queued and an attempt will be made in subsequent invocations of the call (or
 * until they time out).
 * (Note: We will empirically determine whether, rather than queueing a request,
 * a request should be failed if connections could not be checked out if pool limit for its hostPort has been reached
 * and all connections to the hostPort are unavailable).
 */
public class NetworkClient implements Closeable {
  private final Selector selector;
  private final ConnectionTracker connectionTracker;
  private final NetworkConfig networkConfig;
  private final NetworkRequestMetrics networkRequestMetrics;
  private final Time time;
  private final LinkedList<RequestMetadata> pendingRequests;
  private final HashMap<String, RequestMetadata> connectionIdToRequestInFlight;
  private final int checkoutTimeoutMs;
  private final int POLL_TIMEOUT_MS = 10;
  private boolean closed = false;
  private static final Logger logger = LoggerFactory.getLogger(NetworkClient.class);

  /**
   * Instantiates a NetworkClient.
   * @param selector the {@link Selector} for this NetworkClient
   * @param connectionTracker the {@link ConnectionTracker} for this NetworkClient
   * @param networkConfig the {@link NetworkConfig} for this NetworkClient
   * @param networkRequestMetrics the {@link NetworkRequestMetrics} that this NetworkClient should use in
   *                              constructing {@link NetworkSend} objects that are handed to its selector.
   * @param checkoutTimeoutMs the maximum time a request should remain in this NetworkClient's pending queue waiting
   *                          for an available connection to its destination.
   * @param time The Time instance to use.
   */
  public NetworkClient(Selector selector, ConnectionTracker connectionTracker, NetworkConfig networkConfig,
      NetworkRequestMetrics networkRequestMetrics, int checkoutTimeoutMs, Time time) {
    this.selector = selector;
    this.connectionTracker = connectionTracker;
    this.networkConfig = networkConfig;
    this.networkRequestMetrics = networkRequestMetrics;
    this.checkoutTimeoutMs = checkoutTimeoutMs;
    this.time = time;
    pendingRequests = new LinkedList<RequestMetadata>();
    connectionIdToRequestInFlight = new HashMap<String, RequestMetadata>();
  }

  /**
   * Attempt to send the given requests and poll for responses from the network. Any requests that could not be
   * sent out will be added to a queue. Every time this method is called, it will first attempt sending the requests
   * in the queue (or time them out) and then attempt sending the newly added requests.
   * @param requestInfos the list of {@link RequestInfo} representing the requests that need to be sent out. This
   *                     could be empty.
   * @return a list of {@link ResponseInfo} representing the responses received for any requests that were sent out
   * so far.
   * @throws IOException if the {@link Selector} associated with this NetworkClient throws
   * @throws IllegalStateException if the NetworkClient is closed.
   */
  public List<ResponseInfo> sendAndPoll(List<RequestInfo> requestInfos)
      throws IOException {
    if (closed) {
      throw new IllegalStateException("The NetworkClient is closed.");
    }
    List<ResponseInfo> responseInfoList = new ArrayList<ResponseInfo>();
    for (RequestInfo requestInfo : requestInfos) {
      pendingRequests.add(new RequestMetadata(time.milliseconds(), requestInfo, null));
    }
    List<NetworkSend> sends = prepareSends(responseInfoList);
    try {
      selector.poll(POLL_TIMEOUT_MS, sends);
      handleSelectorEvents(responseInfoList);
    } catch (IOException e) {
      //@todo: add to a metric.
      logger.error("Received exception while polling the selector, closing NetworkClient.", e);
      close();
      throw e;
    }
    return responseInfoList;
  }

  /**
   * Process the requests in the pendingRequestsQueue. Create {@link ResponseInfo} for those requests that have timed
   * out while waiting in the queue. Then, attempt to prepare {@link NetworkSend}s by checking out connections for
   * the rest of the requests in the queue.
   * @param responseInfoList the list to populate with responseInfos for requests that timed out waiting for
   *                         connections.
   * @return the list of {@link NetworkSend} objects to hand over to the Selector.
   */
  private List<NetworkSend> prepareSends(List<ResponseInfo> responseInfoList) {
    List<NetworkSend> sends = new ArrayList<NetworkSend>();
    ListIterator<RequestMetadata> iter = pendingRequests.listIterator();

    /* Drop requests that have waited too long */
    while (iter.hasNext()) {
      RequestMetadata requestMetadata = iter.next();
      if (time.milliseconds() - requestMetadata.requestQueuedTimeMs > checkoutTimeoutMs) {
        responseInfoList.add(
            new ResponseInfo(requestMetadata.requestInfo.getRequest(), NetworkClientErrorCode.ConnectionUnavailable,
                null));
        iter.remove();
      } else {
        // Since requests are ordered by time, once the first request that cannot be dropped is found,
        // we let that and the rest be iterated over in the next while loop. Just move the cursor backwards as this
        // element needs to be processed.
        iter.previous();
        break;
      }
    }

    while (iter.hasNext()) {
      RequestMetadata requestMetadata = iter.next();
      try {
        String host = requestMetadata.requestInfo.getHost();
        Port port = requestMetadata.requestInfo.getPort();
        String connId = connectionTracker.checkOutConnection(host, port);
        if (connId == null) {
          if (connectionTracker.mayCreateNewConnection(host, port)) {
            connId = selector.connect(new InetSocketAddress(host, port.getPort()), networkConfig.socketSendBufferBytes,
                networkConfig.socketReceiveBufferBytes, port.getPortType());
            connectionTracker.addNewConnection(host, port, connId);
          }
        } else {
          sends.add(new NetworkSend(connId, requestMetadata.requestInfo.getRequest(), networkRequestMetrics, time));
          requestMetadata.connId = connId;
          connectionIdToRequestInFlight.put(connId, requestMetadata);
          iter.remove();
        }
      } catch (IOException e) {
        //@todo: add to a metric.
        logger.error("Received exception while checking out a connection", e);
      }
    }
    return sends;
  }

  /**
   * Handle Selector events after a poll: newly established connections, new disconnections, newly completed sends and
   * receives.
   * @param responseInfoList the list to populate with {@link ResponseInfo} objects for responses created based on
   *                         the selector events.
   */
  private void handleSelectorEvents(List<ResponseInfo> responseInfoList) {
    for (String connId : selector.connected()) {
      connectionTracker.checkInConnection(connId);
    }

    for (String connId : selector.disconnected()) {
      connectionTracker.removeConnection(connId);
      RequestMetadata requestMetadata = connectionIdToRequestInFlight.remove(connId);
      if (requestMetadata != null) {
        responseInfoList
            .add(new ResponseInfo(requestMetadata.requestInfo.getRequest(), NetworkClientErrorCode.NetworkError, null));
      }
    }

    for (NetworkReceive recv : selector.completedReceives()) {
      String connId = recv.getConnectionId();
      connectionTracker.checkInConnection(connId);
      RequestMetadata requestMetadata = connectionIdToRequestInFlight.remove(connId);
      if (requestMetadata != null) {
        responseInfoList.add(
            new ResponseInfo(requestMetadata.requestInfo.getRequest(), null, recv.getReceivedBytes().getPayload()));
      } else {
        logger.trace("Received response for a request that timed out");
      }
    }
  }

  /**
   * Close the NetworkClient and cleanup.
   */
  @Override
  public void close() {
    selector.close();
    closed = true;
  }

  /**
   * A class that consists of a {@link RequestInfo} with the time at which it is added to the queue.
   */
  private class RequestMetadata {
    // the time at which this request was queued.
    long requestQueuedTimeMs;
    // the RequestInfo associated with the request.
    RequestInfo requestInfo;
    // if this request is in flight, the connection id on which it is in flight; else null.
    String connId;

    RequestMetadata(long requestQueuedTimeMs, RequestInfo requestInfo, String connId) {
      this.requestInfo = requestInfo;
      this.requestQueuedTimeMs = requestQueuedTimeMs;
      this.connId = connId;
    }
  }
}
