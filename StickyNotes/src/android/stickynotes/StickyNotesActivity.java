/*
 * Copyright 2011, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.stickynotes;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentFilter.MalformedMimeTypeException;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.AuthAlgorithm;
import android.net.wifi.WifiConfiguration.GroupCipher;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConfiguration.PairwiseCipher;
import android.net.wifi.WifiConfiguration.Protocol;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;

public class StickyNotesActivity extends Activity {
    private static final String TAG = "stickynotes";	//for logging
    private boolean mResumed = false;
    private boolean mWriteMode = false;
    NfcAdapter mNfcAdapter;
    EditText mNote;
	boolean wifiMode;
	int wifiConfigIndex;
    WifiManager wifi;
	TextView textStatus;
    
    PendingIntent mNfcPendingIntent;
    IntentFilter[] mWriteTagFilters;
    IntentFilter[] mNdefExchangeFilters;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mNfcAdapter = NfcAdapter.getDefaultAdapter(this);
        setContentView(R.layout.mainscreen);
        
        if (mNfcAdapter == null)
        	toast("No NFC support!");
        
        wifiMode = false;
        wifiConfigIndex = -1; // First click of Wifi Button sets this to 0...Yeah poor style but w/e
        wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        
        textStatus = (TextView) findViewById(R.id.text_view);
        
		textStatus.setText("");
		textStatus.append("Choose your mode or write a message!");
        mNote = ((EditText) findViewById(R.id.note));
		
		
        if (mNfcAdapter != null) //device NFC capable
        {
	        mNote.addTextChangedListener(mTextWatcher);
	        
			// Handle all of our received NFC intents in this activity.
	        mNfcPendingIntent = PendingIntent.getActivity(this, 0,
	                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);
	
	        // Intent filters for reading a note from a tag or exchanging over p2p.
	        IntentFilter ndefDetected = new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED);
	        try {
	            ndefDetected.addDataType("text/plain");
	        } catch (MalformedMimeTypeException e) { }
	        mNdefExchangeFilters = new IntentFilter[] { ndefDetected };
	
	        // Intent filters for writing to a tag
	        IntentFilter tagDetected = new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED);
	        mWriteTagFilters = new IntentFilter[] { tagDetected };
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        if (mNfcAdapter != null) { //device NFC capable
	        // Sticky notes received from Android
	        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(getIntent().getAction())) {
	            NdefMessage[] messages = getNdefMessages(getIntent());
	            byte[] payload = messages[0].getRecords()[0].getPayload();
	            setNoteBody(new String(payload));
	            setIntent(new Intent()); // Consume this intent.
	        }
	        enableNdefExchangeMode();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mResumed = false;
        if (mNfcAdapter != null) { //device NFC capable
        	mNfcAdapter.disableForegroundNdefPush(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        // NDEF exchange mode
        if (!mWriteMode && NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction())) {
            NdefMessage[] msgs = getNdefMessages(intent);
            promptForContent(msgs[0]);
        }

        // Tag writing mode
        if (mWriteMode && NfcAdapter.ACTION_TAG_DISCOVERED.equals(intent.getAction())) 
        {
            Tag detectedTag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            //TODO: Decide button context handling scheme
            //We need some kind of context switch here or inside getNoteAsNdef
            //As it is, what is written in the note gets packed into the NDEF payload. 
            // Ideally, if we're passing facebook/twitter/4square/wifi info across, we dont
            //		want that stuff visible to the user and inside the note. 
            writeTag(getNoteAsNdef(), detectedTag);
        }
    }

    private TextWatcher mTextWatcher = new TextWatcher() {

        @Override
        public void onTextChanged(CharSequence arg0, int arg1, int arg2, int arg3) {

        }

        @Override
        public void beforeTextChanged(CharSequence arg0, int arg1, int arg2, int arg3) {

        }

        @Override
        public void afterTextChanged(Editable arg0) {
            if (mResumed) {
                mNfcAdapter.enableForegroundNdefPush(StickyNotesActivity.this, getNoteAsNdef());
            }
        }
    };

    private void promptForContent(final NdefMessage msg) {
        new AlertDialog.Builder(this).setTitle("Replace current content?")
            .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface arg0, int arg1) {
                    String body = new String(msg.getRecords()[0].getPayload());
                    if(body.contains("ssid=") && body.contains("pw="))
                    {
                    	textStatus.setText("");
                    	textStatus.append("Network Info Found!\n");
                    	String ssid = body.substring(5, body.indexOf('\n'));
                    	String pw = body.substring(body.indexOf('\n') + 4);
                    	textStatus.append("SSID: " + ssid + " pw: " + pw + "\n");
                    	saveWepConfig(ssid, pw);
                    	setNoteBody("Wifi info has been added to your phone!");
                    }
                    	
                    setNoteBody(body);
                }
            })
            .setNegativeButton("No", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface arg0, int arg1) {
                    
                }
            }).show();
    }

    private void setNoteBody(String body) {
        Editable text = mNote.getText();
        text.clear();
        text.append(body);
    }

    private NdefMessage getNoteAsNdef() {
        byte[] textBytes = mNote.getText().toString().getBytes();
        NdefRecord textRecord = new NdefRecord(NdefRecord.TNF_MIME_MEDIA, "text/plain".getBytes(),
                new byte[] {}, textBytes);
        return new NdefMessage(new NdefRecord[] {
            textRecord
        });
    }

    NdefMessage[] getNdefMessages(Intent intent) {
        // Parse the intent
        NdefMessage[] msgs = null;
        String action = intent.getAction();
        if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)
                || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {
            Parcelable[] rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
            if (rawMsgs != null) {
                msgs = new NdefMessage[rawMsgs.length];
                for (int i = 0; i < rawMsgs.length; i++) {
                    msgs[i] = (NdefMessage) rawMsgs[i];
                }
            } else {
                // Unknown tag type
                byte[] empty = new byte[] {};
                NdefRecord record = new NdefRecord(NdefRecord.TNF_UNKNOWN, empty, empty, empty);
                NdefMessage msg = new NdefMessage(new NdefRecord[] {
                    record
                });
                msgs = new NdefMessage[] {
                    msg
                };
            }
        } else {
            Log.d(TAG, "Unknown intent.");
            finish();
        }
        return msgs;
    }

    @Override
	public void onStop() {
    	super.onStop();
	}
    
    private void enableNdefExchangeMode() {
        mNfcAdapter.enableForegroundNdefPush(StickyNotesActivity.this, getNoteAsNdef());
        mNfcAdapter.enableForegroundDispatch(this, mNfcPendingIntent, mNdefExchangeFilters, null);
    }

    private void disableNdefExchangeMode() {
        mNfcAdapter.disableForegroundNdefPush(this);
        mNfcAdapter.disableForegroundDispatch(this);
    }

    private void enableTagWriteMode() {
        mWriteMode = true;
        IntentFilter tagDetected = new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED);
        mWriteTagFilters = new IntentFilter[] {
            tagDetected
        };
        mNfcAdapter.enableForegroundDispatch(this, mNfcPendingIntent, mWriteTagFilters, null);
    }

    private void disableTagWriteMode() {
        mWriteMode = false;
        mNfcAdapter.disableForegroundDispatch(this);
    }

    boolean writeTag(NdefMessage message, Tag tag) {
        int size = message.toByteArray().length;

        try {
            Ndef ndef = Ndef.get(tag);
            if (ndef != null) {
                ndef.connect();

                if (!ndef.isWritable()) {
                    toast("Tag is read-only.");
                    return false;
                }
                if (ndef.getMaxSize() < size) {
                    toast("Tag capacity is " + ndef.getMaxSize() + " bytes, message is " + size
                            + " bytes.");
                    return false;
                }

                ndef.writeNdefMessage(message);
                toast("Wrote message to pre-formatted tag.");
                return true;
            } else {
                NdefFormatable format = NdefFormatable.get(tag);
                if (format != null) {
                    try {
                        format.connect();
                        format.format(message);
                        toast("Formatted tag and wrote message");
                        return true;
                    } catch (IOException e) {
                        toast("Failed to format tag.");
                        return false;
                    }
                } else {
                    toast("Tag doesn't support NDEF.");
                    return false;
                }
            }
        } catch (Exception e) {
            toast("Failed to write tag");
        }

        return false;
    }

    void readWepConfig()
    { 
        //WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE); 
        List<WifiConfiguration> item = wifi.getConfiguredNetworks();
        int i = item.size();
        Log.d("WifiPreference", "NO OF CONFIG " + i );
        for(WifiConfiguration config : item)
        {
        textStatus.append( "SSID" + config.SSID + "\n");
        textStatus.append(" PASSWORD" + config.preSharedKey + "\n");
        Log.d("WifiPreference", "ALLOWED ALGORITHMS");
        Log.d("WifiPreference", "LEAP" + config.allowedAuthAlgorithms.get(AuthAlgorithm.LEAP));
        Log.d("WifiPreference", "OPEN" + config.allowedAuthAlgorithms.get(AuthAlgorithm.OPEN));
        Log.d("WifiPreference", "SHARED" + config.allowedAuthAlgorithms.get(AuthAlgorithm.SHARED));
        Log.d("WifiPreference", "GROUP CIPHERS");
        Log.d("WifiPreference", "CCMP" + config.allowedGroupCiphers.get(GroupCipher.CCMP));
        Log.d("WifiPreference", "TKIP" + config.allowedGroupCiphers.get(GroupCipher.TKIP));
        Log.d("<WifiPreference", "WEP104" + config.allowedGroupCiphers.get(GroupCipher.WEP104));
        Log.d("WifiPreference", "WEP40" + config.allowedGroupCiphers.get(GroupCipher.WEP40));
        Log.d("WifiPreference", "KEYMGMT");
        Log.d("WifiPreference", "IEEE8021X" + config.allowedKeyManagement.get(KeyMgmt.IEEE8021X));
        Log.d("WifiPreference", "NONE" + config.allowedKeyManagement.get(KeyMgmt.NONE));
        Log.d("WifiPreference", "WPA_EAP" + config.allowedKeyManagement.get(KeyMgmt.WPA_EAP));
        Log.d("WifiPreference", "WPA_PSK" + config.allowedKeyManagement.get(KeyMgmt.WPA_PSK));
        Log.d("WifiPreference", "PairWiseCipher");
        Log.d("WifiPreference", "CCMP" + config.allowedPairwiseCiphers.get(PairwiseCipher.CCMP));
        Log.d("WifiPreference", "NONE" + config.allowedPairwiseCiphers.get(PairwiseCipher.NONE));
        Log.d("WifiPreference", "TKIP" + config.allowedPairwiseCiphers.get(PairwiseCipher.TKIP));
        Log.d("WifiPreference", "Protocols");
        Log.d("WifiPreference", "RSN" + config.allowedProtocols.get(Protocol.RSN));
        Log.d("WifiPreference", "WPA" + config.allowedProtocols.get(Protocol.WPA));
        Log.d("WifiPreference", "WEP Key Strings");
        String[] wepKeys = config.wepKeys;
        textStatus.append( "   WEP KEY 0" + wepKeys[0] + "\n");
        textStatus.append( "   WEP KEY 1" + wepKeys[1] + "\n");
        textStatus.append( "   WEP KEY 2" + wepKeys[2] + "\n");
        textStatus.append( "   WEP KEY 3" + wepKeys[3] + "\n");
        }
    }
    
    void saveWepConfig(String SSID, String passkey)
    {
        WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        WifiConfiguration wc = new WifiConfiguration(); 
        wc.SSID = "\"" + SSID + "\""; //IMP! This should be in Quotes!!
        wc.hiddenSSID = false;
        
        wc.status = WifiConfiguration.Status.DISABLED;     
        wc.priority = 40;
        wc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        wc.allowedProtocols.set(WifiConfiguration.Protocol.RSN); 
        wc.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
        wc.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
        wc.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
        wc.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
        wc.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
        wc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
        wc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);
        wc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
        wc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);

        wc.wepKeys[0] = "\"" + passkey + "\""; //This is the WEP Password
        wc.wepTxKeyIndex = 0;

        //WifiManager  wifiManag = (WifiManager) this.getSystemService(WIFI_SERVICE);
        boolean res1 = wifi.setWifiEnabled(true);
        int res = wifi.addNetwork(wc);
        textStatus.append("add Network returned " + res + "enabled: " + res1 + "\n");
        boolean es = wifi.saveConfiguration();
        textStatus.append("saveConfiguration returned " + es +"\n");
        boolean b = wifi.enableNetwork(res, true);   
        textStatus.append("enableNetwork returned " + b +"\n");  

    }
    
    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
    
    /**implementing buttons*/
	public void onFacebookClicked(View view)
	{  //TODO complete
		toast("facebook");
		wifiMode = false; // This is only temporary until we decide a context handling thingy
	}  
	
	public void onTwitterClicked(View view)
	{  //TODO complete
		toast("twitter");
		wifiMode = false; // This is only temporary until we decide a context handling thingy
	} 
	
	public void onFoursquareClicked(View view)
	{  //TODO complete
		toast("foursquare");
		wifiMode = false; // This is only temporary until we decide a context handling thingy
	} 
	
	public void onWifiClicked(View view)
	{  //TODO In Progress
		wifiMode = true;
		wifiConfigIndex++;
		List<WifiConfiguration> configs = wifi.getConfiguredNetworks();
        int i = configs.size();
        if(wifiConfigIndex >= i)
        	{wifiConfigIndex = 0;}
        if(i == 0)
        {textStatus.setText("No networks saved. Create one to share!\n");}
        else
        {
        int count = 0;
        textStatus.setText("");
       	textStatus.append("Tap again to cycle network to share\n");
        for(WifiConfiguration config : configs)
        {
        	char selected = 'O';
        	if(count == wifiConfigIndex)
        	{selected = 'X';}
        	textStatus.append("[" + selected + "]" + config.SSID + "\n" );
        	count++;
        	}
        }
		
	} 
	
	public void onWriteClicked(View view)
	{  
		// Write to a tag for as long as the dialog is shown.
        disableNdefExchangeMode();
        enableTagWriteMode();

        new AlertDialog.Builder(StickyNotesActivity.this).setTitle("Touch tag to write")
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        disableTagWriteMode();
                        enableNdefExchangeMode();
                    }
                }).create().show();
	}  
}