/*
*******************************************************************************
*   Android U2F USB BridgE 
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

import java.util.Iterator;
import java.util.Set;
import java.util.Vector;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.view.ViewGroup;
import android.app.Activity;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;
import android.util.Base64;

@SuppressLint("NewApi")
public class MainActivity extends AppCompatActivity {
	
    private static final String TAG = "LedgerU2FBridge";

    private static final String ACTION_GOOGLE = "com.google.android.apps.authenticator.AUTHENTICATE";
    private static final String ACTION_LEDGER = "com.ledger.android.u2f.bridge.AUTHENTICATE";
    private static final String TAG_REQUEST = "request";
    private static final String TAG_RESULT_DATA = "resultData";
    private static final String TAG_JSON_TYPE = "type";
    private static final String TAG_JSON_APPID = "appId";
    private static final String TAG_JSON_CHALLENGE = "challenge";
    private static final String TAG_JSON_REGISTERED_KEYS = "registeredKeys";
    private static final String TAG_JSON_REGISTER_REQUESTS = "registerRequests";
    private static final String TAG_JSON_KEYHANDLE = "keyHandle";
    private static final String TAG_JSON_VERSION = "version";
    private static final String TAG_JSON_REQUESTID = "requestId";
    private static final String TAG_JSON_RESPONSEDATA = "responseData";    
    private static final String TAG_JSON_CLIENTDATA = "clientData";
    private static final String TAG_JSON_SIGNATUREDATA = "signatureData";
    private static final String TAG_JSON_REGISTRATIONDATA = "registrationData";
    private static final String TAG_JSON_TYP = "typ";
    private static final String TAG_JSON_ORIGIN = "origin";
    private static final String TAG_JSON_CID_PUBKEY = "cid_pubkey";

    private static final String SIGN_REQUEST_TYPE = "u2f_sign_request";
    private static final String SIGN_RESPONSE_TYPE = "u2f_sign_response";
    private static final String SIGN_RESPONSE_TYP = "navigator.id.getAssertion";
    private static final String REGISTER_REQUEST_TYPE = "u2f_register_request";
    private static final String REGISTER_RESPONSE_TYPE = "u2f_register_response";
    private static final String REGISTER_RESPONSE_TYP = "navigator.id.finishEnrollment";
    private static final String CID_UNAVAILABLE = "unavailable";

    private static final String VERSION_U2F_V2 = "U2F_V2";

    private static final int SW_OK = 0x9000;
    private static final int SW_USER_PRESENCE_REQUIRED = 0x6985;

    private class U2FContext {

        public U2FContext(String appId, byte[] challenge, Vector<byte[]> keyHandles, int requestId, boolean sign) {
            this.appId = appId;
            this.challenge = challenge;
            this.keyHandles = keyHandles;
            this.requestId = requestId;
            this.sign = sign;
        }

        public String getAppId() {
            return appId;
        }        

        public byte[] getChallenge() {
            return challenge;
        }

        public Vector<byte[]> getKeyHandles() {
            return keyHandles;
        }

        public void setChosenKeyHandle(byte[] chosenKeyHandle) {
            this.chosenKeyHandle = chosenKeyHandle;
        }

        public byte[] getChosenKeyHandle() {
            return chosenKeyHandle;
        }

        public int getRequestId() {
            return requestId;
        }

        public boolean isSign() {
            return sign;
        }

        private String appId;
        private byte[] challenge;
        private Vector<byte[]> keyHandles;
        private byte[] chosenKeyHandle;
        private int requestId;            
        private boolean sign;
    } 

    private class U2FAuthRunner extends Thread implements U2FTransportFactoryCallback {

        //private static final int PAUSE = 50;
        private static final int PAUSE = 300;

        private static final int FIDO_CLA = 0x00;
        private static final int FIDO_INS_AUTH = 0x02;
        private static final int FIDO_INS_REGISTER = 0x01;
        private static final int FIDO_P1_SIGN = 0x03;


        private U2FContext context;
        private U2FTransportAndroid transportBuilder;
        private boolean stopped;

        public U2FAuthRunner(U2FContext context) {
            this.context = context;
            transportBuilder = new U2FTransportAndroid(MainActivity.this);
        }

        public void markStopped() {
            stopped = true;
            transportBuilder.markStopped();
        }

        private boolean isResponseOK(byte[] response) {
            if ((response == null) || (response.length < 2)) {
                return false;
            }
            int sw = ((response[response.length - 2] & 0xff) << 8) | (response[response.length - 1] & 0xff);
            return sw == SW_OK;
        }

        private boolean isResponseBusy(byte[] response) {
            if ((response == null) || (response.length < 2)) {
                return false;
            }
            int sw = ((response[response.length - 2] & 0xff) << 8) | (response[response.length - 1] & 0xff);
            return sw == SW_USER_PRESENCE_REQUIRED;            
        }

        private byte[] processSign(U2FTransportAndroidHID transport) throws Exception {
            byte[] response = null;
            choiceLoop:
            for (byte[] keyHandle : context.getKeyHandles()) {
                if (stopped) {
                    break;
                }
                for(;;) {
                    if (stopped) {
                        break;
                    }
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    int msgLength = 32 + 32 + 1 + keyHandle.length;
                    bos.write(FIDO_CLA);
                    bos.write(FIDO_INS_AUTH);                    
                    bos.write(FIDO_P1_SIGN);
                    bos.write(0x00); // p2
                    bos.write(0x00); // extended length
                    bos.write(msgLength >> 8);
                    bos.write(msgLength & 0xff);
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");                    
                    bos.write(digest.digest(MainActivity.this.createClientData(context).getBytes("UTF-8")));
                    bos.write(digest.digest(context.getAppId().getBytes("UTF-8")));
                    bos.write(keyHandle.length);
                    bos.write(keyHandle);
                    bos.write(0x00);
                    bos.write(0x00);
                    byte[] authApdu = bos.toByteArray();
                    response = transport.exchange(authApdu);                    
                    if (isResponseOK(response)) {
                        context.setChosenKeyHandle(keyHandle);
                        break choiceLoop;
                    }
                    if (!isResponseBusy(response)) {
                        break;
                    }
                    else {
                        response = null;
                        Thread.sleep(PAUSE);
                    }
                }
            }
            return response;
        }

        private byte[] processRegister(U2FTransportAndroidHID transport) throws Exception {
            byte[] response = null;
            for (;;) {
                if (stopped) {
                    break;
                }                
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int msgLength = 32 + 32;
                bos.write(FIDO_CLA);
                bos.write(FIDO_INS_REGISTER);                    
                bos.write(0x00); // p1
                bos.write(0x00); // p2
                bos.write(0x00); // extended length
                bos.write(msgLength >> 8);
                bos.write(msgLength & 0xff);            
                MessageDigest digest = MessageDigest.getInstance("SHA-256");                    
                bos.write(digest.digest(MainActivity.this.createClientData(context).getBytes("UTF-8")));
                bos.write(digest.digest(context.getAppId().getBytes("UTF-8")));
                bos.write(0x00);
                bos.write(0x00);
                byte[] authApdu = bos.toByteArray();
                response = transport.exchange(authApdu);                    
                if (isResponseOK(response)) {
                    break;
                }                
                if (isResponseBusy(response)) {
                    response = null;
                    Thread.sleep(200);
                }
                else {              
                    response = null;   
                    break;
                }
            }
            return response;
        }


        public void onConnected(boolean success) {
            byte[] response = null;
            if (success) {
                U2FTransportAndroidHID transport = transportBuilder.getTransport();
                try {                    
                    transport.setDebug(true);
                    transport.init();
                    if (context.isSign()) {
                        response = processSign(transport);
                    }
                    else {
                        response = processRegister(transport);
                    }
                }
                catch(Exception e) {
                    e.printStackTrace();
                    response = null;
                }
                try {
                    transport.close();
                }
                catch(IOException e) {                    
                }
            }            
            else {
            }
            final byte[] sentResponse = (isResponseOK(response) ? response : null);
            MainActivity.this.runOnUiThread(new Runnable() {
                public void run() {
                    MainActivity.this.postResponse(sentResponse);
                }
            });
        }

        public void run() {
            while (!transportBuilder.isPluggedIn() && !stopped) {
                try {
                    Thread.sleep(PAUSE);
                }
                catch(InterruptedException e) {                    
                }
            }
            if (stopped) {
                return;
            }
            transportBuilder.connect(MainActivity.this, this);
        }
    }

		
    private Button mCancelButton;
    private U2FContext mU2FContext;
    private U2FAuthRunner mAuthThread;


    private U2FContext parseU2FContext(String data) {
        try {
            JSONObject json = new JSONObject(data);
            String requestType = json.getString(TAG_JSON_TYPE);
            if (requestType.equals(SIGN_REQUEST_TYPE)) {
                return parseU2FContextSign(json);
            }
            else
            if (requestType.equals(REGISTER_REQUEST_TYPE)) {
                return parseU2FContextRegister(json);
            }
            else {
                Log.e(TAG, "Invalid request type");
                return null;
            }
        }
        catch(JSONException e) {
            Log.e(TAG, "Error decoding request");
            return null;
        }

    }

    private U2FContext parseU2FContextSign(JSONObject json) {
        try {
            String appId = json.getString(TAG_JSON_APPID);
            byte[] challenge = Base64.decode(json.getString(TAG_JSON_CHALLENGE), Base64.URL_SAFE);
            int requestId = json.getInt(TAG_JSON_REQUESTID);
            JSONArray array = json.getJSONArray(TAG_JSON_REGISTERED_KEYS);
            Vector<byte[]> keyHandles = new Vector<byte[]>();
            for (int i=0; i<array.length(); i++) {
                JSONObject keyHandleItem = array.getJSONObject(i);
                if (!keyHandleItem.getString(TAG_JSON_VERSION).equals(VERSION_U2F_V2)) {
                    Log.e(TAG, "Invalid handle version");
                    return null;
                }
                byte[] keyHandle = Base64.decode(keyHandleItem.getString(TAG_JSON_KEYHANDLE), Base64.URL_SAFE);
                keyHandles.add(keyHandle);
            }
            return new U2FContext(appId, challenge, keyHandles, requestId, true);
        }
        catch(JSONException e) {
            Log.e(TAG, "Error decoding request");
            return null;
        }
    }

    private U2FContext parseU2FContextRegister(JSONObject json) {
        try {
            byte[] challenge = null;
            String appId = json.getString(TAG_JSON_APPID);            
            int requestId = json.getInt(TAG_JSON_REQUESTID);
            JSONArray array = json.getJSONArray(TAG_JSON_REGISTER_REQUESTS);
            for (int i=0; i<array.length(); i++) {
                // TODO : only handle USB transport if several are present
                JSONObject registerItem = array.getJSONObject(i);
                if (!registerItem.getString(TAG_JSON_VERSION).equals(VERSION_U2F_V2)) {
                    Log.e(TAG, "Invalid register version");
                    return null;
                }
                challenge = Base64.decode(registerItem.getString(TAG_JSON_CHALLENGE), Base64.URL_SAFE);
            }
            return new U2FContext(appId, challenge, null, requestId, false);
        }
        catch(JSONException e) {
            Log.e(TAG, "Error decoding request");
            return null;
        }
    }

    private String createU2FResponse(U2FContext context, byte[] data) {
        if (context.isSign()) {
            return createU2FResponseSign(context, data);
        }
        else {
            return createU2FResponseRegister(context, data);
        }
    }

    private String createClientData(U2FContext context) {
        try {            
            JSONObject clientData = new JSONObject();
            clientData.put(TAG_JSON_TYP, (context.isSign() ? SIGN_RESPONSE_TYP : REGISTER_RESPONSE_TYP));
            clientData.put(TAG_JSON_CHALLENGE, Base64.encodeToString(context.getChallenge(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
            clientData.put(TAG_JSON_ORIGIN, context.getAppId());
            clientData.put(TAG_JSON_CID_PUBKEY, CID_UNAVAILABLE);
            return clientData.toString();
        }    
        catch(Exception e) {
            Log.e(TAG, "Error encoding client data");
            return null;
        }            
    }

    private String createU2FResponseSign(U2FContext context, byte[] signature) {
        try {
            JSONObject response = new JSONObject();
            response.put(TAG_JSON_TYPE, SIGN_RESPONSE_TYPE);
            response.put(TAG_JSON_REQUESTID, context.getRequestId());
            JSONObject responseData = new JSONObject();
            responseData.put(TAG_JSON_KEYHANDLE, Base64.encodeToString(context.getChosenKeyHandle(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
            responseData.put(TAG_JSON_SIGNATUREDATA, Base64.encodeToString(signature, 0, signature.length - 2, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
            String clientData = createClientData(context);            
            responseData.put(TAG_JSON_CLIENTDATA, Base64.encodeToString(clientData.getBytes("UTF-8"), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
            response.put(TAG_JSON_RESPONSEDATA, responseData);
            return response.toString();
        }
        catch(Exception e) {
            Log.e(TAG, "Error encoding request");
            return null;
        }
    }

    private String createU2FResponseRegister(U2FContext context, byte[] registerResponse) {
        try {
            JSONObject response = new JSONObject();
            response.put(TAG_JSON_TYPE, REGISTER_RESPONSE_TYPE);
            response.put(TAG_JSON_REQUESTID, context.getRequestId());
            JSONObject responseData = new JSONObject();
            responseData.put(TAG_JSON_REGISTRATIONDATA, Base64.encodeToString(registerResponse, 0, registerResponse.length - 2, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
            responseData.put(TAG_JSON_VERSION, VERSION_U2F_V2);
            String clientData = createClientData(context);
            responseData.put(TAG_JSON_CLIENTDATA, Base64.encodeToString(clientData.getBytes("UTF-8"), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
            response.put(TAG_JSON_RESPONSEDATA, responseData);
            return response.toString();
        }
        catch(Exception e) {
            Log.e(TAG, "Error encoding request");
            return null;
        }
    }

    public void postResponse(byte[] responseData) {
        if (responseData == null) {
            finish();
            return;
        }
        String response = createU2FResponse(mU2FContext, responseData);
        if (response == null) {
            finish();
            return;
        }        
        Intent intent = getIntent();
        intent.putExtra(TAG_RESULT_DATA, response);
        setResult(Activity.RESULT_OK, intent);
        finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mAuthThread != null) {
            mAuthThread.markStopped();
        }
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);        
        setSupportActionBar(toolbar);
        mCancelButton = (Button) findViewById(R.id.cancel_button);
        mCancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAuthThread != null) {
                    mAuthThread.markStopped();
                }
                finish();
            }
        });

        TypefaceHelper.applyFonts((ViewGroup) getWindow().getDecorView());

        Intent intent = getIntent();

        if (!intent.getAction().equals(ACTION_GOOGLE) && !intent.getAction().equals(ACTION_LEDGER)) {
            Toast.makeText(MainActivity.this, R.string.unsupported_intent, Toast.LENGTH_LONG).show();
            finish();            
            return;
        }

        String request = intent.getStringExtra(TAG_REQUEST);
        if (request == null) {
            Log.e(TAG, "Request missing");
            finish();
            return;
        }

        mU2FContext = parseU2FContext(request);
        if (mU2FContext == null) {
            finish();
            return;
        }

        if (mAuthThread != null) {
            mAuthThread.markStopped();
        }
        mAuthThread = new U2FAuthRunner(mU2FContext);
        mAuthThread.start();
    }    
    
}
