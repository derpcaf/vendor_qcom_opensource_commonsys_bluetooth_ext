/*
 * Copyright (c) 2017, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *    * Redistributions of source code must retain the above copyright
 *      notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 *      copyright notice, this list of conditions and the following
 *      disclaimer in the documentation and/or other materials provided
 *      with the distribution.
 *    * Neither the name of The Linux Foundation nor the names of its
 *      contributors may be used to endorse or promote products derived
 *      from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.pbap;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothPbap;
import android.content.Context;
import android.database.Cursor;
import android.database.CursorWindowAllocationException;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemProperties;
import android.os.UserManager;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.util.Log;

import com.android.bluetooth.ObexServerSockets;
import com.android.bluetooth.pbap.BluetoothPbapObexServer.AppParamValue;
import com.android.bluetooth.pbap.BluetoothPbapObexServer.ContentType;
import com.android.bluetooth.pbap.BluetoothPbapVcardManager.VCardFilter;
import com.android.bluetooth.R;
import com.android.bluetooth.opp.BTOppUtils;
import com.android.bluetooth.util.DevicePolicyUtils;
import com.android.bluetooth.sdp.SdpManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.AbstractionLayer;

import java.util.ArrayList;
import java.util.Collections;

import javax.obex.ServerSession;

public class BluetoothPbapFixes {

    private static final String TAG = "BluetoothPbapFixes";

    public static final boolean DEBUG = BluetoothPbapService.DEBUG;

    public static final boolean VERBOSE = BluetoothPbapService.VERBOSE;

    static final String[] PHONES_PROJECTION = new String[] {
        Data._ID, // 0
        CommonDataKinds.Phone.TYPE, // 1
        CommonDataKinds.Phone.LABEL, // 2
        CommonDataKinds.Phone.NUMBER, // 3
        Contacts.DISPLAY_NAME, // 4
    };

    protected static final int SDP_PBAP_LEGACY_SERVER_VERSION = 0x0101;

    protected static final int SDP_PBAP_LEGACY_SUPPORTED_REPOSITORIES = 0x0001;

    protected static final int SDP_PBAP_LEGACY_SUPPORTED_FEATURES = 0x0003;

    private static final int SDP_PBAP_SUPPORTED_REPOSITORIES = 0x0003;

    private static final int SDP_PBAP_SERVER_VERSION = 0x0102;

    private static final int SDP_PBAP_SUPPORTED_FEATURES = 0x021F;

    protected static boolean isSimSupported = true;

    protected static boolean isSupportedPbap12 = true;

    protected static Context sContext;

    private static final int ORDER_BY_ALPHABETICAL = 1;

    /* To get feature support from config file */
    protected static void getFeatureSupport(Context context) {
        sContext = context;
        AdapterService adapterService = AdapterService.getAdapterService();
        if (adapterService != null) {
            isSupportedPbap12 = adapterService.getProfileInfo(AbstractionLayer.PBAP, AbstractionLayer.PBAP_0102_SUPPORT);
            isSimSupported = adapterService.getProfileInfo(AbstractionLayer.PBAP, AbstractionLayer.USE_SIM_SUPPORT);
            if (DEBUG) Log.d(TAG, "isSupportedPbap12: " + isSupportedPbap12);
            if (DEBUG) Log.d(TAG, "isSimSupported: " + isSimSupported);
        }
    }

    /* To sort name list obtained when search attribute is number*/
    public static void sortNameList(int mOrderBy, String searchValue,
            ArrayList<String> names) {

        // Check if the order required is alphabetic
        if (mOrderBy == BluetoothPbapObexServer.ORDER_BY_ALPHABETICAL) {
            if (VERBOSE)
                Log.v(TAG, "Name list created for Search Value ="+ searchValue
                    +". Order By: " + mOrderBy);
            Collections.sort(names);
        }
    }

    /* Used to fetch vCard entry from contact cursor for required handle value*/
    public static MatrixCursor getVcardEntry(Cursor contactCursor,
            MatrixCursor contactIdsCursor, int contactIdColumn, int startPoint) {
        while (contactCursor.moveToNext()) {
            long currentContactId = contactCursor.getLong(contactIdColumn);
             if (currentContactId == startPoint) {
                contactIdsCursor.addRow(new Long[]{currentContactId});
                if (VERBOSE) Log.v(TAG, "contactIdsCursor.addRow: " + currentContactId);
                break;
            }
        }
        BluetoothPbapVcardManager.isPullVcardEntry = false;
        return contactIdsCursor;
    }

    /* Used to fetch handle value from the name in request*/
    public static String getHandle(String value) {
        if (value != null) {
            return value.substring(value.lastIndexOf(',') + 1, value.length());
        }
        return "-1";
    }

    /* Used to position a given vCard entry in list for vCardListing*/
    public static ArrayList<Integer> addToListAtPos(ArrayList<Integer> list,
            int pos, String handle) {
        if (handle != null && Integer.parseInt(handle) >= 0) {
            list.add(Integer.parseInt(handle));
        } else {
            list.add(pos);
        }
        return list;
    }

    /* TO check if given contact_id for given handle value is present or not*/
    public static final boolean checkContactsVcardId(int id, Context mContext) {
        if (id == 0) {
            return true;
        }
        Cursor contactCursor = null;
        try {
            contactCursor = mContext.getContentResolver().query(Phone.CONTENT_URI,
                    PHONES_PROJECTION, Phone.CONTACT_ID+"= ?",
                    new String[] {id + ""}, null);

            if (contactCursor != null && contactCursor.getCount() > 0) {
                return true;
            } else {
                return false;
            }
        } catch (CursorWindowAllocationException e) {
            Log.e(TAG, "CursorWindowAllocationException while checking Contacts id");
        } finally {
            if (contactCursor != null) {
                contactCursor.close();
            }
        }
        return false;
    }

    public static String initCreateProfileVCard(String vcard, Context mContext,
            int vcardType, byte [] filter,final boolean vcardType21, boolean ignorefilter,
            VCardFilter vcardfilter) {
        vcard = BluetoothPbapUtils.createProfileVCard(mContext, vcardType, filter);
        if ((vcard != null) && !ignorefilter) {
            vcard = vcardfilter.apply(vcard, vcardType21);
        }
        return vcard;
    }

    public static void filterSearchedListByOffset(ArrayList<String> selectedNameList,
            ArrayList<Integer> savedPosList, int listStartOffset, int itemsFound,
            int requestSize, StringBuilder result, BluetoothPbapObexServer server) {
        for (int j = listStartOffset; j < selectedNameList.size() &&
            itemsFound < requestSize; j++) {
            itemsFound++;
            server.writeVCardEntry(savedPosList.get(j), selectedNameList.get(j),result);
        }

        selectedNameList.clear();
        savedPosList.clear();
    }

    /* To create spd record when pbap 1.2 support is not there and depending on
     * sim support value*/
    protected static void createSdpRecord(ObexServerSockets serverSockets,
            BluetoothPbapService service) {
        if (!isSupportedPbap12 && !isSimSupported) {
            Log.i(TAG ," with out sim and pbp 1.2 ");
            service.mSdpHandle = SdpManager.getDefaultManager().createPbapPseRecord
                ("OBEX Phonebook Access Server",serverSockets.getRfcommChannel(),
                -1, BluetoothPbapFixes.SDP_PBAP_LEGACY_SERVER_VERSION,
                BluetoothPbapFixes.SDP_PBAP_LEGACY_SUPPORTED_REPOSITORIES,
                BluetoothPbapFixes.SDP_PBAP_LEGACY_SUPPORTED_FEATURES);
        } else if (!isSupportedPbap12 && isSimSupported) {
            Log.i(TAG ," with sim with out pbp 1.2 ");
            service.mSdpHandle = SdpManager.getDefaultManager().createPbapPseRecord
                ("OBEX Phonebook Access Server",serverSockets.getRfcommChannel(),
                -1, BluetoothPbapFixes.SDP_PBAP_LEGACY_SERVER_VERSION,
                SDP_PBAP_SUPPORTED_REPOSITORIES,
                BluetoothPbapFixes.SDP_PBAP_LEGACY_SUPPORTED_FEATURES);
        } else {
            Log.i(TAG ," with sim with pbp 1.2 ");
            service.mSdpHandle = SdpManager.getDefaultManager().createPbapPseRecord
            ("OBEX Phonebook Access Server",serverSockets.getRfcommChannel(),
                serverSockets.getL2capPsm(), SDP_PBAP_SERVER_VERSION,
                SDP_PBAP_SUPPORTED_REPOSITORIES,
                SDP_PBAP_SUPPORTED_FEATURES);
        }
    }

    protected static void updateMtu(ServerSession serverSession, boolean isSrmSupported,
            int rfcommMaxMTU) {
        String offloadSupported = SystemProperties.get("persist.vendor.bt.enable.splita2dp");
        if (DEBUG) Log.d(TAG, "offloadSupported :" + offloadSupported + " isSrmSupported :" +
                isSrmSupported + " isA2DPConnected :" + BTOppUtils.isA2DPConnected +
                " rfcommMaxMTU :" + rfcommMaxMTU);
        if (offloadSupported.isEmpty() || offloadSupported.equals("true")) {
            if (!isSrmSupported && BTOppUtils.isA2DPConnected && rfcommMaxMTU > 0) {
                serverSession.updateMTU(rfcommMaxMTU);
            }
        }
    }

    public static MatrixCursor filterOutSimContacts(Cursor contactCursor) {
        if (contactCursor == null)
            return null;

        MatrixCursor mCursor = new MatrixCursor(new String[]{
                    Phone.CONTACT_ID
        });
        final int contactIdColumn = contactCursor.getColumnIndex(Data.CONTACT_ID);
        final int account_col_id = contactCursor.getColumnIndex(Phone.ACCOUNT_TYPE_AND_DATA_SET);
        long previousContactId = -1;
        contactCursor.moveToPosition(-1);
        while (contactCursor.moveToNext()) {
            long currentContactId = contactCursor.getLong(contactIdColumn);
            String accType = contactCursor.getString(account_col_id);
            if (previousContactId != currentContactId &&
                    !(accType != null && accType.startsWith("com.android.sim"))) {
                if (VERBOSE)
                    Log.v(TAG, "currentContactId = " + currentContactId);
                previousContactId = currentContactId;
                mCursor.addRow(new Long[]{currentContactId});
            }
        }
        return mCursor;
    }

    /* Get account type for given contact*/
    public static String getAccount(long contactId) {
        String account = null;
        Uri uri = DevicePolicyUtils.getEnterprisePhoneUri(sContext);
        String whereClause = Phone.CONTACT_ID + "=?";
        String [] selectionArgs = {String.valueOf(contactId)};
        Cursor cursor = sContext.getContentResolver().query(uri,
                BluetoothPbapVcardManager.PHONES_CONTACTS_PROJECTION,
                whereClause, selectionArgs, Phone.CONTACT_ID);
        if (cursor != null) {
            cursor.moveToPosition(-1);
            while (cursor.moveToNext()) {
                long cid = cursor.getLong(cursor.getColumnIndex(Phone.CONTACT_ID));
                account = cursor.getString(cursor.getColumnIndex(Phone.ACCOUNT_TYPE_AND_DATA_SET));
                if (VERBOSE)
                    Log.v(TAG, "For cid = " + cid + ", account = " + account);
            }
            cursor = null;
        }
        return account;
    }

    static int createList(BluetoothPbapSimVcardManager vcardSimManager, BluetoothPbapVcardManager
            vcardManager, BluetoothPbapObexServer server, boolean vcardSelector, int orderBy,
            AppParamValue appParamValue, int needSendBody, int size, StringBuilder result,
            String type) {
        int itemsFound = 0;
         ArrayList<String> nameList = null;

        if (appParamValue.needTag == ContentType.SIM_PHONEBOOK) {
            nameList = vcardSimManager.getSIMPhonebookNameList(orderBy);
        } else if (isSupportedPbap12 && vcardSelector) {
            nameList = vcardManager.getSelectedPhonebookNameList(orderBy, appParamValue.vcard21,
                    needSendBody, size, appParamValue.vCardSelector,
                    appParamValue.vCardSelectorOperator);
        } else {
            nameList = vcardManager.getPhonebookNameList(orderBy);
        }

        final int requestSize =
                nameList.size() >= appParamValue.maxListCount ? appParamValue.maxListCount
                        : nameList.size();
        final int listSize = nameList.size();
        String compareValue = "", currentValue;

        if (DEBUG) {
            Log.d(TAG, "search by " + type + ", requestSize=" + requestSize + " offset="
                    + appParamValue.listStartOffset + " searchValue=" + appParamValue.searchValue);
        }
        ArrayList<String> selectedNameList = new ArrayList<String>();
        ArrayList<Integer> savedPosList = new ArrayList<>();
        if (type.equals("number")) {
            // query the number, to get the names
            ArrayList<String> names = vcardSimManager.retrieveContactNamesByNumber
                    ((appParamValue.needTag == ContentType.SIM_PHONEBOOK ),
                            vcardManager, appParamValue.searchValue);
            if (orderBy == ORDER_BY_ALPHABETICAL) Collections.sort(names);
            for (int i = 0; i < names.size(); i++) {
                String handle = "-1";
                compareValue = names.get(i).trim();
                if (DEBUG) {
                    Log.d(TAG, "compareValue=" + compareValue);
                }
                for (int pos = 0; pos < listSize; pos++) {
                    currentValue = nameList.get(pos);
                    if (VERBOSE) {
                        Log.d(TAG, "currentValue=" + currentValue);
                    }
                    if (currentValue.equals(compareValue)) {
                        if (currentValue.contains(",")) {
                            handle = BluetoothPbapFixes.getHandle(currentValue);
                            currentValue = currentValue.substring(0, currentValue.lastIndexOf(','));
                        }
                        selectedNameList.add(currentValue);
                        savedPosList = BluetoothPbapFixes.addToListAtPos(savedPosList, pos, handle);
                    }
                }
            }
            filterSearchedListByOffset(selectedNameList, savedPosList,
                    appParamValue.listStartOffset, itemsFound, requestSize, result, server);
        } else {
            if (appParamValue.searchValue != null) {
                compareValue = appParamValue.searchValue.trim().toLowerCase();
            }
            for (int pos = 0; pos < listSize; pos++) {
                String handle = "-1";
                currentValue = nameList.get(pos);
                if (currentValue.contains(",")) {
                    handle = BluetoothPbapFixes.getHandle(currentValue);
                    currentValue = currentValue.substring(0, currentValue.lastIndexOf(','));
                }

                if (appParamValue.searchValue != null) {
                    if (appParamValue.searchValue.isEmpty() ||
                        ((currentValue.toLowerCase()).startsWith(compareValue.toLowerCase()))) {
                        selectedNameList.add(currentValue);
                        savedPosList = BluetoothPbapFixes.addToListAtPos(savedPosList, pos, handle);
                    }
                }
            }
            filterSearchedListByOffset(selectedNameList, savedPosList,
                    appParamValue.listStartOffset, itemsFound, requestSize, result, server);
        }
        return itemsFound;
    }

}