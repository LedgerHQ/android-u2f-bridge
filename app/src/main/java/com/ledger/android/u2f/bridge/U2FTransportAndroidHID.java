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

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbRequest;
import android.util.Log;
import com.ledger.android.u2f.bridge.utils.Dump;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.Arrays;

public class U2FTransportAndroidHID {

   private static final String LOG_TAG = "U2FTransportAndroid";  

   private static final byte TAG_INIT = (byte)0x86;
   private static final byte TAG_MSG = (byte)0x83;    

   private UsbDeviceConnection connection;
   private UsbInterface dongleInterface;
   private UsbEndpoint in;
   private UsbEndpoint out;
   private U2FHelper helper;
   private int timeout;
   private byte transferBuffer[];
   private boolean debug;
   private Random random;

   public U2FTransportAndroidHID(UsbDeviceConnection connection, UsbInterface dongleInterface, UsbEndpoint in, UsbEndpoint out, int timeout) {
      this.connection = connection;
      this.dongleInterface = dongleInterface;
      this.in = in;
      this.out = out;
      this.timeout = timeout;
      transferBuffer = new byte[HID_BUFFER_SIZE];
      helper = new U2FHelper();
      random = new Random();
   }

   public void init() throws IOException {
      if (debug) {
         Log.d(LOG_TAG, "Initializing channel");
      }
      byte nonce[] = new byte[8];
      random.nextBytes(nonce);
      byte[] response = exchange(TAG_INIT, nonce);
      byte[] readNonce = new byte[8];
      System.arraycopy(response, 0, readNonce, 0, 8);
      if (!Arrays.equals(nonce, readNonce)) {
         throw new IOException("Invalid channel initialization");
      }
      int channel = ((response[8] & 0xff) << 24) | ((response[9] & 0xff) << 16) | ((response[10] & 0xff) << 8) | (response[11] & 0xff);
      helper.setChannel(channel);
      if (debug) {
         Log.d(LOG_TAG, "New channel " + helper.getChannel());
      }
   }

   public byte[] exchange(byte[] command) throws IOException {
      return exchange(TAG_MSG, command);
   }

   public byte[] exchange(byte tag, byte[] command) throws IOException {
      ByteArrayOutputStream response = new ByteArrayOutputStream();
      byte[] responseData = null;
      int offset = 0;
      int responseSize;
      if (debug) {
         Log.d(LOG_TAG, "=> " + Dump.dump(command));
      }
      command = helper.wrapCommandAPDU(tag, command, HID_BUFFER_SIZE);      
      UsbRequest requestWrite = new UsbRequest();
      if (!requestWrite.initialize(connection, out)) {
         throw new IOException();
      }
      while (offset != command.length) {
         int blockSize = (command.length - offset > HID_BUFFER_SIZE ? HID_BUFFER_SIZE : command.length - offset);
         System.arraycopy(command, offset, transferBuffer, 0, blockSize);
         if (debug) {
            Log.d(LOG_TAG, "wire => " + Dump.dump(transferBuffer));
         }
         if (!requestWrite.queue(ByteBuffer.wrap(transferBuffer), HID_BUFFER_SIZE)) {
            requestWrite.close();
            throw new IOException();
         }
         connection.requestWait();
         offset += blockSize;
      }
      ByteBuffer responseBuffer = ByteBuffer.allocate(HID_BUFFER_SIZE);
      UsbRequest requestRead = new UsbRequest();
      if (!requestRead.initialize(connection, in)) {
         requestRead.close();
         requestWrite.close();
         throw new IOException();
      }
      while ((responseData = helper.unwrapResponseAPDU(tag, response.toByteArray(), HID_BUFFER_SIZE)) == null) {
         responseBuffer.clear();
         if (!requestRead.queue(responseBuffer, HID_BUFFER_SIZE)) {
            requestRead.close();
            requestWrite.close();
            throw new IOException();
         }
         connection.requestWait();
         responseBuffer.rewind();
         responseBuffer.get(transferBuffer, 0, HID_BUFFER_SIZE);
         if (debug) {
            Log.d(LOG_TAG, "wire <= " + Dump.dump(transferBuffer));
         }
         response.write(transferBuffer, 0, HID_BUFFER_SIZE);
      }
      if (debug) {
         Log.d(LOG_TAG, "<= " + Dump.dump(responseData));
      }

      requestWrite.close();
      requestRead.close();
      return responseData;
   }

   public void close() throws IOException {
      connection.releaseInterface(dongleInterface);
      connection.close();
   }


   public void setDebug(boolean debugFlag) {
      this.debug = debugFlag;
   }   

   private static final int HID_BUFFER_SIZE = 64;
   private static final int SW1_DATA_AVAILABLE = 0x61;   
}
