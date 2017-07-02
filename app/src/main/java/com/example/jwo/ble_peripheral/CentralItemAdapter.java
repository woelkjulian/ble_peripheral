package com.example.jwo.ble_peripheral;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Created by jwo on 02.07.17.
 */

public class CentralItemAdapter extends ArrayAdapter {
    private ArrayList<BluetoothDevice> devices;
    private Context context;
    private ViewHolder mViewHolder;
    private LayoutInflater mInflater;
    private static final String TAG = "CentralItemAdapter:";
    private States state;
    public enum States {
        STANDARD,
        ALARM
    }

    public CentralItemAdapter(Context context, ArrayList<BluetoothDevice> devices) {
        super(context, 0, devices);
        this.context = context;
        this.devices = devices;
        state = States.STANDARD;
        mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    public void addItem(BluetoothDevice device) {
        devices.add(device);
        notifyDataSetChanged();
    }


    public void removeItem(BluetoothDevice device) {
        devices.remove(device);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        // TODO Auto-generated method stub
        return devices.size();
    }

    @Override
    public BluetoothDevice getItem(int position) {
        // TODO Auto-generated method stub
        return devices.get(position);
    }

    @Override
    public long getItemId(int position) {
        // TODO Auto-generated method stub
        return position;
    }

    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {

        if (convertView == null) {
            // Inflate your view
            convertView = mInflater.inflate(R.layout.central, parent, false);
            mViewHolder = new ViewHolder();
            mViewHolder.deviceItem      = (LinearLayout) convertView.findViewById(R.id.deviceItem);
            mViewHolder.deviceAdress    = (TextView) convertView.findViewById(R.id.txtDeviceAdress);

            BluetoothDevice d = getItem(position);
            mViewHolder.deviceAdress.setText(d.getAddress());
            convertView.setTag(mViewHolder);
        } else {
            mViewHolder = (ViewHolder) convertView.getTag();
        }

        switch(state) {
            case STANDARD:
                mViewHolder.deviceItem.setBackground(ContextCompat.getDrawable(context,R.drawable.devicelayout_bg));
                break;
            case ALARM:
                mViewHolder.deviceItem.setBackground(ContextCompat.getDrawable(context,R.drawable.devicelayout_alarm_bg));
                break;
            default:
        }
        mViewHolder.deviceItem.invalidate();

        // Return the completed view to render on screen
        return convertView;
    }

    public void setState(States state) {
        this.state = state;
    }

    private static class ViewHolder {
        LinearLayout deviceItem;
        TextView deviceAdress;
    }
}