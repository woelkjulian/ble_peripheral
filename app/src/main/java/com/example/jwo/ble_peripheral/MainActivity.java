package com.example.jwo.ble_peripheral;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public class MainActivity extends Activity implements View.OnClickListener {

    private static final String TAG = "MainActivity";
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;
    private BluetoothGattServer mGattServer;
    private BluetoothGattCharacteristic mAlarmCharacteristic;
    private BluetoothGattService mAlarmService;
    private BluetoothGattDescriptor mAlarmDescriptor;
    private byte[] storedValue;
    private ListView listView;
    private AufnahmeTask aufnahmeTask;
    private ArrayList<BluetoothDevice> mConnectedDevices;
    private ArrayAdapter<BluetoothDevice> mConnectedDevicesAdapter;
    private static final UUID ALARM_SERVICE_UUID = UUID.fromString("FF890198-9446-4E3A-B173-4E1161D6F59B");
    private static final UUID ALARM_CHARACTERISTIC_UUID = UUID.fromString("77B4350C-DEA3-4DBA-B650-251670F4B2B4");
    private static final UUID ALARM_DESCRIPTOR_UUID = UUID.fromString("78BC6802-D599-40DC-83E1-C1AB25ACCB18");
    private boolean oldAlarmValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        listView = (ListView) findViewById(R.id.listView);

        mConnectedDevices = new ArrayList<BluetoothDevice>();
        mConnectedDevicesAdapter = new CentralItemAdapter(this, mConnectedDevices);
        listView.setAdapter(mConnectedDevicesAdapter);
        mBluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        storedValue = new byte[4];
        oldAlarmValue = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            //Bluetooth is disabled
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(enableBtIntent);
            finish();
            return;
        }
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "No LE Support.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (!mBluetoothAdapter.isMultipleAdvertisementSupported()) {
            Toast.makeText(this, "No Advertising Support.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        mBluetoothLeAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        mGattServer = mBluetoothManager.openGattServer(this, mGattServerCallback);

        initServer();
        startAdvertising();

        setAlarm(false);

        aufnahmeTask = new AufnahmeTask(this);
        aufnahmeTask.execute();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAdvertising();
        shutdownServer();
        aufnahmeTask.cancel(true);
        aufnahmeTask = null;
    }

    private void initServer() {
        mAlarmService           = new BluetoothGattService(ALARM_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        mAlarmCharacteristic    = new BluetoothGattCharacteristic(ALARM_CHARACTERISTIC_UUID,
                                    BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE,
                                    BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);

        mAlarmDescriptor  = new BluetoothGattDescriptor(ALARM_DESCRIPTOR_UUID, BluetoothGattDescriptor.PERMISSION_WRITE | BluetoothGattDescriptor.PERMISSION_WRITE);
        mAlarmDescriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
        mAlarmCharacteristic.addDescriptor(mAlarmDescriptor);
        mAlarmService.addCharacteristic(mAlarmCharacteristic);
        mGattServer.addService(mAlarmService);
    }

    private void shutdownServer() {
        if (mGattServer == null) return;
        mGattServer.close();
    }

    private BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            super.onConnectionStateChange(device, status, newState);
            Log.i(TAG, "onConnectionStateChange "
                    +getStatusDescription(status)+" "
                    +getStateDescription(newState));

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.v(TAG, "Gatt connected");
                postDeviceChange(device, true);

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.v(TAG, "Gatt disconnected");
                postDeviceChange(device, false);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device,
                                                int requestId,
                                                int offset,
                                                BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
            Log.i(TAG, "onCharacteristicReadRequest " + characteristic.getUuid().toString());

            if (ALARM_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                mGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        getStoredValue());
            }

            mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device,
                                                 int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite,
                                                 boolean responseNeeded,
                                                 int offset,
                                                 byte[] value) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);
            Log.i(TAG, "onCharacteristicWriteRequest "+characteristic.getUuid().toString());

            if (ALARM_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                setStoredValue(value);

                if (responseNeeded) {
                    mGattServer.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            value);
                }

                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "Value Updated", Toast.LENGTH_SHORT).show();
                    }
                });

                notifyConnectedDevices();
            }
        }
    };


    private void startAdvertising() {
        Log.v(TAG,"start Advertising");
        if (mBluetoothLeAdvertiser == null) return;

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();

        mBluetoothLeAdvertiser.startAdvertising(settings, data, mAdvertiseCallback);
    }

    private void stopAdvertising() {
        if (mBluetoothLeAdvertiser == null) return;

        mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
    }

    private AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "Peripheral Advertise Started.");
            postStatusMessage("GATT Server Ready");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.w(TAG, "Peripheral Advertise Failed: "+errorCode);
            postStatusMessage("GATT Server Error "+errorCode);
        }
    };

    private Handler mHandler = new Handler();
    private void postStatusMessage(final String message) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                setTitle(message);
            }
        });
    }

    private void postDeviceChange(final BluetoothDevice device, final boolean toAdd) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
            //This will add the item to our list and update the adapter at the same time.
            if (toAdd) {
                mConnectedDevicesAdapter.add(device);
            } else {
                mConnectedDevicesAdapter.remove(device);
            }

            //Trigger our periodic notification once devices are connected
            mConnectedDevicesAdapter.notifyDataSetChanged();
            }
        });
    }

    private void notifyConnectedDevices() {
        for (BluetoothDevice device : mConnectedDevices) {
            BluetoothGattCharacteristic readCharacteristic = mGattServer.getService(ALARM_SERVICE_UUID)
                    .getCharacteristic(ALARM_CHARACTERISTIC_UUID);
            readCharacteristic.setValue(getStoredValue());
            mGattServer.notifyCharacteristicChanged(device, readCharacteristic, false);
        }
    }

    private Object mLock = new Object();

    private byte[] getStoredValue() {
        synchronized (mLock) {
            return storedValue;
        }
    }

    private void setStoredValue(byte[] newValue) {
        synchronized (mLock) {
            storedValue = newValue;
            if (unsignedIntFromBytes(storedValue) == 1) {
                ((CentralItemAdapter) mConnectedDevicesAdapter).setState(CentralItemAdapter.States.ALARM);
                mConnectedDevicesAdapter.notifyDataSetChanged();
                invalidateOptionsMenu();
            } else if (unsignedIntFromBytes(storedValue) == 0) {
                ((CentralItemAdapter) mConnectedDevicesAdapter).setState(CentralItemAdapter.States.STANDARD);
                mConnectedDevicesAdapter.notifyDataSetChanged();
                invalidateOptionsMenu();
            }
        }
    }

    @Override
    public void onClick(View v) {

    }

    public static String getStateDescription(int state) {
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED:
                return "Connected";
            case BluetoothProfile.STATE_CONNECTING:
                return "Connecting";
            case BluetoothProfile.STATE_DISCONNECTED:
                return "Disconnected";
            case BluetoothProfile.STATE_DISCONNECTING:
                return "Disconnecting";
            default:
                return "Unknown State "+state;
        }
    }

    public static String getStatusDescription(int status) {
        switch (status) {
            case BluetoothGatt.GATT_SUCCESS:
                return "SUCCESS";
            default:
                return "Unknown Status "+status;
        }
    }

    public static int unsignedIntFromBytes(byte[] raw) {
        if (raw.length < 4) throw new IllegalArgumentException("Cannot convert raw data to int");

        return ((raw[0] & 0xFF)
                + ((raw[1] & 0xFF) << 8)
                + ((raw[2] & 0xFF) << 16)
                + ((raw[3] & 0xFF) << 24));
    }

    public static byte[] bytesFromInt(int value) {
        //Convert result into raw bytes. GATT APIs expect LE order
        return ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(value)
                .array();
    }

    public void setAlarm(boolean b) {
        byte[] value = new byte[4];

        if(oldAlarmValue != b) {
            Log.i(TAG, "send new Notification");
            if(b) {
                Log.i(TAG, "ALARM TRIGGERED");
                value[0] = (byte) (01 & 0xFF);
                setStoredValue(value);
            } else {
                value[0] = (byte) (00 & 0xFF);
                setStoredValue(value);
            }
            notifyConnectedDevices();

            oldAlarmValue = b;
        }

    }
}