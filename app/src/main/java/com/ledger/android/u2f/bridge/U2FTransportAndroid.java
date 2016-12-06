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

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.*;
import android.util.Log;

import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;

import com.ledger.android.u2f.bridge.utils.Dump;

public class U2FTransportAndroid {

   private boolean stopped;
   private UsbManager usbManager;
   private U2FTransportAndroidHID transport;
   private final LinkedBlockingQueue<Boolean> gotRights = new LinkedBlockingQueue<Boolean>(1);

   private static final String LOG_TAG = "U2FTransportAndroid";

   private static final String ACTION_USB_PERMISSION = "USB_PERMISSION";

   /**
    * Receives broadcast when a supported USB device is attached, detached or
    * when a permission to communicate to the device has been granted.
    */
   private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
         String action = intent.getAction();
         UsbDevice usbDevice = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
         String deviceName = usbDevice.getDeviceName();

         if (ACTION_USB_PERMISSION.equals(action)) {
            boolean permission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,
                  false);
            // sync with connect
            gotRights.clear();
            gotRights.add(permission);
            context.unregisterReceiver(mUsbReceiver);
         }
      }
   };

   public U2FTransportAndroid(Context context) {
      usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
   }

   public boolean isPluggedIn() {
      return getDevice(usbManager) != null;
   }

   public void markStopped() {
      stopped = true;
   }

   public U2FTransportAndroidHID getTransport() {
      return transport;
   }

   public boolean connect(final Context context, final U2FTransportFactoryCallback callback) {
      if (transport != null) {
         try {
            transport.close();
         } catch (Exception e) {
         }
      }
      IntentFilter filter = new IntentFilter();
      filter.addAction(ACTION_USB_PERMISSION);
      context.registerReceiver(mUsbReceiver, filter);


      final UsbDevice device = getDevice(usbManager);
      final Intent intent = new Intent(ACTION_USB_PERMISSION);

      gotRights.clear();
      usbManager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, 0));
      // retry because of InterruptedException
      while (true) {
         try {
            // gotRights.take blocks until the UsbManager gives us the rights via callback to the BroadcastReceiver
            // this might need an user interaction
            if (gotRights.take()) {
               if (!stopped) {
                  transport = open(usbManager, device);
                  callback.onConnected((transport != null ? true : false));
               }
               return true;
            } else {
               if (!stopped) {
                  callback.onConnected(false);
               }
               return true;
            }
         } catch (InterruptedException ignored) {
         }
      }
   }

   public static UsbDevice getDevice(UsbManager manager) {      
      HashMap<String, UsbDevice> deviceList = manager.getDeviceList();      
      for (UsbDevice device : deviceList.values()) {
         if ((device.getDeviceClass() == UsbConstants.USB_CLASS_HID) || (device.getDeviceClass() == UsbConstants.USB_CLASS_PER_INTERFACE)) {
            return device;
         }
      }
      return null;
   }

   private static final int LIBUSB_REQUEST_GET_DESCRIPTOR = 0x06;
   private static final int LIBUSB_DT_REPORT = 0x22;
   private static final int LIBUSB_RECIPIENT_INTERFACE = 0x01;


   private static int getBytes(byte[] data, int length, int size, int cur) {
      int result = 0;
      if (cur + size >= length) {
         return 0;
      }
      switch(size) {
         case 0:
            break;
         case 1:
            result = (data[cur + 1] & 0xff);
            break;
         case 2:
            result = ((data[cur + 2] & 0xff) << 8) | (data[cur + 1] & 0xff);
            break;
         case 3:
            result = ((data[cur + 4] & 0xff) << 24)  | ((data[cur + 3] & 0xff) << 16) | ((data[cur + 2] & 0xff) << 8) | (data[cur + 1] & 0xff);
            break;               
      }
      return result;
   }

   private static int getUsage(byte[] report) {
      return getUsagePageOrUsage(report, report.length, true);
   }

   private static int getUsagePage(byte[] report) {
      return getUsagePageOrUsage(report, report.length, false);
   }

   private static int getUsagePageOrUsage(byte[] data, int size, boolean getUsage) {
      int result = -1;
      int i = 0;
      int size_code;
      int data_len, key_size;
      while (i < size) {
         int key = (data[i] & 0xff);
         int key_cmd = key & 0xfc;
         if ((key & 0xf0) == 0xf0) {
            if (i + 1 < size) {
               data_len = data[i + 1];
            }
            else {
               data_len = 0;
            }
            key_size = 3;
         }
         else {
            size_code = key & 0x03;
            switch(size_code) {
               case 0:
               case 1:
               case 2:
                  data_len = size_code;
                  break;
               case 3:
                  data_len = 4;
                  break;
               default:
                  data_len = 0;
                  break;
            }  
            key_size = 1;
         }
         if ((key_cmd == 0x04) && !getUsage) {
            result = getBytes(data, size, data_len, i);
            break;
         }        
         if ((key_cmd == 0x08) && getUsage)  {
            result = getBytes(data, size, data_len, i);
            break;            
         }
         i += data_len + key_size;
      }
      return result;
   }

   public static U2FTransportAndroidHID open(UsbManager manager, UsbDevice device) {
      // Must only be called once permission is granted (see http://developer.android.com/reference/android/hardware/usb/UsbManager.html)
      // Important if enumerating, rather than being awaken by the intent notification
      for (int interfaceIndex=0; interfaceIndex<device.getInterfaceCount(); interfaceIndex++) {
         UsbInterface dongleInterface = device.getInterface(interfaceIndex);
         UsbEndpoint in = null;
         UsbEndpoint out = null;
         for (int i = 0; i < dongleInterface.getEndpointCount(); i++) {
            UsbEndpoint tmpEndpoint = dongleInterface.getEndpoint(i);
            if (tmpEndpoint.getDirection() == UsbConstants.USB_DIR_IN) {
               in = tmpEndpoint;
            } else {
               out = tmpEndpoint;
            }
         }
         UsbDeviceConnection connection = manager.openDevice(device);
         boolean claimed = connection.claimInterface(dongleInterface, true);
         byte[] descriptor = new byte[256];
         try {                        
            int result = connection.controlTransfer(UsbConstants.USB_DIR_IN | LIBUSB_RECIPIENT_INTERFACE, LIBUSB_REQUEST_GET_DESCRIPTOR, (LIBUSB_DT_REPORT << 8), interfaceIndex, descriptor, descriptor.length, 2000);
         }
         catch(Exception e) {
            e.printStackTrace();
         }
         if ((getUsage(descriptor) == FIDO_USAGE) && (getUsagePage(descriptor) == FIDO_USAGE_PAGE)) {
            return new U2FTransportAndroidHID(connection, dongleInterface, in, out, TIMEOUT);   
         }
         else {
            connection.releaseInterface(dongleInterface);
            connection.close();
         }
      }
      return null;
      
   }

   private static final int FIDO_USAGE = 0x01;
   private static final int FIDO_USAGE_PAGE = 0xf1d0;

   private static final int TIMEOUT = 20000;
}
