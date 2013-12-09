package com.github.ambry.shared;

import com.github.ambry.utils.Utils;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

/**
 * Response of TTL request
 */
public class TTLResponse extends RequestOrResponse {

  private ServerErrorCode error;
  private static final int Error_Size_InBytes = 2;

  public TTLResponse(int correlationId, String clientId, ServerErrorCode error) {
    super(RequestResponseType.TTLResponse, Request_Response_Version, correlationId, clientId);
    this.error = error;
  }

  public ServerErrorCode getError() {
    return error;
  }

  public static TTLResponse readFrom(DataInputStream stream) throws IOException {
    RequestResponseType type = RequestResponseType.values()[stream.readShort()];
    if (type != RequestResponseType.TTLResponse) {
      throw new IllegalArgumentException("The type of request response is not compatible");
    }
    Short versionId  = stream.readShort();
    int correlationId = stream.readInt();
    String clientId = Utils.readIntString(stream);
    ServerErrorCode error = ServerErrorCode.values()[stream.readShort()];
    // ignore version for now
    return new TTLResponse(correlationId, clientId, error);
  }

  @Override
  public void writeTo(WritableByteChannel channel) throws IOException {
    if (bufferToSend == null) {
      bufferToSend = ByteBuffer.allocate((int) sizeInBytes());
      writeHeader();
      bufferToSend.putShort((short)error.ordinal());
      bufferToSend.flip();
    }
    if (bufferToSend.remaining() > 0) {
      channel.write(bufferToSend);
    }
  }

  @Override
  public boolean isSendComplete() {
    return bufferToSend.remaining() == 0;
  }

  @Override
  public long sizeInBytes() {
    // header + error
    return super.sizeInBytes() + Error_Size_InBytes;
  }
}
