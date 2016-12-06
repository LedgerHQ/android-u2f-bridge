/*
*******************************************************************************
*   Android U2F USB Bridge
*   (c) 2016 Ledger
*
*  Licensed under the Apache License, Version 2.0 (the "License");
*  you may not use this file except in compliance with the License.
*  You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
*   Unless required by applicable law or agreed to in writing, software
*   distributed under the License is distributed on an "AS IS" BASIS,
*   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
*  See the License for the specific language governing permissions and
*   limitations under the License.
********************************************************************************/

package com.ledger.android.u2f.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class U2FHelper {

   private static final int CHANNEL_BROADCAST = 0xffffffff;   

   private int channel; 

   public U2FHelper() {
      channel = CHANNEL_BROADCAST;
   }

   public int getChannel() {
      return channel;
   }

   public void setChannel(int channel) {
      this.channel = channel;
   }

   public byte[] wrapCommandAPDU(byte tag, byte[] command, int packetSize) throws IOException {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      int sequenceIdx = 0;
      int offset = 0;
      output.write(channel >> 24);
      output.write(channel >> 16);
      output.write(channel >> 8);
      output.write(channel);
      output.write(tag);
      output.write(command.length >> 8);
      output.write(command.length);
      int blockSize = (command.length > packetSize - 7 ? packetSize - 7 : command.length);
      output.write(command, offset, blockSize);
      offset += blockSize;
      while (offset != command.length) {
         output.write(channel >> 24);
         output.write(channel >> 16);         
         output.write(channel >> 8);
         output.write(channel);
         output.write(sequenceIdx);
         sequenceIdx++;
         blockSize = (command.length - offset > packetSize - 5 ? packetSize - 5 : command.length - offset);
         output.write(command, offset, blockSize);
         offset += blockSize;
      }
      if ((output.size() % packetSize) != 0) {
         byte[] padding = new byte[packetSize - (output.size() % packetSize)];
         output.write(padding, 0, padding.length);
      }
      return output.toByteArray();
   }

   public byte[] unwrapResponseAPDU(byte tag, byte[] data, int packetSize) throws IOException {
      ByteArrayOutputStream response = new ByteArrayOutputStream();
      int offset = 0;
      int responseLength;
      int sequenceIdx = 0;
      int readChannel;
      if ((data == null) || (data.length < 7)) {
         return null;
      }
      readChannel = ((data[offset] & 0xff) << 24) | ((data[offset + 1] & 0xff) << 16) | ((data[offset + 2] & 0xff) << 8) | (data[offset + 3] & 0xff);
      if (readChannel != channel) {
         if (channel == CHANNEL_BROADCAST) {
            channel = readChannel;
         }
         else {
            throw new IOException("Invalid channel");
         }
      }
      offset += 4;
      if (data[offset++] != tag) {
         throw new IOException("Invalid command");
      }
      responseLength = ((data[offset++] & 0xff) << 8);
      responseLength |= (data[offset++] & 0xff);
      if (data.length < 7 + responseLength) {
         return null;
      }
      int blockSize = (responseLength > packetSize - 7 ? packetSize - 7 : responseLength);
      response.write(data, offset, blockSize);
      offset += blockSize;
      while (response.size() != responseLength) {
         if (offset == data.length) {
            return null;
         }
         readChannel = ((data[offset] & 0xff) << 24) | ((data[offset + 1] & 0xff) << 16) | ((data[offset + 2] & 0xff) << 8) | (data[offset + 3] & 0xff);
         offset += 4;
         if (readChannel != channel) {
            throw new IOException("Invalid channel");            
         }
         if (data[offset++] != sequenceIdx) {
            throw new IOException("Invalid sequence");
         }
         blockSize = (responseLength - response.size() > packetSize - 5 ? packetSize - 5 : responseLength - response.size());
         if (blockSize > data.length - offset) {
            return null;
         }
         response.write(data, offset, blockSize);
         offset += blockSize;
         sequenceIdx++;
      }
      return response.toByteArray();
   }

}
