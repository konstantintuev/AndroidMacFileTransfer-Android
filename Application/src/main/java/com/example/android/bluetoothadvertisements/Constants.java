/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.example.android.bluetoothadvertisements;

import android.bluetooth.BluetoothGattCharacteristic;
import android.os.ParcelUuid;

/**
 * Constants for use in the Bluetooth Advertisements sample
 */
public class Constants {

    /**
     * UUID identified with this app - set as Service UUID for BLE Advertisements.
     *
     * Bluetooth requires a certain format for UUIDs associated with Services.
     * The official specification can be found here:
     * {@link https://www.bluetooth.org/en-us/specification/assigned-numbers/service-discovery}
     */
    public static final ParcelUuid Service_UUID = ParcelUuid
            .fromString("63DC1570-461D-47C9-B62F-9A715392AF94");

    public static final int REQUEST_ENABLE_BT = 1;

    public static final ParcelUuid WriteCharacteristic_UUID = ParcelUuid
            .fromString("67574D86-41F2-402D-9FC4-906321DFF90B");

    public static final ParcelUuid ReadCharacteristic_UUID = ParcelUuid
            .fromString("57104CBE-BE83-4DC1-A7DD-D32AFE8296F8");

    public static final ParcelUuid NotifyCharacteristic_UUID = ParcelUuid
            .fromString("56F7B705-AE1E-460E-A72F-9DF587DC3BB4");

    public static final ParcelUuid writeAdvertiseCharacteristicsUUIDMac = ParcelUuid
            .fromString("B01149BF-E680-4853-A445-CA023FA5675F");
    public static final ParcelUuid advertiseServiceUUIDMac = ParcelUuid
            .fromString("F3EA9D08-1852-4ED5-BD8A-69826C2A92F2");

    public static final String RECEIVE_FILE = "send_file";
    public static final String TURN_WIFI_TETHER_OFF = "wifi_ap_off";
}
